//! OAuth 2.0 Authorization Code flow with PKCE against the Microsoft
//! identity platform (`consumers` tenant), using the system browser and a
//! one-shot loopback HTTP listener on 127.0.0.1 for the redirect.

use std::time::Duration;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::RngCore;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::errors::{BloomError, BloomResult};

pub const AUTHORIZE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
pub const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
pub const SCOPE: &str = "XboxLive.signin offline_access";
const LOGIN_TIMEOUT: Duration = Duration::from_secs(300);

pub struct Pkce {
    pub verifier: String,
    pub challenge: String,
}

fn random_token(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::rngs::OsRng.fill_bytes(&mut buf);
    URL_SAFE_NO_PAD.encode(buf)
}

impl Pkce {
    pub fn generate() -> Self {
        // 64 random bytes → 86 char verifier (RFC 7636 allows 43..128).
        let verifier = random_token(64);
        let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
        Self { verifier, challenge }
    }
}

pub struct PendingAuthorization {
    pub url: String,
    pub redirect_uri: String,
    pub pkce: Pkce,
    state: String,
    listener: TcpListener,
}

impl PendingAuthorization {
    /// Binds the loopback listener and builds the authorization URL.
    pub async fn start(client_id: &str) -> BloomResult<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let port = listener.local_addr()?.port();
        // Microsoft ignores the port of `http://localhost` redirect URIs for
        // public desktop clients, so only `http://localhost` is registered.
        let redirect_uri = format!("http://localhost:{port}");
        let pkce = Pkce::generate();
        let state = random_token(24);
        let mut url = url::Url::parse(AUTHORIZE_URL).map_err(|e| BloomError::Internal(e.to_string()))?;
        url.query_pairs_mut()
            .append_pair("client_id", client_id)
            .append_pair("response_type", "code")
            .append_pair("redirect_uri", &redirect_uri)
            .append_pair("response_mode", "query")
            .append_pair("scope", SCOPE)
            .append_pair("state", &state)
            .append_pair("code_challenge", &pkce.challenge)
            .append_pair("code_challenge_method", "S256")
            .append_pair("prompt", "select_account");
        Ok(Self { url: url.to_string(), redirect_uri, pkce, state, listener })
    }

    /// Waits for the browser redirect and returns the authorization code.
    pub async fn wait_for_code(self, cancel: &CancellationToken) -> BloomResult<(String, String, Pkce)> {
        let state = self.state.clone();
        let fut = async {
            loop {
                let (mut socket, _) = self.listener.accept().await?;
                let mut buf = vec![0u8; 8192];
                let n = tokio::time::timeout(Duration::from_secs(10), socket.read(&mut buf))
                    .await
                    .map_err(|_| BloomError::Auth("browser connection timed out".into()))??;
                let request = String::from_utf8_lossy(&buf[..n]);
                let Some(target) = request.lines().next().and_then(|l| l.split_whitespace().nth(1)) else {
                    continue;
                };
                let Ok(parsed) = url::Url::parse(&format!("http://localhost{target}")) else { continue };
                if parsed.path() != "/" {
                    let _ = respond(&mut socket, 404, "Not found", "").await;
                    continue;
                }
                let params: std::collections::HashMap<String, String> = parsed.query_pairs().into_owned().collect();
                if params.get("state") != Some(&state) {
                    let _ = respond(
                        &mut socket,
                        400,
                        "Sign-in failed",
                        "This response doesn't match the sign-in Bloom started. Try again from Bloom Client.",
                    )
                    .await;
                    return Err(BloomError::Auth("invalid state in authorization response".into()));
                }
                if let Some(err) = params.get("error") {
                    let desc = params.get("error_description").cloned().unwrap_or_default();
                    let _ = respond(
                        &mut socket,
                        400,
                        "Sign-in cancelled",
                        "Nothing was changed. You can try again from Bloom Client whenever you like.",
                    )
                    .await;
                    return Err(if err == "access_denied" {
                        BloomError::Cancelled
                    } else {
                        BloomError::Auth(format!("{err}: {desc}"))
                    });
                }
                let Some(code) = params.get("code").cloned() else {
                    let _ = respond(
                        &mut socket,
                        400,
                        "Sign-in failed",
                        "Microsoft didn't return an authorization code. Try again from Bloom Client.",
                    )
                    .await;
                    return Err(BloomError::Auth("no authorization code returned".into()));
                };
                let _ = respond(
                    &mut socket,
                    200,
                    "Signed in with Microsoft",
                    // Xbox Live and Minecraft checks still run in the app after this page.
                    "Head back to Bloom Client: it's finishing up with Xbox Live and Minecraft.",
                )
                .await;
                return Ok(code);
            }
        };
        let code = tokio::select! {
            r = tokio::time::timeout(LOGIN_TIMEOUT, fut) => r.map_err(|_| BloomError::Auth("sign-in timed out".into()))??,
            _ = cancel.cancelled() => return Err(BloomError::Cancelled),
        };
        Ok((code, self.redirect_uri, self.pkce))
    }
}

