//! Microsoft account authentication.
//!
//! Flow: Microsoft OAuth (Authorization Code + PKCE, system browser) →
//! Xbox Live → XSTS → Minecraft Services → entitlement check → profile.
//!
//! The Microsoft refresh token and the Minecraft access token are stored
//! only in the OS credential store (see [`secret_store`]); SQLite keeps the
//! public profile (UUID, username, skin URL) and the token expiry time.

pub mod oauth;
pub mod secret_store;
pub mod xbox;

use std::sync::Mutex;

use base64::Engine;
use serde::{Deserialize, Serialize};
use tauri::Emitter;
use tokio_util::sync::CancellationToken;

use crate::database::now_ts;
use crate::errors::{BloomError, BloomResult};
use crate::models::Account;
use crate::services::events;
use crate::services::state::AppState;

/// Refresh the Minecraft token when it expires within this window.
const REFRESH_MARGIN_SECS: i64 = 10 * 60;

#[derive(Serialize, Deserialize)]
struct StoredSession {
    msa_refresh_token: String,
    mc_access_token: String,
    mc_expires_at: i64,
    xuid: Option<String>,
}

/// Credentials handed to the game at launch.
#[derive(Clone)]
pub struct LaunchAuth {
    pub uuid: String,
    pub username: String,
    pub access_token: String,
    pub xuid: String,
}

#[derive(Default)]
pub struct AuthService {
    pending: Mutex<Option<CancellationToken>>,
    refresh_lock: tokio::sync::Mutex<()>,
}

fn secret_key(uuid: &str) -> String {
    format!("account:{uuid}")
}

/// Effective Azure application (client) ID: user override in Settings, else
/// the value baked in at build time through `BLOOM_MS_CLIENT_ID`.
pub fn client_id(state: &AppState) -> BloomResult<String> {
    state
        .settings()
        .microsoft_client_id
        .or_else(|| option_env!("BLOOM_MS_CLIENT_ID").map(String::from))
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| {
            BloomError::AuthNotConfigured(
                "no Azure application client ID is configured. Set BLOOM_MS_CLIENT_ID at build time \
                 or enter one in Settings → Advanced (see docs/MICROSOFT_AUTH.md)."
                    .into(),
            )
        })
}

