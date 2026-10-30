import { useCallback } from "react";
import { useNavigate } from "react-router";
import { useT } from "@/i18n";
import { api } from "@/services/api";
import { reportError } from "@/services/errors";
import { selectActiveAccount, useAccountsStore } from "@/stores/accountsStore";
import { isActive, useSessionsStore } from "@/stores/sessionsStore";
import { toast } from "@/stores/uiStore";
import type { GameSession, Instance, ProgressEvent } from "@/types";

export interface LaunchControls {
  session: GameSession | null;
  progress: ProgressEvent | null;
  active: boolean;
  running: boolean;
  launch: () => Promise<void>;
  stop: () => Promise<void>;
}

/** Launch state and actions for one instance, driven by backend events. */
export function useLaunch(instance: Instance | null): LaunchControls {
  const t = useT();
  const navigate = useNavigate();
  const session = useSessionsStore((s) => (instance ? (s.sessions[instance.id] ?? null) : null));
  const progress = useSessionsStore((s) => (instance ? (s.progress[instance.id] ?? null) : null));
  const account = useAccountsStore(selectActiveAccount);

  const launch = useCallback(async () => {
    if (!instance) return;
    // Development builds launch offline without an account (backend: offline_dev_session).
    if (!account && !import.meta.env.DEV) {
      toast.info(t("home.signInToPlay"));
      navigate("/accounts");
      return;
    }
    try {
      await api.launchInstance(instance.id);
    } catch (e) {
      reportError(e, t("error.launchTitle", { name: instance.name }));
    }
  }, [instance, account, navigate, t]);

  const stop = useCallback(async () => {
    if (!instance) return;
    try {
      await api.stopInstance(instance.id);
    } catch (e) {
      reportError(e);
    }
  }, [instance]);

  return {
    session,
    progress,
    active: isActive(session),
    running: session?.stage === "running",
    launch,
    stop,
  };
}
