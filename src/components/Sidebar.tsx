import { NavLink, useLocation } from "react-router";
import { motion } from "motion/react";
import {
  Boxes,
  Gamepad2,
  House,
  ImageIcon,
  PanelLeftClose,
  PanelLeftOpen,
  Puzzle,
  Settings,
  Users,
  type LucideIcon,
} from "lucide-react";
import { useT, type TranslationKey } from "@/i18n";
import { selectActiveAccount, useAccountsStore } from "@/stores/accountsStore";
import { useAppStore } from "@/stores/appStore";
import { isActive, useSessionsStore } from "@/stores/sessionsStore";
import { cn } from "@/utils/format";
import { PlayerHead } from "./PlayerHead";
import { Tooltip } from "./ui/Tooltip";

type NavItem = { to: string; label: TranslationKey; icon: LucideIcon };

const primaryItems: NavItem[] = [
  { to: "/", label: "nav.home", icon: House },
  { to: "/play", label: "nav.play", icon: Gamepad2 },
  { to: "/instances", label: "nav.instances", icon: Boxes },
  { to: "/mods", label: "nav.mods", icon: Puzzle },
  { to: "/resource-packs", label: "nav.resourcePacks", icon: ImageIcon },
];

const secondaryItems: NavItem[] = [
  { to: "/accounts", label: "nav.accounts", icon: Users },
  { to: "/settings", label: "nav.settings", icon: Settings },
];

const EXPANDED = 216;
const COLLAPSED = 68;

function NavEntry({ item, current, collapsed }: { item: NavItem; current: boolean; collapsed: boolean }) {
  const t = useT();
  const Icon = item.icon;
  const link = (
    <NavLink
      to={item.to}
      end={item.to === "/"}
      className={cn(
        "group relative flex h-10 items-center gap-3 rounded-xl px-[13px] text-[13.5px] font-semibold outline-none transition-colors duration-200 focus-visible:ring-2 focus-visible:ring-white/20",
        // No background: the current page is marked only by a brighter icon.
        current ? "text-white" : "text-muted/70 hover:text-text",
      )}
      aria-label={collapsed ? t(item.label) : undefined}
    >
      <Icon
        size={18}
        strokeWidth={current ? 2.2 : 1.9}
        className={cn(
          "relative shrink-0 transition-[scale,filter] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] group-hover:scale-110",
          current && "drop-shadow-[0_0_8px_rgb(255_255_255/0.35)]",
        )}
      />
      <motion.span
        initial={false}
        animate={{ opacity: collapsed ? 0 : 1 }}
        transition={{ duration: 0.12 }}
        className="relative whitespace-nowrap"
      >
        {t(item.label)}
      </motion.span>
    </NavLink>
  );
  return collapsed ? (
    <Tooltip content={t(item.label)} side="right" className="w-full">
      <div className="w-full">{link}</div>
    </Tooltip>
  ) : (
    <div>{link}</div>
  );
}

export function Sidebar() {
  const t = useT();
  const location = useLocation();
  const collapsed = useAppStore((s) => s.sidebarCollapsed);
  const toggle = useAppStore((s) => s.toggleSidebar);
  const account = useAccountsStore(selectActiveAccount);
  const inGame = useSessionsStore((s) => Object.values(s.sessions).some((x) => isActive(x) && x.stage === "running"));

  const isCurrent = (to: string) => (to === "/" ? location.pathname === "/" : location.pathname.startsWith(to));

  const statusColor = !account
    ? "bg-faint"
    : account.status === "valid"
      ? "bg-primary"
      : account.status === "refreshable"
        ? "bg-warning"
        : "bg-error";
  const statusLabel = !account
    ? import.meta.env.DEV
      ? t("home.offlineDev")
      : t("home.notSignedIn")
    : inGame
      ? t("home.presence.inGame")
      : t(`accounts.status.${account.status}` as TranslationKey);

  return (
    <motion.aside
      initial={false}
      animate={{ width: collapsed ? COLLAPSED : EXPANDED }}
      transition={{ type: "spring", stiffness: 380, damping: 38 }}
      className="relative flex shrink-0 flex-col bg-bg"
    >
      <nav className="flex flex-1 flex-col px-3 pt-3" aria-label="Main">
        <div className="space-y-1">
          {primaryItems.map((item) => (
            <NavEntry key={item.to} item={item} current={isCurrent(item.to)} collapsed={collapsed} />
          ))}
        </div>
        <div className="mx-auto my-3 h-px w-8 bg-white/[0.07]" />
        <div className="space-y-1">
          {secondaryItems.map((item) => (
            <NavEntry key={item.to} item={item} current={isCurrent(item.to)} collapsed={collapsed} />
          ))}
        </div>
      </nav>

      <div className="mx-3 h-px bg-white/[0.06]" />
      <div className={cn("flex gap-1 p-3", collapsed ? "flex-col items-center" : "items-center")}>
        <NavLink
          to="/accounts"
          className="flex min-w-0 flex-1 items-center gap-2.5 overflow-hidden rounded-xl p-1.5 transition-colors duration-200 hover:bg-white/[0.04]"
          aria-label={account?.username ?? t("home.notSignedIn")}
        >
          <div className="relative shrink-0">
            <PlayerHead uuid={account?.uuid ?? null} name={account?.username ?? null} size={30} />
            {account && (
              <span className={cn("absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full ring-2 ring-bg", statusColor)} />
            )}
          </div>
          {!collapsed && (
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.18, delay: 0.08 }}
              className="min-w-0 flex-1"
            >
              <div className="truncate text-[13px] font-semibold leading-tight text-text">
                {account?.username ?? t("home.notSignedIn")}
              </div>
              <div className="mt-0.5 truncate text-[11.5px] leading-tight text-faint">{statusLabel}</div>
            </motion.div>
          )}
        </NavLink>
        <button
          type="button"
          onClick={toggle}
          aria-label={collapsed ? t("nav.expand") : t("nav.collapse")}
          title={collapsed ? t("nav.expand") : t("nav.collapse")}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-faint transition-colors duration-200 hover:bg-white/[0.05] hover:text-text"
        >
          {collapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
        </button>
      </div>
    </motion.aside>
  );
}
