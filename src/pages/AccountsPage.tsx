import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { motion } from "motion/react";
import { Check, CircleDot, KeyRound, LogOut, RefreshCw, ShieldCheck, TriangleAlert } from "lucide-react";
import { useLocale, useT, type TranslationKey } from "@/i18n";
import { PlayerHead } from "@/components/PlayerHead";
import { Button } from "@/components/ui/Button";
import { staggerItem } from "@/components/ui/Reveal";
import { Badge, Card, PageHeader, Skeleton } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/Feedback";
import { Modal } from "@/components/ui/Modal";
import { Spinner } from "@/components/ui/Spinner";
import { api } from "@/services/api";
import { isCancelled, reportError } from "@/services/errors";
import { EVENTS, on } from "@/services/events";
import { useAccountsStore } from "@/stores/accountsStore";
import { useAppStore } from "@/stores/appStore";
import { toast, useUiStore } from "@/stores/uiStore";
import type { Account, AuthStep } from "@/types";
import { cn, relativeTime } from "@/utils/format";
import { Page } from "@/layouts/Page";
import { SteveHead, MicrosoftLogo } from "@/components/GameIcons";

const STEPS: AuthStep[] = ["browser", "microsoft", "xbox", "minecraft", "profile"];

function LoginModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useT();
  const [step, setStep] = useState<AuthStep | null>(null);

  useEffect(() => {
    if (!open) return;
    setStep(null);
    const sub = on<{ step: AuthStep }>(EVENTS.authProgress, (p) => setStep(p.step));
    return () => void sub.then((u) => u());
  }, [open]);

  const currentIndex = step ? STEPS.indexOf(step) : -1;

  return (
    <Modal
      open={open}
      onClose={() => {
        void api.cancelLogin();
        onClose();
      }}
      title={t("login.title")}
      description={step === null ? t("login.starting") : t("login.hint")}
      width={460}
      footer={
        <Button
          variant="ghost"
          onClick={() => {
            void api.cancelLogin();
            onClose();
          }}
        >
          {t("common.cancel")}
        </Button>
      }
    >
      <ol className="space-y-1 pb-2">
        {STEPS.map((s, i) => {
          const done = currentIndex > i || step === "done";
          const current = currentIndex === i && step !== "done";
          return (
            <li
              key={s}
              className={cn(
                "flex items-center gap-3 rounded-lg px-3 py-2.5 text-[13.5px] transition-colors",
                current && "bg-surface-hover",
              )}
            >
              <span className="flex h-6 w-6 items-center justify-center">
                {done ? (
                  <Check size={16} className="text-primary" />
                ) : current ? (
                  <Spinner size={16} className="text-primary" />
                ) : (
                  <CircleDot size={14} className="text-faint" />
                )}
              </span>
              <span className={cn(done ? "text-muted" : current ? "font-semibold text-text" : "text-faint")}>
                {t(`login.step.${s}` as TranslationKey)}
              </span>
            </li>
          );
        })}
      </ol>
    </Modal>
  );
}

