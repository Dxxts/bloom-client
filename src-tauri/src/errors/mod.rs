//! Centralized error handling.
//!
//! Every fallible operation in Bloom returns [`BloomResult`]. Errors are
//! serialized to the frontend as `{ code, message }` so the UI can react to
//! specific failure classes (e.g. show a "sign in again" prompt on
//! `auth_expired`) without parsing strings.

use serde::{Serialize, Serializer};

pub type BloomResult<T> = Result<T, BloomError>;

#[derive(Debug, thiserror::Error)]
pub enum BloomError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Database error: {0}")]
    Database(#[from] rusqlite::Error),

    #[error("Network error: {0}")]
    Network(String),

    #[error("Invalid data: {0}")]
    Parse(String),

    #[error("Integrity check failed for {path}: expected {expected}, got {actual}")]
    Integrity {
        path: String,
        expected: String,
        actual: String,
    },

    #[error("{0}")]
    NotFound(String),

    #[error("{0}")]
    InvalidInput(String),

    #[error("Authentication failed: {0}")]
    Auth(String),

    #[error("Your session has expired. Please sign in again.")]
    AuthExpired,

    #[error("Sign in with a Microsoft account to play.")]
    NoAccount,

    #[error("This Microsoft account does not own Minecraft: Java Edition.")]
    NoGameOwnership,

    #[error("Microsoft authentication is not configured: {0}")]
    AuthNotConfigured(String),

    #[error(
        "Microsoft sign-in worked, but Mojang hasn't approved this app for the Minecraft API yet. \
         Sign-in will work once the approval request is accepted (see docs/MICROSOFT_AUTH.md)."
    )]
    MinecraftApiNotApproved,

    #[error("Secure credential storage error: {0}")]
    SecretStore(String),

    #[error("Java error: {0}")]
    Java(String),

    #[error("Launch failed: {0}")]
    Launch(String),

    #[error("{0}")]
    AlreadyRunning(String),

    #[error("Operation cancelled")]
    Cancelled,

    #[error("{0}")]
    Unsupported(String),

    #[error("Updater error: {0}")]
    Updater(String),

    #[error("{0}")]
    Internal(String),
}

impl BloomError {
    /// Stable machine readable code used by the frontend.
    pub fn code(&self) -> &'static str {
        match self {
            BloomError::Io(_) => "io",
            BloomError::Database(_) => "database",
            BloomError::Network(_) => "network",
            BloomError::Parse(_) => "parse",
            BloomError::Integrity { .. } => "integrity",
            BloomError::NotFound(_) => "not_found",
            BloomError::InvalidInput(_) => "invalid_input",
            BloomError::Auth(_) => "auth",
            BloomError::AuthExpired => "auth_expired",
            BloomError::NoAccount => "no_account",
            BloomError::NoGameOwnership => "no_ownership",
            BloomError::AuthNotConfigured(_) => "auth_not_configured",
            BloomError::MinecraftApiNotApproved => "minecraft_api_not_approved",
            BloomError::SecretStore(_) => "secret_store",
            BloomError::Java(_) => "java",
            BloomError::Launch(_) => "launch",
            BloomError::AlreadyRunning(_) => "already_running",
            BloomError::Cancelled => "cancelled",
            BloomError::Unsupported(_) => "unsupported",
            BloomError::Updater(_) => "updater",
            BloomError::Internal(_) => "internal",
        }
    }
}

impl Serialize for BloomError {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut s = serializer.serialize_struct("BloomError", 2)?;
        s.serialize_field("code", self.code())?;
        s.serialize_field("message", &self.to_string())?;
        s.end()
    }
}

impl From<reqwest::Error> for BloomError {
    fn from(e: reqwest::Error) -> Self {
        if e.is_timeout() {
            BloomError::Network("the request timed out".into())
        } else if e.is_connect() {
            BloomError::Network("could not connect to the server, check your internet connection".into())
        } else if let Some(status) = e.status() {
            BloomError::Network(format!("server responded with HTTP {status}"))
        } else {
            BloomError::Network(e.without_url().to_string())
        }
    }
}

impl From<serde_json::Error> for BloomError {
    fn from(e: serde_json::Error) -> Self {
        BloomError::Parse(e.to_string())
    }
}

impl From<zip::result::ZipError> for BloomError {
    fn from(e: zip::result::ZipError) -> Self {
        BloomError::Parse(format!("archive error: {e}"))
    }
}

impl From<tauri::Error> for BloomError {
    fn from(e: tauri::Error) -> Self {
        BloomError::Internal(e.to_string())
    }
}

impl From<tokio::task::JoinError> for BloomError {
    fn from(e: tokio::task::JoinError) -> Self {
        BloomError::Internal(format!("background task failed: {e}"))
    }
}
