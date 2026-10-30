//! Download engine.
//!
//! * bounded parallelism (configurable concurrency)
//! * retries with exponential backoff and jitter for transient failures
//! * resume of interrupted transfers through HTTP `Range` on `.part` files
//! * mandatory SHA1/size verification when the manifest provides them
//! * atomic rename into place, so a crash never leaves a truncated file at
//!   the final path

pub mod progress;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use futures::{stream, StreamExt, TryStreamExt};
use rand::Rng;
use sha1::{Digest, Sha1};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_util::sync::CancellationToken;

use crate::errors::{BloomError, BloomResult};
pub use progress::{ProgressSink, ProgressTracker};

const MAX_ATTEMPTS: u32 = 5;
const USER_AGENT: &str = concat!("BloomClient/", env!("CARGO_PKG_VERSION"), " (+launcher)");

#[derive(Debug, Clone)]
pub struct DownloadItem {
    pub url: String,
    pub path: PathBuf,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    /// Short label shown in the UI.
    pub label: String,
}

#[derive(Clone)]
pub struct Downloader {
    client: reqwest::Client,
}

impl Downloader {
    pub fn new() -> BloomResult<Self> {
        let client = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(15))
            .read_timeout(Duration::from_secs(30))
            .pool_max_idle_per_host(16)
            .https_only(true)
            .build()
            .map_err(|e| BloomError::Internal(format!("http client: {e}")))?;
        Ok(Self { client })
    }

    pub fn client(&self) -> &reqwest::Client {
        &self.client
    }

    /// GET returning bytes, with retry on transient errors.
    pub async fn fetch_bytes(&self, url: &str) -> BloomResult<Vec<u8>> {
        let mut attempt = 0;
        loop {
            attempt += 1;
            let result = async {
                let resp = self.client.get(url).timeout(Duration::from_secs(60)).send().await?;
                let resp = check_status(resp)?;
                Ok::<_, BloomError>(resp.bytes().await?.to_vec())
            }
            .await;
            match result {
                Ok(b) => return Ok(b),
                Err(e) if attempt < MAX_ATTEMPTS && is_retryable(&e) => {
                    tracing::warn!(url, attempt, error = %e, "fetch failed, retrying");
                    tokio::time::sleep(backoff(attempt)).await;
                }
                Err(e) => return Err(e),
            }
        }
    }

    /// Fetches bytes and verifies the SHA1 when provided.
    pub async fn fetch_verified(&self, url: &str, sha1: Option<&str>) -> BloomResult<Vec<u8>> {
        let bytes = self.fetch_bytes(url).await?;
        if let Some(expected) = sha1 {
            let actual = hex::encode(Sha1::digest(&bytes));
            if !actual.eq_ignore_ascii_case(expected) {
                return Err(BloomError::Integrity { path: url.to_string(), expected: expected.into(), actual });
            }
        }
        Ok(bytes)
    }

    pub async fn fetch_json<T: serde::de::DeserializeOwned>(&self, url: &str) -> BloomResult<T> {
        let bytes = self.fetch_bytes(url).await?;
        Ok(serde_json::from_slice(&bytes)?)
    }

    /// Downloads every item with at most `concurrency` transfers in flight.
    /// Already counted totals are expected to be set on the tracker.
    pub async fn download_all(
        &self,
        items: Vec<DownloadItem>,
        concurrency: usize,
        tracker: Arc<ProgressTracker>,
        cancel: CancellationToken,
    ) -> BloomResult<()> {
        let concurrency = concurrency.clamp(1, 64);
        stream::iter(items.into_iter().map(Ok::<_, BloomError>))
            .try_for_each_concurrent(concurrency, |item| {
                let tracker = tracker.clone();
                let cancel = cancel.clone();
                async move {
                    if cancel.is_cancelled() {
                        return Err(BloomError::Cancelled);
                    }
                    tracker.set_current(&item.label);
                    self.download_with_retry(&item, &tracker, &cancel).await?;
                    tracker.file_done();
                    Ok(())
                }
            })
            .await
    }

    async fn download_with_retry(
        &self,
        item: &DownloadItem,
        tracker: &ProgressTracker,
        cancel: &CancellationToken,
    ) -> BloomResult<()> {
        let mut attempt = 0;
        loop {
            attempt += 1;
            let mut counted = 0u64;
            let result = tokio::select! {
                r = self.download_once(item, tracker, &mut counted) => r,
                _ = cancel.cancelled() => Err(BloomError::Cancelled),
            };
            match result {
                Ok(()) => return Ok(()),
                Err(BloomError::Cancelled) => {
                    tracker.sub_bytes(counted);
                    return Err(BloomError::Cancelled);
                }
                Err(e) => {
                    tracker.sub_bytes(counted);
                    if attempt < MAX_ATTEMPTS && is_retryable(&e) {
                        if matches!(e, BloomError::Integrity { .. }) {
                            // Corrupted partial data: start over from scratch.
                            let _ = tokio::fs::remove_file(part_path(&item.path)).await;
                        }
                        tracing::warn!(file = %item.label, attempt, error = %e, "download failed, retrying");
                        tokio::time::sleep(backoff(attempt)).await;
                        continue;
                    }
                    return Err(match e {
                        BloomError::Network(msg) => {
                            BloomError::Network(format!("{} ({msg})", item.label))
                        }
                        other => other,
                    });
                }
            }
        }
    }

    async fn download_once(
        &self,
        item: &DownloadItem,
        tracker: &ProgressTracker,
        counted: &mut u64,
    ) -> BloomResult<()> {
        if !item.url.starts_with("https://") {
            return Err(BloomError::InvalidInput(format!("refusing non-HTTPS download: {}", item.url)));
        }
        if let Some(parent) = item.path.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        let part = part_path(&item.path);
        let mut hasher = Sha1::new();

        // Resume only when we know the expected size and the partial file is
        // strictly smaller than it.
        let mut existing = match tokio::fs::metadata(&part).await {
            Ok(m) => m.len(),
            Err(_) => 0,
        };
        if let Some(size) = item.size {
            if existing >= size {
                existing = 0;
            }
        } else {
            existing = 0;
        }

        let mut request = self.client.get(&item.url);
        if existing > 0 {
            request = request.header(reqwest::header::RANGE, format!("bytes={existing}-"));
        }
        let resp = check_status(request.send().await?)?;
        let resumed = existing > 0 && resp.status() == reqwest::StatusCode::PARTIAL_CONTENT;

        let mut file = if resumed {
            // Re-hash the bytes we already have so the final digest covers the
            // whole file.
            let mut f = tokio::fs::File::open(&part).await?;
            let mut buf = vec![0u8; 64 * 1024];
            loop {
                let n = f.read(&mut buf).await?;
                if n == 0 {
                    break;
                }
                hasher.update(&buf[..n]);
            }
            tracker.add_bytes(existing);
            *counted += existing;
            tokio::fs::OpenOptions::new().append(true).open(&part).await?
        } else {
            tokio::fs::File::create(&part).await?
        };

        let mut written = if resumed { existing } else { 0 };
        let mut body = resp.bytes_stream();
        while let Some(chunk) = body.next().await {
            let chunk = chunk?;
            file.write_all(&chunk).await?;
            hasher.update(&chunk);
            written += chunk.len() as u64;
            tracker.add_bytes(chunk.len() as u64);
            *counted += chunk.len() as u64;
        }
        file.flush().await?;
        file.sync_all().await?;
        drop(file);

        if let Some(size) = item.size {
            if written != size {
                // Keep the partial file if it is short (resumable), discard if longer.
                if written > size {
                    let _ = tokio::fs::remove_file(&part).await;
                }
                return Err(BloomError::Network(format!(
                    "incomplete transfer ({written} of {size} bytes)"
                )));
            }
        }
        if let Some(expected) = &item.sha1 {
            let actual = hex::encode(hasher.finalize());
            if !actual.eq_ignore_ascii_case(expected) {
                let _ = tokio::fs::remove_file(&part).await;
                return Err(BloomError::Integrity {
                    path: item.label.clone(),
                    expected: expected.clone(),
                    actual,
                });
            }
        }
        tokio::fs::rename(&part, &item.path).await?;
        Ok(())
    }
}