pub fn is_configured(state: &AppState) -> bool {
    client_id(state).is_ok()
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct AuthProgress {
    step: &'static str,
}

fn emit_step(state: &AppState, step: &'static str) {
    let _ = state.app.emit(events::AUTH_PROGRESS, AuthProgress { step });
}

impl AuthService {
    pub fn cancel_login(&self) {
        if let Some(token) = self.pending.lock().unwrap_or_else(|e| e.into_inner()).take() {
            token.cancel();
        }
    }
}

/// Runs the interactive login. Never reports success unless every step of
/// the chain, including the ownership check, succeeded.
pub async fn login(state: &AppState) -> BloomResult<Account> {
    let client_id = client_id(state)?;
    let cancel = CancellationToken::new();
    {
        let mut pending = state.auth.pending.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(previous) = pending.replace(cancel.clone()) {
            previous.cancel();
        }
    }
    let result = async {
        let pending = oauth::PendingAuthorization::start(&client_id).await?;
        {
            use tauri_plugin_opener::OpenerExt;
            state
                .app
                .opener()
                .open_url(pending.url.clone(), None::<&str>)
                .map_err(|e| BloomError::Auth(format!("could not open the browser: {e}")))?;
        }
        emit_step(state, "browser");
        let (code, redirect_uri, pkce) = pending.wait_for_code(&cancel).await?;
        emit_step(state, "microsoft");
        let client = state.downloader.client();
        let msa = oauth::exchange_code(client, &client_id, &code, &redirect_uri, &pkce.verifier).await?;
        let uuid = complete_login(state, msa).await?;
        emit_step(state, "done");
        account_by_uuid(state, &uuid)
    }
    .await;
    state.auth.pending.lock().unwrap_or_else(|e| e.into_inner()).take();
    result
}

/// Xbox → XSTS → Minecraft → ownership → profile, then persists the account.
async fn complete_login(state: &AppState, msa: oauth::TokenResponse) -> BloomResult<String> {
    let client = state.downloader.client();
    let refresh_token = msa
        .refresh_token
        .clone()
        .ok_or_else(|| BloomError::Auth("Microsoft did not return a refresh token".into()))?;
    emit_step(state, "xbox");
    let xbl = xbox::xbox_live(client, &msa.access_token).await?;
    let xsts = xbox::xsts(client, &xbl).await?;
    emit_step(state, "minecraft");
    let mc = xbox::minecraft_login(client, &xsts).await?;
    if !xbox::owns_game(client, &mc.access_token).await? {
        return Err(BloomError::NoGameOwnership);
    }
    emit_step(state, "profile");
    let profile = xbox::profile(client, &mc.access_token).await?;
    let uuid = profile.uuid();
    let expires_at = now_ts() + mc.expires_in;
    let stored = StoredSession {
        msa_refresh_token: refresh_token,
        xuid: xbox::xuid_from_token(&mc.access_token),
        mc_access_token: mc.access_token,
        mc_expires_at: expires_at,
    };
    state.secrets.set(&secret_key(&uuid), &serde_json::to_string(&stored)?)?;
    let skin = profile.active_skin();
    state.db.upsert_account(
        &uuid,
        &profile.name,
        skin.map(|s| s.url.as_str()),
        skin.and_then(|s| s.variant.as_deref()),
        expires_at,
    )?;
    Ok(uuid)
}

fn load_session(state: &AppState, uuid: &str) -> BloomResult<Option<StoredSession>> {
    match state.secrets.get(&secret_key(uuid))? {
        Some(json) => Ok(serde_json::from_str(&json).ok()),
        None => Ok(None),
    }
}

/// Refreshes the Minecraft session of an account using its stored Microsoft
/// refresh token.
pub async fn refresh_account(state: &AppState, uuid: &str) -> BloomResult<Account> {
    let _guard = state.auth.refresh_lock.lock().await;
    refresh_locked(state, uuid).await?;
    account_by_uuid(state, uuid)
}

async fn refresh_locked(state: &AppState, uuid: &str) -> BloomResult<StoredSession> {
    let client_id = client_id(state)?;
    let session = load_session(state, uuid)?.ok_or(BloomError::AuthExpired)?;
    let msa = match oauth::refresh(state.downloader.client(), &client_id, &session.msa_refresh_token).await {
        Ok(t) => t,
        Err(BloomError::AuthExpired) => {
            state.db.mark_account_expired(uuid)?;
            let _ = state.secrets.delete(&secret_key(uuid));
            return Err(BloomError::AuthExpired);
        }
        Err(e) => return Err(e),
    };
    let new_uuid = complete_login_refresh(state, msa, &session).await?;
    if new_uuid != uuid {
        return Err(BloomError::Auth("the refreshed session belongs to a different profile".into()));
    }
    load_session(state, uuid)?.ok_or(BloomError::AuthExpired)
}

/// Same chain as [`complete_login`] but keeps the account's active flag.
async fn complete_login_refresh(
    state: &AppState,
    msa: oauth::TokenResponse,
    previous: &StoredSession,
) -> BloomResult<String> {
    let client = state.downloader.client();
    let xbl = xbox::xbox_live(client, &msa.access_token).await?;
    let xsts = xbox::xsts(client, &xbl).await?;
    let mc = xbox::minecraft_login(client, &xsts).await?;
    if !xbox::owns_game(client, &mc.access_token).await? {
        return Err(BloomError::NoGameOwnership);
    }
    let profile = xbox::profile(client, &mc.access_token).await?;
    let uuid = profile.uuid();
    let expires_at = now_ts() + mc.expires_in;
    let stored = StoredSession {
        // Microsoft may rotate the refresh token; keep the old one otherwise.
        msa_refresh_token: msa.refresh_token.unwrap_or_else(|| previous.msa_refresh_token.clone()),
        xuid: xbox::xuid_from_token(&mc.access_token),
        mc_access_token: mc.access_token,
        mc_expires_at: expires_at,
    };
    state.secrets.set(&secret_key(&uuid), &serde_json::to_string(&stored)?)?;
    let skin = profile.active_skin();
    state.db.update_account_session(
        &uuid,
        &profile.name,
        skin.map(|s| s.url.as_str()),
        skin.and_then(|s| s.variant.as_deref()),
        expires_at,
    )?;
    Ok(uuid)
}

/// Returns valid launch credentials for the account, refreshing the session
/// when the Minecraft token is expired or about to expire.
/// Offline player for development builds, so the game can be tested before
/// Microsoft sign-in is configured. Singleplayer works; online-mode servers
/// reject it. Release builds never call this (see `launcher::run`).
pub fn offline_dev_session() -> LaunchAuth {
    LaunchAuth {
        // `UUID.nameUUIDFromBytes("OfflinePlayer:BloomDev")`, like vanilla offline mode.
        uuid: "3b43652a0b8c353fad9c056d040d2b5e".into(),
        username: "BloomDev".into(),
        // Unique on purpose: game output is scrubbed of the token, and "0" would
        // erase every zero from the console.
        access_token: "bloom-offline-dev-session".into(),
        xuid: "0".into(),
    }
}

pub async fn launch_session(state: &AppState, uuid: &str) -> BloomResult<LaunchAuth> {
    let _guard = state.auth.refresh_lock.lock().await;
    let account = state.db.get_account(uuid)?.ok_or_else(|| BloomError::NotFound("account not found".into()))?;
    let mut session = load_session(state, uuid)?.ok_or(BloomError::AuthExpired)?;
    if session.mc_expires_at - now_ts() < REFRESH_MARGIN_SECS {
        session = refresh_locked(state, uuid).await?;
    }
    state.db.touch_account(uuid)?;
    let username = state.db.get_account(uuid)?.map(|a| a.username).unwrap_or(account.username);
    Ok(LaunchAuth {
        uuid: uuid.to_string(),
        username,
        access_token: session.mc_access_token,
        xuid: session.xuid.unwrap_or_else(|| "0".into()),
    })
}

/// Background renewal at startup: refreshes accounts whose token is close to
/// expiring, ignoring network errors (the user may be offline).
pub async fn refresh_expiring(state: &AppState) {
    if !is_configured(state) {
        return;
    }
    let Ok(accounts) = state.db.list_accounts() else { return };
    for a in accounts {
        let expiring = a.mc_expires_at.is_none_or(|t| t - now_ts() < 60 * 60);
        if expiring {
            if let Err(e) = refresh_account(state, &a.uuid).await {
                tracing::info!(account = a.username, error = %e, "automatic session renewal skipped");
            }
        }
    }
    let _ = state.app.emit(events::ACCOUNTS_CHANGED, ());
}

pub fn logout(state: &AppState, uuid: &str) -> BloomResult<()> {
    state.secrets.delete(&secret_key(uuid))?;
    state.db.delete_account(uuid)?;
    let skin = state.paths().skins().join(format!("{}.png", uuid.replace('-', "")));
    let _ = std::fs::remove_file(skin);
    Ok(())
}

pub fn list_accounts(state: &AppState) -> BloomResult<Vec<Account>> {
    let now = now_ts();
    state
        .db
        .list_accounts()?
        .into_iter()
        .map(|a| {
            let has_secret = state.secrets.get(&secret_key(&a.uuid)).map(|s| s.is_some()).unwrap_or(false);
            let status = match (has_secret, a.mc_expires_at) {
                (false, _) => "expired",
                (true, Some(t)) if t > now => "valid",
                (true, _) => "refreshable",
            };
            Ok(Account {
                uuid: a.uuid,
                username: a.username,
                skin_url: a.skin_url,
                skin_variant: a.skin_variant,
                active: a.active,
                status: status.into(),
                minecraft_token_expires_at: a.mc_expires_at,
                added_at: a.added_at,
                last_used_at: a.last_used_at,
            })
        })
        .collect()
}

fn account_by_uuid(state: &AppState, uuid: &str) -> BloomResult<Account> {
    list_accounts(state)?
        .into_iter()
        .find(|a| a.uuid == uuid)
        .ok_or_else(|| BloomError::NotFound("account not found".into()))
}

/// Returns the account skin as a `data:` URL, downloading and caching it on
/// first use. Only Mojang's texture host is accepted.
pub async fn skin_data_url(state: &AppState, uuid: &str) -> BloomResult<Option<String>> {
    let Some(account) = state.db.get_account(uuid)? else { return Ok(None) };
    let Some(url) = account.skin_url else { return Ok(None) };
    let parsed = url::Url::parse(&url).map_err(|_| BloomError::InvalidInput("invalid skin URL".into()))?;
    if parsed.host_str() != Some("textures.minecraft.net") {
        return Err(BloomError::InvalidInput("unexpected skin host".into()));
    }
    let texture_id = parsed.path_segments().and_then(|mut s| s.next_back()).unwrap_or_default().to_string();
    if texture_id.is_empty() || !texture_id.chars().all(|c| c.is_ascii_alphanumeric()) {
        return Err(BloomError::InvalidInput("invalid skin URL".into()));
    }
    let cache = state.paths().skins().join(format!("{texture_id}.png"));
    let bytes = match tokio::fs::read(&cache).await {
        Ok(b) => b,
        Err(_) => {
            let https = format!("https://textures.minecraft.net/texture/{texture_id}");
            let b = state.downloader.fetch_bytes(&https).await?;
            if !b.starts_with(&[0x89, b'P', b'N', b'G']) {
                return Err(BloomError::Parse("skin is not a PNG image".into()));
            }
            crate::system::paths::write_atomic(&cache, &b)?;
            b
        }
    };
    Ok(Some(format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes))))
}
