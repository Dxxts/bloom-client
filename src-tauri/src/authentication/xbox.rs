//! Xbox Live, XSTS and Minecraft Services token exchange.

use std::time::Duration;

use base64::Engine;
use serde::Deserialize;
use serde_json::json;

use crate::errors::{BloomError, BloomResult};

const XBL_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MC_LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MC_ENTITLEMENTS_URL: &str = "https://api.minecraftservices.com/entitlements/mcstore";
const MC_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XboxTokenResponse {
    token: String,
    display_claims: DisplayClaims,
}

#[derive(Deserialize)]
struct DisplayClaims {
    xui: Vec<Xui>,
}

#[derive(Deserialize)]
struct Xui {
    uhs: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XstsError {
    x_err: Option<u64>,
}

pub struct XstsToken {
    pub token: String,
    pub user_hash: String,
}

pub async fn xbox_live(client: &reqwest::Client, msa_access_token: &str) -> BloomResult<String> {
    let resp = client
        .post(XBL_URL)
        .header("Accept", "application/json")
        .json(&json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={msa_access_token}")
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT"
        }))
        .timeout(Duration::from_secs(30))
        .send()
        .await?;
    if !resp.status().is_success() {
        return Err(BloomError::Auth(format!("Xbox Live authentication failed (HTTP {})", resp.status().as_u16())));
    }
    let body: XboxTokenResponse = resp.json().await?;
    Ok(body.token)
}

pub async fn xsts(client: &reqwest::Client, xbl_token: &str) -> BloomResult<XstsToken> {
    let resp = client
        .post(XSTS_URL)
        .header("Accept", "application/json")
        .json(&json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl_token] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT"
        }))
        .timeout(Duration::from_secs(30))
        .send()
        .await?;
    let status = resp.status();
    if status == reqwest::StatusCode::UNAUTHORIZED {
        let err: XstsError = resp.json().await.unwrap_or(XstsError { x_err: None });
        return Err(BloomError::Auth(xsts_error_message(err.x_err)));
    }
    if !status.is_success() {
        return Err(BloomError::Auth(format!("Xbox security token request failed (HTTP {})", status.as_u16())));
    }
    let body: XboxTokenResponse = resp.json().await?;
    let user_hash = body
        .display_claims
        .xui
        .first()
        .map(|x| x.uhs.clone())
        .ok_or_else(|| BloomError::Auth("Xbox response did not include a user hash".into()))?;
    Ok(XstsToken { token: body.token, user_hash })
}

fn xsts_error_message(code: Option<u64>) -> String {
    match code {
        Some(2148916233) => "this Microsoft account has no Xbox profile. Sign in once at xbox.com to create one, then try again.".into(),
        Some(2148916235) => "Xbox Live is not available in your country or region.".into(),
        Some(2148916236) | Some(2148916237) => "this account requires adult verification on xbox.com.".into(),
        Some(2148916238) => "this is a child account: it must be added to a Microsoft family by an adult before it can play.".into(),
        Some(c) => format!("Xbox authorization was refused (code {c})."),
        None => "Xbox authorization was refused.".into(),
    }
}

#[derive(Deserialize)]
pub struct MinecraftToken {
    pub access_token: String,
    pub expires_in: i64,
}

