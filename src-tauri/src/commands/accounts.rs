//! Microsoft account commands.

use tauri::{Emitter, State};

use crate::authentication;
use crate::errors::BloomResult;
use crate::models::Account;
use crate::services::{events, state::AppState};

#[tauri::command]
pub fn list_accounts(state: State<'_, AppState>) -> BloomResult<Vec<Account>> {
    authentication::list_accounts(&state)
}

#[tauri::command]
pub async fn login_microsoft(state: State<'_, AppState>) -> BloomResult<Account> {
    let account = authentication::login(&state).await?;
    let _ = state.app.emit(events::ACCOUNTS_CHANGED, ());
    Ok(account)
}

#[tauri::command]
pub fn cancel_login(state: State<'_, AppState>) {
    state.auth.cancel_login();
}

#[tauri::command]
pub fn logout_account(state: State<'_, AppState>, uuid: String) -> BloomResult<()> {
    authentication::logout(&state, &uuid)?;
    let _ = state.app.emit(events::ACCOUNTS_CHANGED, ());
    Ok(())
}

#[tauri::command]
pub fn set_active_account(state: State<'_, AppState>, uuid: String) -> BloomResult<()> {
    state.db.set_active_account(&uuid)?;
    let _ = state.app.emit(events::ACCOUNTS_CHANGED, ());
    Ok(())
}

#[tauri::command]
pub async fn refresh_account(state: State<'_, AppState>, uuid: String) -> BloomResult<Account> {
    let result = authentication::refresh_account(&state, &uuid).await;
    let _ = state.app.emit(events::ACCOUNTS_CHANGED, ());
    result
}

#[tauri::command]
pub async fn get_account_skin(state: State<'_, AppState>, uuid: String) -> BloomResult<Option<String>> {
    authentication::skin_data_url(&state, &uuid).await
}
