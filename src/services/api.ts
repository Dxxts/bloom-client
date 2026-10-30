// Typed client for the Rust command layer. This is the only module that
// calls `invoke`; components never talk to Tauri directly.

import { invoke } from "@tauri-apps/api/core";
import type {
  Account,
  AppInfo,
  GameSession,
  Instance,
  InstanceInput,
  JavaRuntime,
  JavaTestResult,
  LaunchRecord,
  LoaderInfo,
  RenderEngineInfo,
  LogLine,
  MinecraftDiagnostics,
  ModEntry,
  NewsItem,
  ResourcePackEntry,
  Settings,
  StorageUsage,
  SystemInfo,
  UpdateInfo,
  VersionDetails,
  VersionList,
} from "@/types";

export interface ApiErrorPayload {
  code: string;
  message: string;
}

export class ApiError extends Error {
  readonly code: string;
  constructor(payload: ApiErrorPayload) {
    super(payload.message);
    this.code = payload.code;
    this.name = "ApiError";
  }
}

function toApiError(e: unknown): ApiError {
  if (e instanceof ApiError) return e;
  if (e && typeof e === "object" && "code" in e && "message" in e) {
    return new ApiError(e as ApiErrorPayload);
  }
  return new ApiError({ code: "internal", message: typeof e === "string" ? e : String(e) });
}

async function call<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  try {
    return await invoke<T>(cmd, args);
  } catch (e) {
    throw toApiError(e);
  }
}

export const api = {
  // app & settings
  getAppInfo: () => call<AppInfo>("get_app_info"),
  getSettings: () => call<Settings>("get_settings"),
  updateSettings: (settings: Settings) => call<Settings>("update_settings", { settings }),
  resetSettings: () => call<Settings>("reset_settings"),
  setSelectedInstance: (instanceId: string | null) => call<void>("set_selected_instance", { instanceId }),

  // accounts
  listAccounts: () => call<Account[]>("list_accounts"),
  loginMicrosoft: () => call<Account>("login_microsoft"),
  cancelLogin: () => call<void>("cancel_login"),
  logoutAccount: (uuid: string) => call<void>("logout_account", { uuid }),
  setActiveAccount: (uuid: string) => call<void>("set_active_account", { uuid }),
  refreshAccount: (uuid: string) => call<Account>("refresh_account", { uuid }),
  getAccountSkin: (uuid: string) => call<string | null>("get_account_skin", { uuid }),

  // versions
  listVersions: (force = false) => call<VersionList>("list_versions", { force }),
  getVersionDetails: (id: string) => call<VersionDetails>("get_version_details", { id }),
  installVersion: (id: string) => call<void>("install_version", { id }),
  deleteVersion: (id: string) => call<void>("delete_version", { id }),
  cancelTask: (taskId: string) => call<boolean>("cancel_task", { taskId }),

  // instances
  listInstances: () => call<Instance[]>("list_instances"),
  getInstance: (id: string) => call<Instance>("get_instance", { id }),
  createInstance: (input: InstanceInput) => call<Instance>("create_instance", { input }),
  updateInstance: (id: string, input: InstanceInput) => call<Instance>("update_instance", { id, input }),
  duplicateInstance: (id: string) => call<Instance>("duplicate_instance", { id }),
  deleteInstance: (id: string) => call<void>("delete_instance", { id }),
  listLoaders: () => call<LoaderInfo[]>("list_loaders"),
  listRenderEngines: () => call<RenderEngineInfo[]>("list_render_engines"),
  openInstanceFolder: (id: string, sub?: string) => call<void>("open_instance_folder", { id, sub: sub ?? null }),

  // content
  listMods: (instanceId: string) => call<ModEntry[]>("list_mods", { instanceId }),
  setModEnabled: (instanceId: string, fileName: string, enabled: boolean) =>
    call<string>("set_mod_enabled", { instanceId, fileName, enabled }),
  deleteMod: (instanceId: string, fileName: string) => call<void>("delete_mod", { instanceId, fileName }),
  importMods: (instanceId: string) => call<number>("import_mods", { instanceId }),
  listResourcePacks: (instanceId: string) => call<ResourcePackEntry[]>("list_resource_packs", { instanceId }),
  setResourcePackEnabled: (instanceId: string, fileName: string, enabled: boolean) =>
    call<void>("set_resource_pack_enabled", { instanceId, fileName, enabled }),
  deleteResourcePack: (instanceId: string, fileName: string) =>
    call<void>("delete_resource_pack", { instanceId, fileName }),
  importResourcePacks: (instanceId: string) => call<number>("import_resource_packs", { instanceId }),

  // java
  listJavaRuntimes: () => call<JavaRuntime[]>("list_java_runtimes"),
  detectJavaRuntimes: () => call<JavaRuntime[]>("detect_java_runtimes"),
  addJavaRuntime: () => call<JavaRuntime | null>("add_java_runtime"),
  testJavaRuntime: (path: string) => call<JavaTestResult>("test_java_runtime", { path }),
  removeJavaRuntime: (path: string) => call<void>("remove_java_runtime", { path }),
  installJavaRuntime: (component: string) => call<JavaRuntime>("install_java_runtime", { component }),
  uninstallJavaRuntime: (component: string) => call<void>("uninstall_java_runtime", { component }),

  // launch
  launchInstance: (instanceId: string) => call<void>("launch_instance", { instanceId }),
  stopInstance: (instanceId: string) => call<boolean>("stop_instance", { instanceId }),
  getSessions: () => call<GameSession[]>("get_sessions"),
  getSessionLogs: (instanceId: string) => call<LogLine[]>("get_session_logs", { instanceId }),
  listLaunchHistory: (limit?: number) => call<LaunchRecord[]>("list_launch_history", { limit: limit ?? null }),
  readLaunchLog: (launchId: number, maxLines?: number) =>
    call<string[]>("read_launch_log", { launchId, maxLines: maxLines ?? null }),

  // system
  getSystemInfo: () => call<SystemInfo>("get_system_info"),
  openBloomFolder: (kind: "data" | "instances" | "config" | "logs" | "runtimes") =>
    call<void>("open_bloom_folder", { kind }),
  pickDirectory: () => call<string | null>("pick_directory"),
  getStorageUsage: () => call<StorageUsage>("get_storage_usage"),
  clearMetadataCache: () => call<void>("clear_metadata_cache"),
  clearVerificationCache: () => call<number>("clear_verification_cache"),
  pruneUnusedFiles: () => call<number>("prune_unused_files"),
  runMinecraftDiagnostics: () => call<MinecraftDiagnostics>("run_minecraft_diagnostics"),
  exportDiagnostics: () => call<string | null>("export_diagnostics"),
  getNews: () => call<NewsItem[]>("get_news"),
  getNewsArticle: (id: string) => call<string>("get_news_article", { id }),
  checkForUpdates: () => call<UpdateInfo>("check_for_updates"),
  installUpdate: () => call<void>("install_update"),
};
