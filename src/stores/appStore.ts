import { create } from "zustand";
import { api } from "@/services/api";
import type { AppInfo, Settings } from "@/types";

interface AppState {
  info: AppInfo | null;
  settings: Settings | null;
  ready: boolean;
  bootError: string | null;
  sidebarCollapsed: boolean;
  boot: () => Promise<void>;
  refreshInfo: () => Promise<void>;
  /** Persists a partial change; the backend validates and returns the stored value. */
  saveSettings: (patch: Partial<Settings>) => Promise<Settings>;
  replaceSettings: (s: Settings) => void;
  toggleSidebar: () => void;
}

const SIDEBAR_KEY = "bloom.sidebarCollapsed";

function readSidebarPref(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_KEY) === "1";
  } catch {
    return false;
  }
}

export const useAppStore = create<AppState>((set, get) => ({
  info: null,
  settings: null,
  ready: false,
  bootError: null,
  sidebarCollapsed: readSidebarPref(),

  boot: async () => {
    try {
      const [info, settings] = await Promise.all([api.getAppInfo(), api.getSettings()]);
      set({ info, settings, ready: true, bootError: null });
    } catch (e) {
      set({ bootError: e instanceof Error ? e.message : String(e) });
    }
  },

  refreshInfo: async () => {
    set({ info: await api.getAppInfo() });
  },

  saveSettings: async (patch) => {
    const current = get().settings;
    if (!current) throw new Error("settings not loaded");
    const saved = await api.updateSettings({ ...current, ...patch });
    set({ settings: saved });
    // Paths and auth configuration depend on settings.
    void get().refreshInfo();
    return saved;
  },

  replaceSettings: (s) => set({ settings: s }),

  toggleSidebar: () => {
    const next = !get().sidebarCollapsed;
    try {
      localStorage.setItem(SIDEBAR_KEY, next ? "1" : "0");
    } catch {
      // Preference is only a convenience.
    }
    set({ sidebarCollapsed: next });
  },
}));
