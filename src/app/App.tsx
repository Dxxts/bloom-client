import { useEffect, useRef } from "react";
import { RouterProvider } from "react-router";
import { MotionConfig } from "motion/react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { BloomMark } from "@/components/BloomMark";
import { Button } from "@/components/ui/Button";
import { t } from "@/i18n";
import { api } from "@/services/api";
import { errorMessage } from "@/services/errors";
import { EVENTS, on } from "@/services/events";
import { useAccountsStore } from "@/stores/accountsStore";
import { useAppStore } from "@/stores/appStore";
import { useInstancesStore } from "@/stores/instancesStore";
import { useLogsStore, useSessionsStore } from "@/stores/sessionsStore";
import { useUiStore } from "@/stores/uiStore";
import type { GameSession, LogLine, ProgressEvent } from "@/types";
import { router } from "./router";

function instanceName(id: string): string {
  return useInstancesStore.getState().instances.find((i) => i.id === id)?.name ?? "Minecraft";
}

/** Subscribes once to backend events and routes them into the stores. */
function useBackendEvents(enabled: boolean) {
  useEffect(() => {
    if (!enabled) return;
    const subscriptions = [
      on<ProgressEvent>(EVENTS.progress, (p) => useSessionsStore.getState().setProgress(p)),
      on<GameSession>(EVENTS.gameState, (s) => {
        const previous = useSessionsStore.getState().sessions[s.instanceId];
        useSessionsStore.getState().setSession(s);
        if (previous?.stage === s.stage) return;
        const name = instanceName(s.instanceId);
        const openConsole = () => void router.navigate(`/instances/${s.instanceId}?tab=console`);
        if (s.stage === "failed") {
          useUiStore.getState().showError({
            title: t("error.launchTitle", { name }),
            message: s.message,
            action: { label: t("common.viewLogs"), onClick: openConsole },
          });
        } else if (s.stage === "crashed") {
          useUiStore.getState().pushToast({
            kind: "error",
            title: t("stage.crashed"),
            message: `${name}: ${s.message}`,
            action: { label: t("common.viewLogs"), onClick: openConsole },
          });
        }
      }),
      on<{ instanceId: string; lines: LogLine[] }>(EVENTS.gameLog, (b) =>
        useLogsStore.getState().append(b.instanceId, b.lines),
      ),
      on(EVENTS.accountsChanged, () => void useAccountsStore.getState().load()),
      on(EVENTS.instancesChanged, () => void useInstancesStore.getState().load()),
    ];
    return () => {
      for (const s of subscriptions) void s.then((unlisten) => unlisten());
    };
  }, [enabled]);
}

/** Applies theme and motion preferences to the document root. */
function useDocumentPreferences() {
  const theme = useAppStore((s) => s.settings?.theme ?? "dark");
  const motion = useAppStore((s) => s.settings?.reducedMotion ?? "system");
  const lang = useAppStore((s) => s.settings?.language ?? "en");
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = theme;
    root.dataset.reducedMotion = motion;
    root.lang = lang;
  }, [theme, motion, lang]);
  return motion;
}

function useStartupUpdateCheck(ready: boolean) {
  const checked = useRef(false);
  useEffect(() => {
    if (!ready || checked.current) return;
    checked.current = true;
    const { settings, info } = useAppStore.getState();
    if (!settings?.autoUpdate || !info?.updaterConfigured) return;
    api
      .checkForUpdates()
      .then((u) => {
        if (u.available && u.version) {
          useUiStore.getState().pushToast({
            kind: "info",
            title: t("settings.about.available", { version: u.version }),
            action: { label: t("settings.about.install"), onClick: () => void router.navigate("/settings?tab=about") },
          });
        }
      })
      .catch(() => {
        // Update checks are best-effort at startup; errors are visible in Settings → About.
      });
  }, [ready]);
}

export function App() {
  const ready = useAppStore((s) => s.ready);
  const bootError = useAppStore((s) => s.bootError);
  const boot = useAppStore((s) => s.boot);
  const reducedMotion = useDocumentPreferences();

  useEffect(() => {
    void (async () => {
      await boot();
      if (!useAppStore.getState().ready) return;
      await Promise.allSettled([
        useAccountsStore.getState().load(),
        useInstancesStore.getState().load(),
        useSessionsStore.getState().load(),
      ]);
    })().finally(() => {
      // The window starts hidden to avoid a white flash; show it once styled.
      const win = getCurrentWindow();
      void win.show().then(() => win.setFocus());
    });
  }, [boot]);

  useBackendEvents(ready);
  useStartupUpdateCheck(ready);

  if (bootError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-bg p-10 text-center">
        <BloomMark size={48} />
        <h1 className="text-lg font-bold text-text">Bloom could not start</h1>
        <p className="max-w-md text-[13px] text-muted" data-selectable>
          {errorMessage(bootError)}
        </p>
        <Button variant="primary" onClick={() => void boot()}>
          Retry
        </Button>
      </div>
    );
  }

  if (!ready) {
    return (
      <div className="flex h-full items-center justify-center bg-bg">
        <BloomMark size={44} className="animate-pulse-soft" />
      </div>
    );
  }

  return (
    <MotionConfig reducedMotion={reducedMotion === "on" ? "always" : reducedMotion === "off" ? "never" : "user"}>
      <RouterProvider router={router} />
    </MotionConfig>
  );
}
