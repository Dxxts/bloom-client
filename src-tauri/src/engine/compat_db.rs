//! Updatable compatibility database of the Bloom Native engine.
//!
//! The engine jar ships a signed copy. The launcher fetches newer ones
//! without a launcher update: it reads `index.json` (sequence, size,
//! SHA-256, Ed25519 signature), downloads the database only if the sequence
//! is newer than the cached one, checks size, hash and signature, and only
//! then replaces the cached copy. The engine verifies the signature again
//! and keeps whichever valid copy is newest, so a broken or tampered
//! download can never be used and never disables anything.

use std::path::{Path, PathBuf};

use base64::Engine as _;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::errors::{BloomError, BloomResult};

/// Raw Ed25519 public key (base64), the same as `DatabaseSignature.PUBLIC_KEY` in the engine.
pub const PUBLIC_KEY: &str = "hpPhcERyUXEn8W7tVZyQe3iB9UflpWWhDBBtT7YdwZY=";
pub const DEFAULT_INDEX_URL: &str = "https://raw.githubusercontent.com/Dxxts/bloom-client/main/bloom-performance-engine/compat-db/index.json";
const FILE_NAME: &str = "bloom-compat-db.json";
const MAX_INDEX_BYTES: usize = 64 * 1024;
const MAX_DB_BYTES: u64 = 8 * 1024 * 1024;

#[derive(Debug, Deserialize, PartialEq)]
pub struct Index {
    pub schema: u32,
    pub sequence: u64,
    pub file: String,
    pub size: u64,
    pub sha256: String,
    pub signature: String,
}

#[derive(Debug, PartialEq)]
pub enum Outcome {
    Updated { sequence: u64 },
    UpToDate { sequence: u64 },
}

pub fn dir(meta: &Path) -> PathBuf {
    meta.join("compat-db")
}

/// The cached database, if one was downloaded and verified.
pub fn cached(meta: &Path) -> Option<PathBuf> {
    let path = dir(meta).join(FILE_NAME);
    let signed = path.with_extension("json.sig").is_file();
    (signed && path.is_file()).then_some(path)
}

fn cached_sequence(meta: &Path) -> Option<u64> {
    let text = std::fs::read_to_string(cached(meta)?).ok()?;
    serde_json::from_str::<serde_json::Value>(&text).ok()?.get("sequence")?.as_u64()
}

/// Checks a downloaded database against its index: size, SHA-256, Ed25519
/// signature with `public_key`, and that the content declares the same
/// schema and sequence as the index.
pub fn verify(bytes: &[u8], index: &Index, public_key: &str) -> Result<(), String> {
    if bytes.len() as u64 != index.size {
        return Err(format!("size {} instead of {}", bytes.len(), index.size));
    }
    let sha = hex::encode(Sha256::digest(bytes));
    if !sha.eq_ignore_ascii_case(&index.sha256) {
        return Err(format!("sha256 {sha} instead of {}", index.sha256));
    }
    let b64 = base64::engine::general_purpose::STANDARD;
    let key: [u8; 32] = b64.decode(public_key).map_err(|e| e.to_string())?.try_into().map_err(|_| "bad public key length".to_string())?;
    let key = VerifyingKey::from_bytes(&key).map_err(|e| e.to_string())?;
    let sig: [u8; 64] = b64.decode(index.signature.trim()).map_err(|e| e.to_string())?.try_into().map_err(|_| "bad signature length".to_string())?;
    key.verify(bytes, &Signature::from_bytes(&sig)).map_err(|_| "invalid signature".to_string())?;
    let content: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
    if content.get("schema").and_then(|v| v.as_u64()) != Some(index.schema as u64) || content.get("sequence").and_then(|v| v.as_u64()) != Some(index.sequence) {
        return Err("schema or sequence differs from the index".into());
    }
    Ok(())
}

/// Fetches the index at `index_url` and, if it announces a newer database,
/// downloads, verifies and caches it under `meta`.
pub async fn refresh(client: &reqwest::Client, meta: &Path, index_url: &str) -> BloomResult<Outcome> {
    let index_bytes = client.get(index_url).send().await?.error_for_status()?.bytes().await?;
    if index_bytes.len() > MAX_INDEX_BYTES {
        return Err(BloomError::Parse("compatibility index too large".into()));
    }
    let index: Index = serde_json::from_slice(&index_bytes).map_err(|e| BloomError::Parse(format!("compatibility index: {e}")))?;
    if index.schema != 1 || index.file != FILE_NAME || index.size > MAX_DB_BYTES {
        return Err(BloomError::Parse("unsupported compatibility index".into()));
    }
    let current = cached_sequence(meta).unwrap_or(0);
    if index.sequence <= current {
        return Ok(Outcome::UpToDate { sequence: current });
    }
    let url = url::Url::parse(index_url).and_then(|u| u.join(&index.file)).map_err(|e| BloomError::Parse(e.to_string()))?;
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;
    verify(&bytes, &index, PUBLIC_KEY).map_err(|actual| BloomError::Integrity {
        path: FILE_NAME.into(),
        expected: format!("signed database, sequence {}", index.sequence),
        actual,
    })?;
    let dir = dir(meta);
    // Signature first: a database file without its signature is never used.
    crate::system::paths::write_atomic(&dir.join(format!("{FILE_NAME}.sig")), index.signature.as_bytes())?;
    crate::system::paths::write_atomic(&dir.join(FILE_NAME), &bytes)?;
    Ok(Outcome::Updated { sequence: index.sequence })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The database committed in the repository, with the index made by `CompatDbTool`.
    const DB: &[u8] = include_bytes!("../../../bloom-performance-engine/compat-db/bloom-compat-db.json");
    const INDEX: &str = include_str!("../../../bloom-performance-engine/compat-db/index.json");

    #[test]
    fn the_published_database_verifies_with_the_release_key() {
        let index: Index = serde_json::from_str(INDEX).unwrap();
        assert_eq!(verify(DB, &index, PUBLIC_KEY), Ok(()));
    }

    #[test]
    fn any_change_is_rejected() {
        let index: Index = serde_json::from_str(INDEX).unwrap();
        let mut tampered = DB.to_vec();
        let at = tampered.len() / 2;
        tampered[at] ^= 0x20;
        assert!(verify(&tampered, &index, PUBLIC_KEY).unwrap_err().contains("sha256"));
        // Same bytes and hash but a forged signature.
        let forged = Index { signature: base64::engine::general_purpose::STANDARD.encode([7u8; 64]), ..serde_json::from_str(INDEX).unwrap() };
        assert_eq!(verify(DB, &forged, PUBLIC_KEY).unwrap_err(), "invalid signature");
        // An index that lies about the sequence.
        let lying = Index { sequence: index.sequence + 1, ..serde_json::from_str(INDEX).unwrap() };
        assert!(verify(DB, &lying, PUBLIC_KEY).unwrap_err().contains("sequence"));
        assert!(verify(&DB[..10], &index, PUBLIC_KEY).unwrap_err().contains("size"));
    }
}
