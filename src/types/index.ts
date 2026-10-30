// Types mirror the Rust models in src-tauri/src/models (camelCase serde).

export type Language = "en" | "it";
export type Theme = "dark" | "midnight";
export type ReducedMotion = "system" | "on" | "off";
export type LauncherVisibility = "keep" | "minimize" | "hide";
export type LogLevel = "error" | "warn" | "info" | "debug" | "trace";

export interface Settings {
  language: Language;
  theme: Theme;
  reducedMotion: ReducedMotion;
  launchOnStartup: boolean;
  minimizeToTray: boolean;
  notifications: boolean;
  launcherVisibilityOnLaunch: LauncherVisibility;
  minRamMb: number;
  maxRamMb: number;
  javaPath: string | null;
  jvmArgs: string;
  resolutionWidth: number;
  resolutionHeight: number;
  fullscreen: boolean;
  instancesDirectory: string | null;
  dataDirectory: string | null;
  downloadConcurrency: number;
  autoUpdate: boolean;
  logLevel: LogLevel;
  microsoftClientId: string | null;
  selectedInstanceId: string | null;
  showSnapshots: boolean;
}

export interface AppInfo {
  name: string;
  version: string;
  os: string;
  arch: string;
  dataDir: string;
  instancesDir: string;
  defaultDataDir: string;
  configDir: string;
  logDir: string;
  microsoftAuthConfigured: boolean;
  microsoftClientIdSource: "settings" | "build" | "none";
  updaterConfigured: boolean;
  schemaVersion: number;
  totalMemoryMb: number;
}

export type AccountStatus = "valid" | "refreshable" | "expired";

export interface Account {
  uuid: string;
  username: string;
  skinUrl: string | null;
  skinVariant: string | null;
  active: boolean;
  status: AccountStatus;
  minecraftTokenExpiresAt: number | null;
  addedAt: number;
  lastUsedAt: number | null;
}

export type LoaderKind = "vanilla" | "fabric" | "forge" | "neoforge";
/** World renderer: Minecraft's own (Java) or the Bloom Native World Engine (Rust). */
export type EngineKind = "java" | "rust";
export type InstallState = "installed" | "not_installed" | "incomplete";

export interface Instance {
  id: string;
  name: string;
  versionId: string;
  loader: LoaderKind;
  loaderVersion: string | null;
  engine: EngineKind;
  javaPath: string | null;
  minRamMb: number | null;
  maxRamMb: number | null;
  resolutionWidth: number | null;
  resolutionHeight: number | null;
  fullscreen: boolean | null;
  jvmArgs: string | null;
  icon: string;
  createdAt: number;
  lastPlayedAt: number | null;
  totalPlaySeconds: number;
  installState: InstallState;
  directory: string;
}

export interface InstanceInput {
  name: string;
  versionId: string;
  loader: LoaderKind;
  loaderVersion: string | null;
  engine: EngineKind;
  javaPath: string | null;
  minRamMb: number | null;
  maxRamMb: number | null;
  resolutionWidth: number | null;
  resolutionHeight: number | null;
  fullscreen: boolean | null;
  jvmArgs: string | null;
  icon: string | null;
}

export interface RenderEngineInfo {
  kind: EngineKind;
  name: string;
  /** `null`: every version. */
  supportedVersions: string[] | null;
  available: boolean;
  detail: string | null;
}

export interface LoaderInfo {
  kind: LoaderKind;
  name: string;
  available: boolean;
}

export type ReleaseType = "release" | "snapshot" | "old_beta" | "old_alpha";

export interface VersionSummary {
  id: string;
  releaseType: ReleaseType | string;
  releaseTime: string;
  metadataUrl: string;
  metadataSha1: string | null;
  installed: boolean;
  javaMajor: number | null;
  javaComponent: string | null;
}

export interface VersionList {
  latestRelease: string | null;
  latestSnapshot: string | null;
  versions: VersionSummary[];
  fetchedAt: number;
  offline: boolean;
}

