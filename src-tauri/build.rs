fn main() {
    // Re-run when build-time configuration changes, so the values baked in
    // through `option_env!` stay in sync with the environment.
    println!("cargo:rerun-if-env-changed=BLOOM_MS_CLIENT_ID");
    println!("cargo:rerun-if-env-changed=BLOOM_UPDATE_ENDPOINT");
    println!("cargo:rerun-if-env-changed=BLOOM_ANNOUNCEMENTS_URL");
    tauri_build::build()
}
