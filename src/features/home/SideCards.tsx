import type { ReactNode } from "react";
import { useNavigate } from "react-router";
import { motion } from "motion/react";
import { AlertTriangle, ChevronRight, UserPlus, Users } from "lucide-react";
import { useLocale, useT, type TranslationKey } from "@/i18n";
import { PlayerHead } from "@/components/PlayerHead";
import { Button } from "@/components/ui/Button";
import { Card, Skeleton } from "@/components/ui/Card";
import { staggerItem } from "@/components/ui/Reveal";
import { loaderName } from "./PlayButton";
import { InstanceIcon } from "@/features/instances/InstanceIcon";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { selectActiveAccount, useAccountsStore } from "@/stores/accountsStore";
import { useInstancesStore } from "@/stores/instancesStore";
import { useSessionsStore } from "@/stores/sessionsStore";
import { cn, formatDuration, relativeTime } from "@/utils/format";
import { MicrosoftLogo } from "@/components/GameIcons";

function PanelHeader({ title, meta, action }: { title: ReactNode; meta?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <h3 className="flex items-center gap-2 text-[14px] font-extrabold tracking-tight text-text">
        {title}
        {meta && (
          <span className="rounded-md bg-white/[0.06] px-1.5 py-0.5 text-[11px] font-semibold tracking-normal text-muted">
            {meta}
          </span>
        )}
      </h3>
      {action}
    </div>
  );
}

/** Name of the instance currently running, if any (drives presence). */
function useRunningInstanceName(): string | null {
  const instances = useInstancesStore((s) => s.instances);
  const runningId = useSessionsStore(
    (s) => Object.values(s.sessions).find((x) => x.stage === "running")?.instanceId ?? null,
  );
  return runningId ? (instances.find((i) => i.id === runningId)?.name ?? "Minecraft") : null;
}

export function ProfileCard() {
  const t = useT();
  const navigate = useNavigate();
  const account = useAccountsStore(selectActiveAccount);
  const count = useAccountsStore((s) => s.accounts.length);
  const playing = useRunningInstanceName();

  if (!account) {
    return (
      <Card className="p-4">
        <div className="flex items-center gap-3">
          <PlayerHead uuid={null} name={null} size={44} />
          <div className="min-w-0">
            <div className="text-[15px] font-extrabold text-text">{t("home.notSignedIn")}</div>
            <div className="text-[12.5px] font-medium text-muted">{t("home.account")}</div>
          </div>
        </div>
        <Button variant="secondary" className="mt-4 w-full" icon={<MicrosoftLogo size={15} />} onClick={() => navigate("/accounts")}>
          {t("accounts.add")}
        </Button>
      </Card>
    );
  }

  const tone = account.status === "valid" ? "bg-primary" : account.status === "refreshable" ? "bg-warning" : "bg-error";
  const presence = playing
    ? t("home.presence.playing", { name: playing })
    : account.status === "expired"
      ? t(`accounts.status.${account.status}` as TranslationKey)
      : t("home.presence.online");

  return (
    <Card className="overflow-hidden">
      <button
        type="button"
        onClick={() => navigate("/accounts")}
        className="group flex w-full items-center gap-3 p-4 text-left transition-colors hover:bg-white/[0.02]"
      >
        <div className="relative">
          <PlayerHead uuid={account.uuid} name={account.username} size={44} />
          <span className={cn("absolute -bottom-0.5 -right-0.5 h-3 w-3 rounded-full ring-[3px] ring-surface", tone)} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[15px] font-extrabold text-text">{account.username}</div>
          <div className={cn("truncate text-[12.5px] font-semibold", playing ? "text-primary" : "text-muted")}>{presence}</div>
        </div>
        <ChevronRight size={16} className="shrink-0 text-faint transition-transform group-hover:translate-x-0.5 group-hover:text-muted" />
      </button>
      <div className="flex items-center justify-between border-t border-line px-4 py-2.5 text-[12px] text-faint">
        <span>{t("home.account")}</span>
        {count > 1 && <span>{t("home.accountsCount", { n: count })}</span>}
      </div>
    </Card>
  );
}

