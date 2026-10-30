//! News feed: official Minecraft: Java Edition patch notes from Mojang's
//! launcher content service, plus optional Bloom announcements from an
//! endpoint configured at build time (`BLOOM_ANNOUNCEMENTS_URL`).

use serde::Deserialize;

use crate::database::now_ts;
use crate::errors::{BloomError, BloomResult};
use crate::models::NewsItem;
use crate::services::state::AppState;
use crate::system::paths::{safe_join, write_atomic};

const CONTENT_BASE: &str = "https://launchercontent.mojang.com";
const PATCH_NOTES_URL: &str = "https://launchercontent.mojang.com/v2/javaPatchNotes.json";
const CACHE_KEY: &str = "java_patch_notes";
const TTL_SECS: i64 = 60 * 60;

#[derive(Deserialize)]
struct PatchNotesFeed {
    entries: Vec<PatchNoteEntry>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PatchNoteEntry {
    id: String,
    title: String,
    version: Option<String>,
    #[serde(rename = "type")]
    kind: Option<String>,
    image: Option<FeedImage>,
    content_path: Option<String>,
    date: Option<String>,
    short_text: Option<String>,
}

#[derive(Deserialize)]
struct FeedImage {
    url: String,
}

#[derive(Deserialize)]
struct PatchNoteBody {
    body: String,
}

fn image_url(path: &str) -> Option<String> {
    if path.starts_with('/') {
        Some(format!("{CONTENT_BASE}{path}"))
    } else if path.starts_with(CONTENT_BASE) {
        Some(path.to_string())
    } else {
        None
    }
}

/// Returns the latest news, newest first. `body_html` holds the short text;
/// the full article is loaded on demand with [`news_article`].
pub async fn latest_news(state: &AppState, limit: usize) -> BloomResult<Vec<NewsItem>> {
    let cache_file = state.paths().meta().join("news").join("java_patch_notes.json");
    let fresh = state
        .db
        .http_cache_get(CACHE_KEY)?
        .is_some_and(|(_, at)| now_ts() - at < TTL_SECS);

    let bytes = if fresh && cache_file.exists() {
        tokio::fs::read(&cache_file).await?
    } else {
        match state.downloader.fetch_bytes(PATCH_NOTES_URL).await {
            Ok(b) => {
                // Validate before caching.
                serde_json::from_slice::<PatchNotesFeed>(&b)?;
                write_atomic(&cache_file, &b)?;
                state.db.http_cache_put(CACHE_KEY, None, now_ts())?;
                b
            }
            Err(e) => match tokio::fs::read(&cache_file).await {
                Ok(b) => b,
                Err(_) => return Err(e),
            },
        }
    };

    let feed: PatchNotesFeed = serde_json::from_slice(&bytes)?;
    let mut items: Vec<NewsItem> = feed
        .entries
        .into_iter()
        .take(limit)
        .map(|e| NewsItem {
            id: e.content_path.clone().unwrap_or(e.id),
            title: e.title,
            version: e.version,
            category: e.kind.unwrap_or_else(|| "release".into()),
            date: e.date,
            image_url: e.image.and_then(|i| image_url(&i.url)),
            body_html: e.short_text.unwrap_or_default(),
            source: "mojang".into(),
        })
        .collect();

    if let Some(url) = option_env!("BLOOM_ANNOUNCEMENTS_URL") {
        match state.downloader.fetch_json::<Vec<NewsItem>>(url).await {
            Ok(mut announcements) => {
                for a in &mut announcements {
                    a.source = "bloom".into();
                    a.image_url = None;
                }
                announcements.append(&mut items);
                items = announcements;
            }
            Err(e) => tracing::warn!(error = %e, "could not load Bloom announcements"),
        }
    }
    Ok(items)
}

/// Loads the full HTML body of a patch note. The HTML is rendered by the
/// frontend through an allow-list sanitizer, never injected directly.
pub async fn news_article(state: &AppState, content_path: &str) -> BloomResult<String> {
    if !content_path.starts_with("javaPatchNotes/") || !content_path.ends_with(".json") {
        return Err(BloomError::InvalidInput("invalid article".into()));
    }
    let news_dir = state.paths().meta().join("news");
    let cache = safe_join(&news_dir, content_path)?;
    if let Ok(bytes) = tokio::fs::read(&cache).await {
        if let Ok(b) = serde_json::from_slice::<PatchNoteBody>(&bytes) {
            return Ok(b.body);
        }
    }
    let url = format!("{CONTENT_BASE}/v2/{content_path}");
    let bytes = state.downloader.fetch_bytes(&url).await?;
    let body: PatchNoteBody = serde_json::from_slice(&bytes)?;
    write_atomic(&cache, &bytes)?;
    Ok(body.body)
}
