//! Ordered schema migrations. Each entry upgrades the schema by exactly one
//! version (tracked with `PRAGMA user_version`). Never edit a migration that
//! has shipped: append a new one instead.

pub const MIGRATIONS: &[&str] = &[
    // v1: initial schema
    r#"
    CREATE TABLE settings (
        key   TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );

    CREATE TABLE accounts (
        uuid          TEXT PRIMARY KEY,
        username      TEXT NOT NULL,
        skin_url      TEXT,
        skin_variant  TEXT,
        active        INTEGER NOT NULL DEFAULT 0,
        mc_expires_at INTEGER,
        added_at      INTEGER NOT NULL,
        last_used_at  INTEGER
    );

    CREATE TABLE instances (
        id                 TEXT PRIMARY KEY,
        name               TEXT NOT NULL,
        version_id         TEXT NOT NULL,
        loader             TEXT NOT NULL DEFAULT 'vanilla',
        loader_version     TEXT,
        java_path          TEXT,
        min_ram_mb         INTEGER,
        max_ram_mb         INTEGER,
        resolution_width   INTEGER,
        resolution_height  INTEGER,
        fullscreen         INTEGER,
        jvm_args           TEXT,
        icon               TEXT NOT NULL DEFAULT 'sprout',
        created_at         INTEGER NOT NULL,
        last_played_at     INTEGER,
        total_play_seconds INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE installed_versions (
        version_id     TEXT PRIMARY KEY,
        release_type   TEXT,
        release_time   TEXT,
        java_component TEXT,
        java_major     INTEGER,
        installed_at   INTEGER NOT NULL
    );

    CREATE TABLE java_runtimes (
        path        TEXT PRIMARY KEY,
        version     TEXT NOT NULL,
        major       INTEGER NOT NULL,
        vendor      TEXT NOT NULL DEFAULT '',
        arch        TEXT NOT NULL DEFAULT '',
        source      TEXT NOT NULL,
        component   TEXT,
        verified_at INTEGER NOT NULL
    );

    CREATE TABLE launch_history (
        id           INTEGER PRIMARY KEY AUTOINCREMENT,
        instance_id  TEXT NOT NULL,
        version_id   TEXT NOT NULL,
        account_uuid TEXT,
        account_name TEXT,
        started_at   INTEGER NOT NULL,
        ended_at     INTEGER,
        exit_code    INTEGER,
        crashed      INTEGER NOT NULL DEFAULT 0,
        log_file     TEXT
    );
    CREATE INDEX idx_launch_history_started ON launch_history (started_at DESC);

    CREATE TABLE file_cache (
        path  TEXT PRIMARY KEY,
        size  INTEGER NOT NULL,
        mtime INTEGER NOT NULL,
        sha1  TEXT NOT NULL
    );

    CREATE TABLE http_cache (
        key        TEXT PRIMARY KEY,
        etag       TEXT,
        fetched_at INTEGER NOT NULL
    );
    "#,
    // v2: world renderer per instance (Bloom Native World Engine)
    r#"
    ALTER TABLE instances ADD COLUMN engine TEXT NOT NULL DEFAULT 'java';
    "#,
];