/// Page shown in the browser after the redirect, styled like the launcher.
const CALLBACK_PAGE: &str = include_str!("callback.html");

async fn respond(socket: &mut tokio::net::TcpStream, status: u16, title: &str, message: &str) -> std::io::Result<()> {
    let reason = match status {
        200 => "OK",
        404 => "Not Found",
        _ => "Bad Request",
    };
    // Titles and messages are fixed strings from this file, so no escaping is needed.
    let body = CALLBACK_PAGE
        .replace("{{TONE}}", if status == 200 { "ok" } else { "err" })
        .replace("{{TITLE}}", title)
        .replace("{{MESSAGE}}", message);
    let resp = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\n\
         Cache-Control: no-store\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    socket.write_all(resp.as_bytes()).await?;
    socket.shutdown().await
}

#[derive(Debug, Deserialize)]
pub struct TokenResponse {
    pub access_token: String,
    pub refresh_token: Option<String>,
}

#[derive(Debug, Deserialize)]
struct TokenError {
    error: String,
    error_description: Option<String>,
}

pub async fn exchange_code(
    client: &reqwest::Client,
    client_id: &str,
    code: &str,
    redirect_uri: &str,
    verifier: &str,
) -> BloomResult<TokenResponse> {
    token_request(
        client,
        &[
            ("client_id", client_id),
            ("grant_type", "authorization_code"),
            ("code", code),
            ("redirect_uri", redirect_uri),
            ("code_verifier", verifier),
            ("scope", SCOPE),
        ],
    )
    .await
}

pub async fn refresh(client: &reqwest::Client, client_id: &str, refresh_token: &str) -> BloomResult<TokenResponse> {
    token_request(
        client,
        &[
            ("client_id", client_id),
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("scope", SCOPE),
        ],
    )
    .await
}

async fn token_request(client: &reqwest::Client, form: &[(&str, &str)]) -> BloomResult<TokenResponse> {
    let resp = client.post(TOKEN_URL).form(form).timeout(Duration::from_secs(30)).send().await?;
    let status = resp.status();
    let bytes = resp.bytes().await?;
    if status.is_success() {
        return Ok(serde_json::from_slice(&bytes)?);
    }
    match serde_json::from_slice::<TokenError>(&bytes) {
        Ok(e) if e.error == "invalid_grant" => Err(BloomError::AuthExpired),
        Ok(e) if e.error == "invalid_client" || e.error == "unauthorized_client" => Err(BloomError::AuthNotConfigured(
            format!("Microsoft rejected the client ID ({})", e.error_description.unwrap_or(e.error)),
        )),
        Ok(e) => Err(BloomError::Auth(e.error_description.unwrap_or(e.error))),
        Err(_) => Err(BloomError::Network(format!("token endpoint responded with HTTP {}", status.as_u16()))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_challenge_matches_verifier() {
        let p = Pkce::generate();
        assert!(p.verifier.len() >= 43 && p.verifier.len() <= 128);
        assert_eq!(p.challenge, URL_SAFE_NO_PAD.encode(Sha256::digest(p.verifier.as_bytes())));
        assert_ne!(Pkce::generate().verifier, p.verifier);
    }

    #[tokio::test]
    async fn loopback_receives_code_and_validates_state() {
        let pending = PendingAuthorization::start("00000000-0000-0000-0000-000000000000").await.unwrap();
        let url = url::Url::parse(&pending.url).unwrap();
        let params: std::collections::HashMap<_, _> = url.query_pairs().into_owned().collect();
        let state = params["state"].clone();
        let port: u16 = pending.redirect_uri.rsplit(':').next().unwrap().parse().unwrap();
        let cancel = CancellationToken::new();
        let waiter = tokio::spawn(async move { pending.wait_for_code(&cancel).await });
        let mut s = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        s.write_all(format!("GET /?code=abc&state={state} HTTP/1.1\r\nHost: localhost\r\n\r\n").as_bytes())
            .await
            .unwrap();
        let (code, _, _) = waiter.await.unwrap().unwrap();
        assert_eq!(code, "abc");
    }

    #[tokio::test]
    async fn loopback_rejects_wrong_state() {
        let pending = PendingAuthorization::start("00000000-0000-0000-0000-000000000000").await.unwrap();
        let port: u16 = pending.redirect_uri.rsplit(':').next().unwrap().parse().unwrap();
        let cancel = CancellationToken::new();
        let waiter = tokio::spawn(async move { pending.wait_for_code(&cancel).await });
        let mut s = tokio::net::TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        s.write_all(b"GET /?code=abc&state=forged HTTP/1.1\r\n\r\n").await.unwrap();
        assert!(waiter.await.unwrap().is_err());
    }
}