fn part_path(path: &Path) -> PathBuf {
    let mut name = path.file_name().map(|n| n.to_os_string()).unwrap_or_default();
    name.push(".part");
    path.with_file_name(name)
}

fn check_status(resp: reqwest::Response) -> BloomResult<reqwest::Response> {
    let status = resp.status();
    if status.is_success() {
        Ok(resp)
    } else {
        Err(BloomError::Network(format!("server responded with HTTP {}", status.as_u16())))
    }
}

fn is_retryable(e: &BloomError) -> bool {
    match e {
        BloomError::Network(msg) => {
            // Client errors other than 408/429 are permanent.
            !(msg.contains("HTTP 4") && !msg.contains("HTTP 408") && !msg.contains("HTTP 429"))
        }
        BloomError::Integrity { .. } | BloomError::Io(_) => true,
        _ => false,
    }
}

fn backoff(attempt: u32) -> Duration {
    let base = 400u64 * 2u64.pow(attempt.saturating_sub(1).min(5));
    let jitter = rand::thread_rng().gen_range(0..250);
    Duration::from_millis(base + jitter)
}

/// Computes the SHA1 of a file in a blocking-friendly way (call from
/// `spawn_blocking`).
pub fn sha1_file(path: &Path) -> std::io::Result<String> {
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut hasher = Sha1::new();
    let mut buf = vec![0u8; 128 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn part_path_appends_suffix() {
        let p = part_path(Path::new("/a/b/client.jar"));
        assert_eq!(p, PathBuf::from("/a/b/client.jar.part"));
    }

    #[test]
    fn retry_classification() {
        assert!(is_retryable(&BloomError::Network("server responded with HTTP 503".into())));
        assert!(is_retryable(&BloomError::Network("server responded with HTTP 429".into())));
        assert!(!is_retryable(&BloomError::Network("server responded with HTTP 404".into())));
        assert!(is_retryable(&BloomError::Network("the request timed out".into())));
        assert!(!is_retryable(&BloomError::Cancelled));
    }

    #[test]
    fn sha1_of_file() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("x.txt");
        std::fs::write(&p, b"bloom").unwrap();
        assert_eq!(sha1_file(&p).unwrap(), hex::encode(Sha1::digest(b"bloom")));
    }
}
