//! Typed repositories on top of [`Db`]. Sensitive values (tokens) never pass
//! through this module: they live in the OS credential store.

use rusqlite::{params, OptionalExtension, Row};

use super::{now_ts, Db};
use crate::errors::{BloomError, BloomResult};
use crate::models::{EngineKind, EngineOptions, JavaRuntime, LaunchRecord, LoaderKind, Settings};

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

impl Db {
    /// Loads settings, filling missing keys with defaults. Unknown keys from
    /// newer versions are ignored, so downgrades do not lose data.
    pub fn load_settings(&self) -> BloomResult<Settings> {
        let conn = self.conn();
        let mut stmt = conn.prepare("SELECT key, value FROM settings")?;
        let mut map = serde_json::Map::new();
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
        for row in rows {
            let (key, value) = row?;
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&value) {
                map.insert(key, v);
            }
        }
        drop(stmt);
        drop(conn);
        let defaults = serde_json::to_value(Settings::default())?;
        let mut merged = defaults.as_object().cloned().unwrap_or_default();
        for (k, v) in map {
            if merged.contains_key(&k) {
                merged.insert(k, v);
            }
        }
        // A single malformed value must not reset every setting: fall back
        // per key when the full object fails to deserialize.
        match serde_json::from_value::<Settings>(serde_json::Value::Object(merged.clone())) {
            Ok(s) => Ok(s),
            Err(_) => {
                let defaults = defaults.as_object().cloned().unwrap_or_default();
                let mut repaired = defaults.clone();
                for (k, v) in merged {
                    let mut probe = repaired.clone();
                    probe.insert(k.clone(), v.clone());
                    if serde_json::from_value::<Settings>(serde_json::Value::Object(probe)).is_ok() {
                        repaired.insert(k, v);
                    }
                }
                Ok(serde_json::from_value(serde_json::Value::Object(repaired))?)
            }
        }
    }

    pub fn save_settings(&self, settings: &Settings) -> BloomResult<()> {
        let value = serde_json::to_value(settings)?;
        let obj = value.as_object().ok_or_else(|| BloomError::Internal("settings not an object".into()))?;
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        for (k, v) in obj {
            tx.execute(
                "INSERT INTO settings (key, value) VALUES (?1, ?2)
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                params![k, v.to_string()],
            )?;
        }
        tx.commit()?;
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Accounts
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct AccountRow {
    pub uuid: String,
    pub username: String,
    pub skin_url: Option<String>,
    pub skin_variant: Option<String>,
    pub active: bool,
    pub mc_expires_at: Option<i64>,
    pub added_at: i64,
    pub last_used_at: Option<i64>,
}

fn account_from_row(r: &Row) -> rusqlite::Result<AccountRow> {
    Ok(AccountRow {
        uuid: r.get(0)?,
        username: r.get(1)?,
        skin_url: r.get(2)?,
        skin_variant: r.get(3)?,
        active: r.get::<_, i64>(4)? != 0,
        mc_expires_at: r.get(5)?,
        added_at: r.get(6)?,
        last_used_at: r.get(7)?,
    })
}

const ACCOUNT_COLS: &str =
    "uuid, username, skin_url, skin_variant, active, mc_expires_at, added_at, last_used_at";

impl Db {
    pub fn list_accounts(&self) -> BloomResult<Vec<AccountRow>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(&format!("SELECT {ACCOUNT_COLS} FROM accounts ORDER BY added_at"))?;
        let rows = stmt.query_map([], account_from_row)?.collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn get_account(&self, uuid: &str) -> BloomResult<Option<AccountRow>> {
        Ok(self
            .conn()
            .query_row(&format!("SELECT {ACCOUNT_COLS} FROM accounts WHERE uuid = ?1"), params![uuid], account_from_row)
            .optional()?)
    }

    pub fn active_account(&self) -> BloomResult<Option<AccountRow>> {
        Ok(self
            .conn()
            .query_row(&format!("SELECT {ACCOUNT_COLS} FROM accounts WHERE active = 1 LIMIT 1"), [], account_from_row)
            .optional()?)
    }

    /// Inserts or updates an account and makes it the active one.
    pub fn upsert_account(
        &self,
        uuid: &str,
        username: &str,
        skin_url: Option<&str>,
        skin_variant: Option<&str>,
        mc_expires_at: i64,
    ) -> BloomResult<()> {
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        tx.execute("UPDATE accounts SET active = 0", [])?;
        tx.execute(
            "INSERT INTO accounts (uuid, username, skin_url, skin_variant, active, mc_expires_at, added_at, last_used_at)
             VALUES (?1, ?2, ?3, ?4, 1, ?5, ?6, ?6)
             ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, skin_url = excluded.skin_url,
               skin_variant = excluded.skin_variant, active = 1, mc_expires_at = excluded.mc_expires_at,
               last_used_at = excluded.last_used_at",
            params![uuid, username, skin_url, skin_variant, mc_expires_at, now_ts()],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn update_account_session(
        &self,
        uuid: &str,
        username: &str,
        skin_url: Option<&str>,
        skin_variant: Option<&str>,
        mc_expires_at: i64,
    ) -> BloomResult<()> {
        self.conn().execute(
            "UPDATE accounts SET username = ?2, skin_url = ?3, skin_variant = ?4, mc_expires_at = ?5 WHERE uuid = ?1",
            params![uuid, username, skin_url, skin_variant, mc_expires_at],
        )?;
        Ok(())
    }

    pub fn mark_account_expired(&self, uuid: &str) -> BloomResult<()> {
        self.conn()
            .execute("UPDATE accounts SET mc_expires_at = NULL WHERE uuid = ?1", params![uuid])?;
        Ok(())
    }

    pub fn touch_account(&self, uuid: &str) -> BloomResult<()> {
        self.conn()
            .execute("UPDATE accounts SET last_used_at = ?2 WHERE uuid = ?1", params![uuid, now_ts()])?;
        Ok(())
    }

    pub fn set_active_account(&self, uuid: &str) -> BloomResult<()> {
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        let exists: bool = tx
            .query_row("SELECT 1 FROM accounts WHERE uuid = ?1", params![uuid], |_| Ok(true))
            .optional()?
            .unwrap_or(false);
        if !exists {
            return Err(BloomError::NotFound("account not found".into()));
        }
        tx.execute("UPDATE accounts SET active = (uuid = ?1)", params![uuid])?;
        tx.commit()?;
        Ok(())
    }

    /// Removes an account; if it was active, the most recently used remaining
    /// account becomes active.
    pub fn delete_account(&self, uuid: &str) -> BloomResult<()> {
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        tx.execute("DELETE FROM accounts WHERE uuid = ?1", params![uuid])?;
        let has_active: bool = tx
            .query_row("SELECT 1 FROM accounts WHERE active = 1", [], |_| Ok(true))
            .optional()?
            .unwrap_or(false);
        if !has_active {
            tx.execute(
                "UPDATE accounts SET active = 1 WHERE uuid = (
                    SELECT uuid FROM accounts ORDER BY COALESCE(last_used_at, added_at) DESC LIMIT 1)",
                [],
            )?;
        }
        tx.commit()?;
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Instances
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct InstanceRow {
    pub id: String,
    pub name: String,
    pub version_id: String,
    pub loader: LoaderKind,
    pub loader_version: Option<String>,
    pub engine: EngineKind,
    pub engine_options: EngineOptions,
    pub java_path: Option<String>,
    pub min_ram_mb: Option<u32>,
    pub max_ram_mb: Option<u32>,
    pub resolution_width: Option<u32>,
    pub resolution_height: Option<u32>,
    pub fullscreen: Option<bool>,
    pub jvm_args: Option<String>,
    pub icon: String,
    pub created_at: i64,
    pub last_played_at: Option<i64>,
    pub total_play_seconds: i64,
}

const INSTANCE_COLS: &str = "id, name, version_id, loader, loader_version, java_path, min_ram_mb, max_ram_mb,
    resolution_width, resolution_height, fullscreen, jvm_args, icon, created_at, last_played_at, total_play_seconds, engine, engine_options";

fn instance_from_row(r: &Row) -> rusqlite::Result<InstanceRow> {
    let loader: String = r.get(3)?;
    Ok(InstanceRow {
        id: r.get(0)?,
        name: r.get(1)?,
        version_id: r.get(2)?,
        loader: LoaderKind::parse(&loader).unwrap_or(LoaderKind::Vanilla),
        loader_version: r.get(4)?,
        java_path: r.get(5)?,
        min_ram_mb: r.get(6)?,
        max_ram_mb: r.get(7)?,
        resolution_width: r.get(8)?,
        resolution_height: r.get(9)?,
        fullscreen: r.get::<_, Option<i64>>(10)?.map(|v| v != 0),
        jvm_args: r.get(11)?,
        icon: r.get(12)?,
        created_at: r.get(13)?,
        last_played_at: r.get(14)?,
        total_play_seconds: r.get(15)?,
        engine: EngineKind::parse(&r.get::<_, String>(16)?).unwrap_or_default(),
        // Unreadable or missing options fall back to the defaults.
        engine_options: r.get::<_, Option<String>>(17)?.and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default(),
    })
}

impl Db {
    pub fn list_instances(&self) -> BloomResult<Vec<InstanceRow>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(&format!(
            "SELECT {INSTANCE_COLS} FROM instances ORDER BY COALESCE(last_played_at, created_at) DESC"
        ))?;
        let rows = stmt.query_map([], instance_from_row)?.collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn get_instance(&self, id: &str) -> BloomResult<Option<InstanceRow>> {
        Ok(self
            .conn()
            .query_row(&format!("SELECT {INSTANCE_COLS} FROM instances WHERE id = ?1"), params![id], instance_from_row)
            .optional()?)
    }

    pub fn insert_instance(&self, i: &InstanceRow) -> BloomResult<()> {
        self.conn().execute(
            &format!("INSERT INTO instances ({INSTANCE_COLS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)"),
            params![
                i.id, i.name, i.version_id, i.loader.as_str(), i.loader_version, i.java_path, i.min_ram_mb,
                i.max_ram_mb, i.resolution_width, i.resolution_height, i.fullscreen.map(i64::from), i.jvm_args,
                i.icon, i.created_at, i.last_played_at, i.total_play_seconds, i.engine.as_str(),
                serde_json::to_string(&i.engine_options)?
            ],
        )?;
        Ok(())
    }

    pub fn update_instance(&self, i: &InstanceRow) -> BloomResult<()> {
        let n = self.conn().execute(
            "UPDATE instances SET name = ?2, version_id = ?3, loader = ?4, loader_version = ?5, java_path = ?6,
               min_ram_mb = ?7, max_ram_mb = ?8, resolution_width = ?9, resolution_height = ?10, fullscreen = ?11,
               jvm_args = ?12, icon = ?13, engine = ?14, engine_options = ?15 WHERE id = ?1",
            params![
                i.id, i.name, i.version_id, i.loader.as_str(), i.loader_version, i.java_path, i.min_ram_mb,
                i.max_ram_mb, i.resolution_width, i.resolution_height, i.fullscreen.map(i64::from), i.jvm_args, i.icon,
                i.engine.as_str(), serde_json::to_string(&i.engine_options)?
            ],
        )?;
        if n == 0 {
            return Err(BloomError::NotFound("instance not found".into()));
        }
        Ok(())
    }

    /// Deletes an instance together with its launch history.
    pub fn delete_instance(&self, id: &str) -> BloomResult<()> {
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        tx.execute("DELETE FROM launch_history WHERE instance_id = ?1", params![id])?;
        tx.execute("DELETE FROM instances WHERE id = ?1", params![id])?;
        tx.commit()?;
        Ok(())
    }

    pub fn record_instance_played(&self, id: &str, started_at: i64, seconds: i64) -> BloomResult<()> {
        self.conn().execute(
            "UPDATE instances SET last_played_at = ?2, total_play_seconds = total_play_seconds + ?3 WHERE id = ?1",
            params![id, started_at, seconds.max(0)],
        )?;
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Installed versions
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct InstalledVersionRow {
    pub version_id: String,
    pub release_type: Option<String>,
    pub release_time: Option<String>,
    pub java_component: Option<String>,
    pub java_major: Option<u32>,
    pub installed_at: i64,
}

impl Db {
    pub fn list_installed_versions(&self) -> BloomResult<Vec<InstalledVersionRow>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT version_id, release_type, release_time, java_component, java_major, installed_at FROM installed_versions",
        )?;
        let rows = stmt
            .query_map([], |r| {
                Ok(InstalledVersionRow {
                    version_id: r.get(0)?,
                    release_type: r.get(1)?,
                    release_time: r.get(2)?,
                    java_component: r.get(3)?,
                    java_major: r.get(4)?,
                    installed_at: r.get(5)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn mark_version_installed(&self, row: &InstalledVersionRow) -> BloomResult<()> {
        self.conn().execute(
            "INSERT INTO installed_versions (version_id, release_type, release_time, java_component, java_major, installed_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(version_id) DO UPDATE SET release_type = excluded.release_type, release_time = excluded.release_time,
               java_component = excluded.java_component, java_major = excluded.java_major, installed_at = excluded.installed_at",
            params![row.version_id, row.release_type, row.release_time, row.java_component, row.java_major, row.installed_at],
        )?;
        Ok(())
    }

    pub fn remove_installed_version(&self, id: &str) -> BloomResult<()> {
        self.conn().execute("DELETE FROM installed_versions WHERE version_id = ?1", params![id])?;
        Ok(())
    }

    pub fn instances_using_version(&self, id: &str) -> BloomResult<Vec<String>> {
        let conn = self.conn();
        let mut stmt = conn.prepare("SELECT name FROM instances WHERE version_id = ?1")?;
        let rows = stmt.query_map(params![id], |r| r.get(0))?.collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }
}

// ---------------------------------------------------------------------------
// Java runtimes
// ---------------------------------------------------------------------------

impl Db {
    pub fn list_java_runtimes(&self) -> BloomResult<Vec<JavaRuntime>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT path, version, major, vendor, arch, source, component FROM java_runtimes ORDER BY major DESC, path",
        )?;
        let rows = stmt
            .query_map([], |r| {
                Ok(JavaRuntime {
                    path: r.get(0)?,
                    version: r.get(1)?,
                    major: r.get(2)?,
                    vendor: r.get(3)?,
                    arch: r.get(4)?,
                    source: r.get(5)?,
                    component: r.get(6)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn upsert_java_runtime(&self, j: &JavaRuntime) -> BloomResult<()> {
        // Never downgrade a "managed"/"custom" runtime to "system" on rescan.
        self.conn().execute(
            "INSERT INTO java_runtimes (path, version, major, vendor, arch, source, component, verified_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
             ON CONFLICT(path) DO UPDATE SET version = excluded.version, major = excluded.major, vendor = excluded.vendor,
               arch = excluded.arch, verified_at = excluded.verified_at,
               source = CASE WHEN java_runtimes.source = 'system' THEN excluded.source ELSE java_runtimes.source END,
               component = COALESCE(excluded.component, java_runtimes.component)",
            params![j.path, j.version, j.major, j.vendor, j.arch, j.source, j.component, now_ts()],
        )?;
        Ok(())
    }

    pub fn remove_java_runtime(&self, path: &str) -> BloomResult<()> {
        self.conn().execute("DELETE FROM java_runtimes WHERE path = ?1", params![path])?;
        Ok(())
    }

    pub fn remove_system_java_runtimes(&self) -> BloomResult<()> {
        self.conn().execute("DELETE FROM java_runtimes WHERE source = 'system'", [])?;
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Launch history
// ---------------------------------------------------------------------------

impl Db {
    pub fn insert_launch(
        &self,
        instance_id: &str,
        version_id: &str,
        account_uuid: Option<&str>,
        account_name: Option<&str>,
        started_at: i64,
        log_file: Option<&str>,
    ) -> BloomResult<i64> {
        let conn = self.conn();
        conn.execute(
            "INSERT INTO launch_history (instance_id, version_id, account_uuid, account_name, started_at, log_file)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![instance_id, version_id, account_uuid, account_name, started_at, log_file],
        )?;
        Ok(conn.last_insert_rowid())
    }

    pub fn finish_launch(&self, id: i64, ended_at: i64, exit_code: Option<i32>, crashed: bool) -> BloomResult<()> {
        self.conn().execute(
            "UPDATE launch_history SET ended_at = ?2, exit_code = ?3, crashed = ?4 WHERE id = ?1",
            params![id, ended_at, exit_code, crashed as i64],
        )?;
        Ok(())
    }

    /// Closes history entries left open by a launcher crash or forced quit.
    pub fn close_dangling_launches(&self) -> BloomResult<()> {
        self.conn()
            .execute("UPDATE launch_history SET ended_at = started_at WHERE ended_at IS NULL", [])?;
        Ok(())
    }

    pub fn recent_launches(&self, limit: u32) -> BloomResult<Vec<LaunchRecord>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT h.id, h.instance_id, i.name, h.version_id, h.account_name, h.started_at, h.ended_at, h.exit_code,
                    h.crashed, h.log_file
             FROM launch_history h LEFT JOIN instances i ON i.id = h.instance_id
             ORDER BY h.started_at DESC LIMIT ?1",
        )?;
        let rows = stmt
            .query_map(params![limit], |r| {
                Ok(LaunchRecord {
                    id: r.get(0)?,
                    instance_id: r.get(1)?,
                    instance_name: r.get(2)?,
                    version_id: r.get(3)?,
                    account_name: r.get(4)?,
                    started_at: r.get(5)?,
                    ended_at: r.get(6)?,
                    exit_code: r.get(7)?,
                    crashed: r.get::<_, i64>(8)? != 0,
                    log_file: r.get(9)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn get_launch(&self, id: i64) -> BloomResult<Option<LaunchRecord>> {
        Ok(self.recent_launches(500)?.into_iter().find(|l| l.id == id))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_roundtrip_and_defaults() {
        let db = Db::open_in_memory().unwrap();
        let mut s = db.load_settings().unwrap();
        assert_eq!(s, Settings::default());
        s.max_ram_mb = 6144;
        s.language = "it".into();
        db.save_settings(&s).unwrap();
        assert_eq!(db.load_settings().unwrap(), s);
    }

    #[test]
    fn malformed_setting_falls_back_to_default() {
        let db = Db::open_in_memory().unwrap();
        db.conn()
            .execute("INSERT INTO settings (key, value) VALUES ('maxRamMb', '\"lots\"'), ('language', '\"it\"')", [])
            .unwrap();
        let s = db.load_settings().unwrap();
        assert_eq!(s.max_ram_mb, Settings::default().max_ram_mb);
        assert_eq!(s.language, "it");
    }

    #[test]
    fn active_account_switches() {
        let db = Db::open_in_memory().unwrap();
        db.upsert_account("a", "Alice", None, None, 1).unwrap();
        db.upsert_account("b", "Bob", None, None, 1).unwrap();
        assert_eq!(db.active_account().unwrap().unwrap().uuid, "b");
        db.set_active_account("a").unwrap();
        assert_eq!(db.active_account().unwrap().unwrap().uuid, "a");
        db.delete_account("a").unwrap();
        assert_eq!(db.active_account().unwrap().unwrap().uuid, "b");
    }
}
