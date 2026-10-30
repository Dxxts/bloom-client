//! SQLite persistence layer.
//!
//! A single connection guarded by a mutex is plenty for a desktop launcher:
//! every query is short, and bulk work (file verification cache) runs inside
//! a single transaction. The lock is never held across an `.await`.

mod migrations;
pub mod repos;

use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

use rusqlite::{params, Connection, OptionalExtension};

use crate::errors::{BloomError, BloomResult};

pub struct Db {
    conn: Mutex<Connection>,
    path: PathBuf,
}

impl Db {
    /// Opens (or creates) the database and applies pending migrations.
    /// Before upgrading an existing database a backup copy is written next to
    /// it, so user configuration is never lost because of a failed migration.
    pub fn open(path: &Path) -> BloomResult<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        let db = Self { conn: Mutex::new(conn), path: path.to_path_buf() };
        db.migrate()?;
        Ok(db)
    }

    /// In-memory database for tests.
    #[cfg(test)]
    pub fn open_in_memory() -> BloomResult<Self> {
        let conn = Connection::open_in_memory()?;
        let db = Self { conn: Mutex::new(conn), path: PathBuf::from(":memory:") };
        db.migrate()?;
        Ok(db)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn conn(&self) -> MutexGuard<'_, Connection> {
        // A poisoned lock only means another thread panicked while holding
        // it; SQLite itself keeps the data consistent, so keep going.
        self.conn.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn schema_version(&self) -> BloomResult<u32> {
        Ok(self.conn().pragma_query_value(None, "user_version", |r| r.get(0))?)
    }

    fn migrate(&self) -> BloomResult<()> {
        let mut conn = self.conn();
        let current: u32 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        let target = migrations::MIGRATIONS.len() as u32;
        if current > target {
            return Err(BloomError::Internal(format!(
                "the database was created by a newer Bloom version (schema {current}, supported {target})"
            )));
        }
        if current == target {
            return Ok(());
        }
        if current > 0 && self.path.exists() {
            let backup = self.path.with_extension(format!("db.bak-v{current}"));
            conn.execute("VACUUM INTO ?1", params![backup.to_string_lossy()])
                .map_err(|e| BloomError::Internal(format!("database backup failed: {e}")))?;
        }
        for (idx, sql) in migrations::MIGRATIONS.iter().enumerate().skip(current as usize) {
            let version = idx as u32 + 1;
            let tx = conn.transaction()?;
            tx.execute_batch(sql)?;
            tx.pragma_update(None, "user_version", version)?;
            tx.commit()?;
            tracing::info!(version, "applied database migration");
        }
        Ok(())
    }

    // -- http cache metadata ------------------------------------------------

    pub fn http_cache_get(&self, key: &str) -> BloomResult<Option<(Option<String>, i64)>> {
        Ok(self
            .conn()
            .query_row("SELECT etag, fetched_at FROM http_cache WHERE key = ?1", params![key], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .optional()?)
    }

    pub fn http_cache_put(&self, key: &str, etag: Option<&str>, fetched_at: i64) -> BloomResult<()> {
        self.conn().execute(
            "INSERT INTO http_cache (key, etag, fetched_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(key) DO UPDATE SET etag = excluded.etag, fetched_at = excluded.fetched_at",
            params![key, etag, fetched_at],
        )?;
        Ok(())
    }

    // -- verified file cache -------------------------------------------------

    /// Returns the cached SHA1 for `path` if size and mtime still match.
    pub fn file_cache_lookup(&self, path: &str, size: u64, mtime: i64) -> BloomResult<Option<String>> {
        Ok(self
            .conn()
            .query_row(
                "SELECT sha1 FROM file_cache WHERE path = ?1 AND size = ?2 AND mtime = ?3",
                params![path, size as i64, mtime],
                |r| r.get(0),
            )
            .optional()?)
    }

    pub fn file_cache_put_many(&self, entries: &[(String, u64, i64, String)]) -> BloomResult<()> {
        if entries.is_empty() {
            return Ok(());
        }
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        {
            let mut stmt = tx.prepare_cached(
                "INSERT INTO file_cache (path, size, mtime, sha1) VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime, sha1 = excluded.sha1",
            )?;
            for (path, size, mtime, sha1) in entries {
                stmt.execute(params![path, *size as i64, mtime, sha1])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    pub fn file_cache_remove_prefix(&self, prefix: &str) -> BloomResult<usize> {
        let pattern = format!("{}%", prefix.replace('%', "\\%").replace('_', "\\_"));
        Ok(self
            .conn()
            .execute("DELETE FROM file_cache WHERE path LIKE ?1 ESCAPE '\\'", params![pattern])?)
    }

    pub fn file_cache_clear(&self) -> BloomResult<usize> {
        Ok(self.conn().execute("DELETE FROM file_cache", [])?)
    }
}

pub fn now_ts() -> i64 {
    chrono::Utc::now().timestamp()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrations_apply_and_are_idempotent() {
        let db = Db::open_in_memory().unwrap();
        assert_eq!(db.schema_version().unwrap(), migrations::MIGRATIONS.len() as u32);
        db.migrate().unwrap();
        assert_eq!(db.schema_version().unwrap(), migrations::MIGRATIONS.len() as u32);
    }

    #[test]
    fn file_cache_roundtrip() {
        let db = Db::open_in_memory().unwrap();
        db.file_cache_put_many(&[("a/b.jar".into(), 10, 100, "abc".into())]).unwrap();
        assert_eq!(db.file_cache_lookup("a/b.jar", 10, 100).unwrap().as_deref(), Some("abc"));
        assert_eq!(db.file_cache_lookup("a/b.jar", 11, 100).unwrap(), None);
        assert_eq!(db.file_cache_remove_prefix("a/").unwrap(), 1);
    }
}
