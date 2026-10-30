//! Secure storage for authentication secrets.
//!
//! Secrets never touch SQLite. The [`SecretStore`] trait abstracts the
//! platform credential store; [`KeyringStore`] implements it on top of the
//! `keyring` crate, which maps to Windows Credential Manager, macOS Keychain
//! and the Secret Service API on Linux (backend chosen per target in
//! `Cargo.toml`).
//!
//! Windows limits a credential blob to 2560 bytes, while Microsoft refresh
//! tokens plus Minecraft access tokens can exceed that, so values are split
//! into chunks stored as separate credentials with a small header entry.

use crate::errors::{BloomError, BloomResult};

pub trait SecretStore: Send + Sync {
    fn set(&self, key: &str, value: &str) -> BloomResult<()>;
    fn get(&self, key: &str) -> BloomResult<Option<String>>;
    fn delete(&self, key: &str) -> BloomResult<()>;
}

pub struct KeyringStore {
    service: String,
}

const CHUNK_SIZE: usize = 1800;
const HEADER_PREFIX: &str = "bloom-chunks:";

impl KeyringStore {
    pub fn new(service: impl Into<String>) -> Self {
        Self { service: service.into() }
    }

    fn entry(&self, user: &str) -> BloomResult<keyring::Entry> {
        keyring::Entry::new(&self.service, user).map_err(map_err)
    }

    fn chunk_user(key: &str, idx: usize) -> String {
        format!("{key}#{idx}")
    }

    fn read_header(&self, key: &str) -> BloomResult<Option<usize>> {
        match self.entry(key)?.get_secret() {
            Ok(bytes) => {
                let text = String::from_utf8(bytes).map_err(|_| BloomError::SecretStore("corrupted header".into()))?;
                let n = text
                    .strip_prefix(HEADER_PREFIX)
                    .and_then(|n| n.parse().ok())
                    .ok_or_else(|| BloomError::SecretStore("corrupted header".into()))?;
                Ok(Some(n))
            }
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(map_err(e)),
        }
    }

    fn delete_chunks(&self, key: &str, from: usize, to: usize) {
        for i in from..to {
            if let Ok(e) = self.entry(&Self::chunk_user(key, i)) {
                let _ = e.delete_credential();
            }
        }
    }
}

fn map_err(e: keyring::Error) -> BloomError {
    BloomError::SecretStore(e.to_string())
}

impl SecretStore for KeyringStore {
    fn set(&self, key: &str, value: &str) -> BloomResult<()> {
        let previous = self.read_header(key).ok().flatten().unwrap_or(0);
        let bytes = value.as_bytes();
        let chunks: Vec<&[u8]> = if bytes.is_empty() { vec![&[][..]] } else { bytes.chunks(CHUNK_SIZE).collect() };
        for (i, chunk) in chunks.iter().enumerate() {
            self.entry(&Self::chunk_user(key, i))?.set_secret(chunk).map_err(map_err)?;
        }
        self.entry(key)?
            .set_secret(format!("{HEADER_PREFIX}{}", chunks.len()).as_bytes())
            .map_err(map_err)?;
        if previous > chunks.len() {
            self.delete_chunks(key, chunks.len(), previous);
        }
        Ok(())
    }

    fn get(&self, key: &str) -> BloomResult<Option<String>> {
        let Some(n) = self.read_header(key)? else { return Ok(None) };
        let mut out = Vec::new();
        for i in 0..n {
            match self.entry(&Self::chunk_user(key, i))?.get_secret() {
                Ok(b) => out.extend(b),
                Err(keyring::Error::NoEntry) => {
                    return Err(BloomError::SecretStore("stored credential is incomplete".into()))
                }
                Err(e) => return Err(map_err(e)),
            }
        }
        String::from_utf8(out)
            .map(Some)
            .map_err(|_| BloomError::SecretStore("stored credential is corrupted".into()))
    }

    fn delete(&self, key: &str) -> BloomResult<()> {
        let n = self.read_header(key).ok().flatten().unwrap_or(0);
        self.delete_chunks(key, 0, n);
        match self.entry(key)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(map_err(e)),
        }
    }
}

/// In-memory store used by tests.
#[cfg(test)]
#[derive(Default)]
pub struct MemoryStore(std::sync::Mutex<std::collections::HashMap<String, String>>);

#[cfg(test)]
impl SecretStore for MemoryStore {
    fn set(&self, key: &str, value: &str) -> BloomResult<()> {
        self.0.lock().unwrap().insert(key.into(), value.into());
        Ok(())
    }
    fn get(&self, key: &str) -> BloomResult<Option<String>> {
        Ok(self.0.lock().unwrap().get(key).cloned())
    }
    fn delete(&self, key: &str) -> BloomResult<()> {
        self.0.lock().unwrap().remove(key);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Exercises the real OS credential store with a large value that needs
    /// several chunks. Run with `cargo test -- --ignored` on a desktop session.
    #[test]
    #[ignore]
    fn keyring_chunked_roundtrip() {
        let store = KeyringStore::new("com.bloomclient.launcher.test");
        let big: String = "x".repeat(5000) + "end";
        store.set("roundtrip", &big).unwrap();
        assert_eq!(store.get("roundtrip").unwrap().as_deref(), Some(big.as_str()));
        store.set("roundtrip", "short").unwrap();
        assert_eq!(store.get("roundtrip").unwrap().as_deref(), Some("short"));
        store.delete("roundtrip").unwrap();
        assert_eq!(store.get("roundtrip").unwrap(), None);
    }

    #[test]
    fn memory_store_roundtrip() {
        let s = MemoryStore::default();
        s.set("k", "v").unwrap();
        assert_eq!(s.get("k").unwrap().as_deref(), Some("v"));
        s.delete("k").unwrap();
        assert_eq!(s.get("k").unwrap(), None);
    }
}