/**
 * Friends and their online status. Bloom has no social backend yet, so the
 * list is honestly empty; the panel is ready for real data when it exists.
 */
export function FriendsPanel() {
  const t = useT();
  return (
    <Card className="p-4">
      <PanelHeader
        title={t("home.friends")}
        meta={t("home.friendsOnline", { n: 0 })}
        action={<UserPlus size={16} className="text-faint" aria-hidden />}
      />
      <div className="mt-3.5 flex items-start gap-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-[10px] bg-white/[0.05] text-muted ring-1 ring-inset ring-white/[0.07]">
          <Users size={16} />
        </div>
        <div className="min-w-0">
          <div className="text-[13px] font-bold text-text">{t("home.friendsEmptyTitle")}</div>
          <p className="mt-0.5 text-[12px] leading-relaxed text-faint">{t("home.friendsEmptyBody")}</p>
        </div>
      </div>
    </Card>
  );
}

export function RecentActivity() {
  const t = useT();
  const locale = useLocale();
  const navigate = useNavigate();
  const instances = useInstancesStore((s) => s.instances);
  const stages = useSessionsStore((s) => Object.values(s.sessions).map((x) => x.stage).join(","));
  // Re-query when a game starts/stops or play time changes.
  const history = useAsync(() => api.listLaunchHistory(5), [instances, stages]);

  return (
    <Card className="p-4">
      <PanelHeader title={t("home.recentActivity")} />
      <div className="mt-3">
        {history.loading && !history.data ? (
          <div className="space-y-3 py-1">
            {Array.from({ length: 3 }, (_, i) => (
              <div key={i} className="flex items-center gap-3">
                <Skeleton className="h-9 w-9 rounded-[10px]" />
                <div className="flex-1 space-y-1.5">
                  <Skeleton className="h-3.5 w-2/3" />
                  <Skeleton className="h-3 w-1/2" />
                </div>
              </div>
            ))}
          </div>
        ) : !history.data?.length ? (
          <p className="py-2 text-[12.5px] leading-relaxed text-faint">{t("home.noSessions")}</p>
        ) : (
          <ul className="-mx-2">
            {history.data.map((r, n) => {
              const inst = instances.find((i) => i.id === r.instanceId);
              const status =
                r.endedAt === null
                  ? t("home.inProgress")
                  : r.crashed
                    ? t("home.endedCrash")
                    : formatDuration(r.endedAt - r.startedAt);
              return (
                <motion.li key={r.id} {...staggerItem(n, 0.3)}>
                  <button
                    type="button"
                    disabled={!inst}
                    onClick={() => navigate(`/instances/${r.instanceId}`)}
                    className="group flex w-full items-center gap-3 rounded-[10px] px-2 py-2 text-left transition-colors duration-200 hover:bg-white/[0.04] disabled:cursor-default disabled:hover:bg-transparent"
                  >
                    {r.crashed ? (
                      <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-[10px] bg-error/12 text-error">
                        <AlertTriangle size={16} />
                      </div>
                    ) : (
                      <InstanceIcon icon={inst?.icon ?? "sprout"} size={36} />
                    )}
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-[13px] font-bold text-text">{r.instanceName ?? r.versionId}</div>
                      <div className="truncate text-[11.5px] font-medium text-faint">
                        {/* The raw version id ("fabric-loader-0.20.1-26.3") is noise; show loader + game version. */}
                        {inst ? `${loaderName(inst.loader)} ${inst.versionId}` : r.versionId} ·{" "}
                        {relativeTime(r.startedAt, t, locale)}
                      </div>
                    </div>
                    <span
                      className={cn(
                        "shrink-0 rounded-md px-1.5 py-0.5 text-[11px] font-semibold tabular-nums",
                        r.endedAt === null
                          ? "bg-primary-soft text-primary"
                          : r.crashed
                            ? "bg-error/12 text-error"
                            : "bg-white/[0.05] text-muted",
                      )}
                    >
                      {status}
                    </span>
                  </button>
                </motion.li>
              );
            })}
          </ul>
        )}
      </div>
    </Card>
  );
}
