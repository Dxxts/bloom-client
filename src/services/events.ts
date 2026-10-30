// Backend → frontend event channel names. Keep in sync with
// src-tauri/src/services/events.rs.

import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export const EVENTS = {
  progress: "bloom://progress",
  gameState: "bloom://game-state",
  gameLog: "bloom://game-log",
  authProgress: "bloom://auth-progress",
  accountsChanged: "bloom://accounts-changed",
  instancesChanged: "bloom://instances-changed",
  updateProgress: "bloom://update-progress",
} as const;

export function on<T>(event: string, handler: (payload: T) => void): Promise<UnlistenFn> {
  return listen<T>(event, (e) => handler(e.payload));
}