pub async fn minecraft_login(client: &reqwest::Client, xsts: &XstsToken) -> BloomResult<MinecraftToken> {
    let resp = client
        .post(MC_LOGIN_URL)
        .json(&json!({ "identityToken": format!("XBL3.0 x={};{}", xsts.user_hash, xsts.token) }))
        .timeout(Duration::from_secs(30))
        .send()
        .await?;
    let status = resp.status();
    if status.is_success() {
        return Ok(resp.json().await?);
    }
    // Error bodies are small JSON objects ({"path", "errorMessage", ...}) with
    // no credentials in them, so they are safe to log for diagnosis.
    let body = resp.text().await.unwrap_or_default();
    let body: String = body.chars().take(600).collect();
    tracing::warn!(status = status.as_u16(), body = %body, "Minecraft Services login_with_xbox rejected");
    let message = serde_json::from_str::<McErrorBody>(&body).ok().and_then(|b| b.error_message);

    if status == reqwest::StatusCode::TOO_MANY_REQUESTS {
        return Err(BloomError::Auth("too many sign-in attempts, wait a few minutes and try again".into()));
    }
    if status == reqwest::StatusCode::FORBIDDEN || status == reqwest::StatusCode::UNAUTHORIZED {
        // New Azure applications must be approved by Mojang before they can
        // use the Minecraft Services API; Mojang reports it as an invalid app
        // registration. Anything else is surfaced verbatim.
        let unapproved = message.as_deref().is_none_or(|m| m.to_ascii_lowercase().contains("app registration"));
        if unapproved {
            return Err(BloomError::MinecraftApiNotApproved);
        }
    }
    Err(BloomError::Auth(match message {
        Some(m) => format!("Minecraft Services refused the sign-in (HTTP {}): {m}", status.as_u16()),
        None => format!("Minecraft Services login failed (HTTP {})", status.as_u16()),
    }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct McErrorBody {
    error_message: Option<String>,
}

#[derive(Deserialize)]
struct Entitlements {
    #[serde(default)]
    items: Vec<EntitlementItem>,
}

#[derive(Deserialize)]
struct EntitlementItem {
    name: String,
}

pub async fn owns_game(client: &reqwest::Client, mc_token: &str) -> BloomResult<bool> {
    let resp = client
        .get(MC_ENTITLEMENTS_URL)
        .bearer_auth(mc_token)
        .timeout(Duration::from_secs(30))
        .send()
        .await?;
    if !resp.status().is_success() {
        return Err(BloomError::Auth(format!("could not verify game ownership (HTTP {})", resp.status().as_u16())));
    }
    let e: Entitlements = resp.json().await?;
    Ok(e.items.iter().any(|i| i.name == "game_minecraft" || i.name == "product_minecraft"))
}

#[derive(Debug, Clone, Deserialize)]
pub struct Profile {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub skins: Vec<Skin>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Skin {
    pub state: String,
    pub url: String,
    pub variant: Option<String>,
}

impl Profile {
    pub fn active_skin(&self) -> Option<&Skin> {
        self.skins.iter().find(|s| s.state == "ACTIVE")
    }

    /// UUID with dashes, as used by the game's `--uuid` argument.
    pub fn uuid(&self) -> String {
        let id = self.id.replace('-', "");
        if id.len() == 32 {
            format!("{}-{}-{}-{}-{}", &id[0..8], &id[8..12], &id[12..16], &id[16..20], &id[20..32])
        } else {
            self.id.clone()
        }
    }
}

pub async fn profile(client: &reqwest::Client, mc_token: &str) -> BloomResult<Profile> {
    let resp = client
        .get(MC_PROFILE_URL)
        .bearer_auth(mc_token)
        .timeout(Duration::from_secs(30))
        .send()
        .await?;
    match resp.status() {
        s if s.is_success() => Ok(resp.json().await?),
        reqwest::StatusCode::NOT_FOUND => Err(BloomError::Auth(
            "this account has no Minecraft profile yet. Launch the official launcher once to choose a username.".into(),
        )),
        reqwest::StatusCode::UNAUTHORIZED => Err(BloomError::AuthExpired),
        s => Err(BloomError::Auth(format!("could not load the Minecraft profile (HTTP {})", s.as_u16()))),
    }
}

/// Reads the `xuid` claim from the Minecraft access token (a JWT). The token
/// is not validated here: it was just received over TLS from Mojang and the
/// value is only forwarded to the game.
pub fn xuid_from_token(token: &str) -> Option<String> {
    let payload = token.split('.').nth(1)?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(payload.trim_end_matches('='))
        .ok()?;
    let v: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    v.get("xuid").and_then(|x| x.as_str().map(String::from).or_else(|| x.as_u64().map(|n| n.to_string())))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuid_formatting() {
        let p = Profile { id: "069a79f444e94726a5befca90e38aaf5".into(), name: "Notch".into(), skins: vec![] };
        assert_eq!(p.uuid(), "069a79f4-44e9-4726-a5be-fca90e38aaf5");
    }

    #[test]
    fn xuid_extraction() {
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(br#"{"xuid":"2535400000000000"}"#);
        let token = format!("h.{payload}.s");
        assert_eq!(xuid_from_token(&token).as_deref(), Some("2535400000000000"));
        assert_eq!(xuid_from_token("garbage"), None);
    }
}
