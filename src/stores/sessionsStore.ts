import { create } from "zustand";
import { api } from "@/services/api";
import { ACTIVE_STAGES, type GameSession, type LogLine, type ProgressEvent } from "@/types";

const MAX_LINES = 5000;

interface SessionsState {
  sessions: Record<string, GameSession>;
  /** Latest progress per task id (instance id or "version:<id>" / "java:<component>"). */
  progress: Record<string, ProgressEvent>;
  load: () => Promise<void>;
  setSession: (s: GameSession) => void;
  setProgress: (p: ProgressEvent) => void;
  clearProgress: (taskId: string) => void;
}

export const useSessionsStore = create<SessionsState>((set) => ({
  sessions: {},
  progress: {},

  load: async () => {
    const list = await api.getSessions();
    set({ sessions: Object.fromEntries(list.map((s) => [s.instanceId, s])) });
  },

  setSession: (session) =>
    set((s) => {
      const progress = { ...s.progress };
      if (!["checking_files", "downloading", "installing"].includes(session.stage)) {
        delete progress[session.instanceId];
      }
      return { sessions: { ...s.sessions, [session.instanceId]: session }, progress };
    }),

  setProgress: (p) =>
    set((s) => {
      const progress = { ...s.progress };
      // Terminal stages close standalone tasks (version / Java installs).
      if (["exited", "failed", "cancelled", "crashed"].includes(p.stage)) delete progress[p.taskId];
      else progress[p.taskId] = p;
      return { progress };
    }),

  clearProgress: (taskId) =>
    set((s) => {
      const progress = { ...s.progress };
      delete progress[taskId];
      return { progress };
    }),
}));

export const isActive = (s: GameSession | undefined | null) => !!s && ACTIVE_STAGES.includes(s.stage);

/** Game output, kept separate so log bursts only re-render the console. */
interface LogsState {
  logs: Record<string, LogLine[]>;
  append: (instanceId: string, lines: LogLine[]) => void;
  replace: (instanceId: string, lines: LogLine[]) => void;
}

export const useLogsStore = create<LogsState>((set) => ({
  logs: {},
  append: (instanceId, lines) =>
    set((s) => {
      const merged = (s.logs[instanceId] ?? []).concat(lines);
      return {
        logs: { ...s.logs, [instanceId]: merged.length > MAX_LINES ? merged.slice(-MAX_LINES) : merged },
      };
    }),
  replace: (instanceId, lines) => set((s) => ({ logs: { ...s.logs, [instanceId]: lines.slice(-MAX_LINES) } })),
}));
