# Microsoft account authentication: setup

Bloom signs players in with the official Microsoft → Xbox Live → Minecraft
Services chain. It uses **OAuth 2.0 Authorization Code with PKCE** in the
system browser. Bloom never sees the Microsoft password.

Bloom does **not** ship a client ID. You need your own Azure application.

## 1. Register an Azure application

1. Open the [Azure portal](https://portal.azure.com) → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Name: `Bloom Client` (or your product name).
3. Supported account types: **Personal Microsoft accounts only**
   (Bloom uses the `consumers` tenant).
4. Redirect URI: platform **Public client/native (mobile & desktop)**, value `http://localhost`.
   Microsoft ignores the port of `http://localhost` redirect URIs for native
   clients. Bloom listens on a random free port on `127.0.0.1` for each sign-in.
5. Create the registration and copy the **Application (client) ID** (a GUID).
6. Under **Authentication**, set **Allow public client flows** to **Yes**.

No client secret is needed. Public desktop clients cannot keep a secret, so
Bloom must not use one.

API permissions: the `XboxLive.signin` and `offline_access` scopes are
requested at runtime. You do not need to add them in the portal.

## 2. Get Minecraft API access for the application

Mojang only allows approved Azure applications to call
`api.minecraftservices.com`. Until the application is approved, sign-in fails
at the "Checking Minecraft ownership" step. Bloom then shows:

> Microsoft sign-in worked, but Mojang hasn't approved this app for the
> Minecraft API yet.

Request access with Mojang's official form for third-party launchers
(<https://aka.ms/mce-reviewappid>). Give it your application (client) ID.
Approval is decided by Mojang/Microsoft.

## 3. Configure Bloom with the client ID

There are two ways. The Settings value wins over the build value.

**Build time (recommended for releases):**

```powershell
$env:BLOOM_MS_CLIENT_ID = "00000000-0000-0000-0000-000000000000"
npm run app:build
```

**Runtime (development / self-builds):** open **Settings → Advanced → Azure client ID**, paste the GUID and save.

## What is stored, and where

| Data | Location |
|---|---|
| Microsoft refresh token, Minecraft access token, token expiry, XUID | OS credential store: **Windows Credential Manager** (service `com.bloomclient.launcher`, entries `account:<uuid>`, split into chunks because of Windows' 2560-byte limit per credential) |
| UUID, username, skin URL, token expiry time, active flag | SQLite (`bloom.db`) |

Tokens are never written to the database, to log files or to diagnostics
exports. Game output is filtered so the access token is replaced with
`<redacted>` before it reaches the log viewer or the launch log files.

The `SecretStore` trait (`src-tauri/src/authentication/secret_store.rs`) hides
the platform backend. macOS (Keychain) and Linux (Secret Service) are selected
in `Cargo.toml` through the `keyring` crate features.

## Session lifecycle

- Minecraft access tokens last about 24 hours. Bloom renews them at launch when
  they expire within 10 minutes, and in the background at startup when they
  expire within 1 hour.
- If Microsoft rejects the refresh token (`invalid_grant`), the account is shown
  as **Sign in again** and its stored credentials are removed.
- **Sign out** deletes the account's credentials from Credential Manager and its
  row from the database.

## Error messages you may see

| Message | Cause |
|---|---|
| no Azure application client ID is configured | Step 3 not done |
| Microsoft rejected the client ID | Wrong GUID, or the app is not a public client / wrong account type |
| this Microsoft account has no Xbox profile | The user must sign in once at xbox.com |
| child account | Must be added to a Microsoft family |
| Mojang hasn't approved this app for the Minecraft API yet | Step 2 (Mojang approval) is pending |
| does not own Minecraft: Java Edition | No entitlement on the account |
| no Minecraft profile yet | Owned, but no username chosen yet (launch the official launcher once) |