function AccountRow({ account, index }: { account: Account; index: number }) {
  const t = useT();
  const locale = useLocale();
  const confirm = useUiStore((s) => s.confirm);
  const load = useAccountsStore((s) => s.load);
  const [refreshing, setRefreshing] = useState(false);
  const tone = account.status === "valid" ? "primary" : account.status === "refreshable" ? "warning" : "error";

  const refresh = async () => {
    setRefreshing(true);
    try {
      await api.refreshAccount(account.uuid);
      toast.success(t("accounts.refreshed"));
    } catch (e) {
      reportError(e);
    } finally {
      setRefreshing(false);
      void load();
    }
  };

  const signOut = async () => {
    const ok = await confirm({
      title: t("accounts.signOut"),
      message: t("accounts.signOutConfirm", { name: account.username }),
      confirmLabel: t("accounts.signOut"),
      cancelLabel: t("common.cancel"),
      danger: true,
    });
    if (!ok) return;
    try {
      await api.logoutAccount(account.uuid);
      await load();
    } catch (e) {
      reportError(e);
    }
  };

  return (
    <motion.div layout {...staggerItem(index)}>
      <Card className={cn("flex items-center gap-4 p-5", account.active && "!ring-primary/35")}>
        <PlayerHead uuid={account.uuid} name={account.username} size={52} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-[16px] font-bold text-text">{account.username}</span>
            {account.active && <Badge tone="primary">{t("accounts.active")}</Badge>}
            <Badge tone={tone}>{t(`accounts.status.${account.status}` as TranslationKey)}</Badge>
          </div>
          <div className="mt-1 flex items-center gap-1.5 text-[12px] font-medium text-muted">
            <MicrosoftLogo size={12} /> Microsoft · Minecraft: Java Edition
          </div>
          <div className="mt-0.5 font-mono text-[11px] text-faint" data-selectable>
            {account.uuid}
          </div>
          {account.lastUsedAt && (
            <div className="mt-0.5 text-[12px] text-muted">
              {t("accounts.lastUsed", { time: relativeTime(account.lastUsedAt, t, locale) })}
            </div>
          )}
        </div>
        <div className="flex items-center gap-2">
          {!account.active && (
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                void api
                  .setActiveAccount(account.uuid)
                  .then(load)
                  .catch(reportError)
              }
            >
              {t("accounts.setActive")}
            </Button>
          )}
          <Button
            size="sm"
            variant="ghost"
            icon={<RefreshCw size={14} className={refreshing ? "animate-spin" : ""} />}
            onClick={() => void refresh()}
            disabled={refreshing}
          >
            {t("accounts.refresh")}
          </Button>
          <Button size="sm" variant="danger" icon={<LogOut size={14} />} onClick={() => void signOut()}>
            {t("accounts.signOut")}
          </Button>
        </div>
      </Card>
    </motion.div>
  );
}

export function AccountsPage() {
  const t = useT();
  const navigate = useNavigate();
  const accounts = useAccountsStore((s) => s.accounts);
  const loaded = useAccountsStore((s) => s.loaded);
  const load = useAccountsStore((s) => s.load);
  const configured = useAppStore((s) => s.info?.microsoftAuthConfigured ?? false);
  const [loggingIn, setLoggingIn] = useState(false);

  const login = async () => {
    setLoggingIn(true);
    try {
      const account = await api.loginMicrosoft();
      toast.success(t("accounts.signedIn", { name: account.username }));
      await load();
    } catch (e) {
      if (!isCancelled(e)) {
        useUiStore.getState().showError({ title: t("login.title"), message: e instanceof Error ? e.message : String(e) });
      }
    } finally {
      setLoggingIn(false);
    }
  };

  return (
    <Page art={0} narrow>
      <PageHeader
        title={t("accounts.title")}
        subtitle={t("accounts.subtitle")}
        actions={
          <Button variant="primary" icon={<MicrosoftLogo size={15} />} onClick={() => void login()} disabled={!configured || loggingIn}>
            {t("accounts.add")}
          </Button>
        }
      />

      {!configured && (
        <Card className="mb-5 flex items-start gap-4 p-5 !ring-warning/25">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-warning/12 text-warning">
            <TriangleAlert size={19} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="text-[14px] font-bold text-text">{t("accounts.notConfiguredTitle")}</div>
            <p className="mt-1 text-[13px] leading-relaxed text-muted">{t("accounts.notConfiguredBody")}</p>
            <Button size="sm" variant="secondary" icon={<KeyRound size={14} />} className="mt-3" onClick={() => navigate("/settings?tab=advanced")}>
              {t("accounts.goToSettings")}
            </Button>
          </div>
        </Card>
      )}

      {!loaded ? (
        <Skeleton className="h-24 rounded-2xl" />
      ) : accounts.length === 0 ? (
        <Card>
          <EmptyState
            icon={<SteveHead size={30} />}
            title={t("accounts.emptyTitle")}
            body={t("accounts.emptyBody")}
            action={
              configured && (
                <Button variant="primary" icon={<MicrosoftLogo size={15} />} onClick={() => void login()}>
                  {t("accounts.add")}
                </Button>
              )
            }
          />
        </Card>
      ) : (
        <div className="space-y-3">
          {accounts.map((a, n) => (
            <AccountRow key={a.uuid} account={a} index={n} />
          ))}
        </div>
      )}

      <p className="mt-6 flex items-center gap-2 text-[12.5px] text-faint">
        <ShieldCheck size={14} className="text-primary" /> {t("accounts.security")}
      </p>

      <LoginModal open={loggingIn} onClose={() => setLoggingIn(false)} />
    </Page>
  );
}