export interface VersionDetails {
  id: string;
  releaseType: string;
  releaseTime: string;
  mainClass: string;
  javaMajor: number;
  javaComponent: string;
  assetIndex: string;
  libraryCount: number;
  clientSize: number;
  installed: boolean;
  installedAt: number | null;
  diskSize: number | null;
}

export type Stage =
  | "preparing"
  | "checking_files"
  | "downloading"
  | "installing"
  | "launching"
  | "running"
  | "exited"
  | "crashed"
  | "failed"
  | "cancelled";

export const ACTIVE_STAGES: Stage[] = [
  "preparing",
  "checking_files",
  "downloading",
  "installing",
  "launching",
  "running",
];

export interface ProgressEvent {
  taskId: string;
  stage: Stage;
  message: string;
  filesDone: number;
  filesTotal: number;
  bytesDone: number;
  bytesTotal: number;
  speedBps: number;
  currentFile: string | null;
}

export interface GameSession {
  instanceId: string;
  stage: Stage;
  message: string;
  pid: number | null;
  startedAt: number | null;
  exitCode: number | null;
  lastProgress: ProgressEvent | null;
}

export interface LogLine {
  stream: "stdout" | "stderr";
  line: string;
}

export interface LaunchRecord {
  id: number;
  instanceId: string;
  instanceName: string | null;
  versionId: string;
  accountName: string | null;
  startedAt: number;
  endedAt: number | null;
  exitCode: number | null;
  crashed: boolean;
  logFile: string | null;
}

export interface JavaRuntime {
  path: string;
  version: string;
  major: number;
  vendor: string;
  arch: string;
  source: "managed" | "system" | "custom";
  component: string | null;
}

export interface JavaTestResult {
  version: string;
  major: number;
  vendor: string;
  arch: string;
}

export interface ModEntry {
  fileName: string;
  enabled: boolean;
  size: number;
  modifiedAt: number;
  modId: string | null;
  name: string | null;
  version: string | null;
  description: string | null;
  authors: string[];
  loader: string | null;
}

export interface ResourcePackEntry {
  fileName: string;
  isDirectory: boolean;
  enabled: boolean;
  size: number;
  modifiedAt: number;
  description: string | null;
  packFormat: number | null;
  iconDataUrl: string | null;
}

export interface NewsItem {
  id: string;
  title: string;
  version: string | null;
  category: string;
  date: string | null;
  imageUrl: string | null;
  bodyHtml: string;
  source: "mojang" | "bloom" | string;
}

export interface GpuInfo {
  name: string;
  vendor: string | null;
  driverVersion: string | null;
  memoryMb: number | null;
}

export interface SystemInfo {
  osName: string;
  osVersion: string;
  kernelVersion: string;
  arch: string;
  hostname: string;
  cpuBrand: string;
  cpuCoresLogical: number;
  cpuCoresPhysical: number | null;
  totalMemoryMb: number;
  availableMemoryMb: number;
  gpus: GpuInfo[];
}

export interface StorageUsage {
  versions: number;
  libraries: number;
  assets: number;
  runtimes: number;
  metadata: number;
  instances: number;
  verificationEntries: number;
}

export interface Reachability {
  name: string;
  url: string;
  ok: boolean;
  latencyMs: number | null;
  error: string | null;
}

export interface MinecraftDiagnostics {
  services: Reachability[];
  installedVersions: number;
  instances: number;
  dataDirWritable: boolean;
  dataDir: string;
  freeSpaceMb: number | null;
}

export interface UpdateInfo {
  configured: boolean;
  available: boolean;
  currentVersion: string;
  version: string | null;
  notes: string | null;
  date: string | null;
}

export interface UpdateProgress {
  downloaded: number;
  total: number | null;
  finished: boolean;
}

export type AuthStep = "browser" | "microsoft" | "xbox" | "minecraft" | "profile" | "done";
