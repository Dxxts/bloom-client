import { useT } from "@/i18n";
import { SettingRow, Toggle } from "@/components/ui/Form";
import { Segmented, Select } from "@/components/ui/Select";
import type { Language, LauncherVisibility, ReducedMotion, Theme } from "@/types";
import { SettingsGroup, type SectionProps } from "./shared";

export function GeneralSection({ draft, update }: SectionProps) {
  const t = useT();
  return (
    <div className="space-y-6">
      <SettingsGroup title={t("settings.tab.general")}>
        <SettingRow title={t("settings.language")} description={t("settings.languageHint")}>
          <Select<Language>
            value={draft.language}
            onChange={(v) => update({ language: v })}
            label={t("settings.language")}
            className="w-48"
            align="right"
            options={[
              { value: "en", label: "English" },
              { value: "it", label: "Italiano" },
            ]}
          />
        </SettingRow>
        <SettingRow title={t("settings.theme")} description={t("settings.themeHint")}>
          <Segmented<Theme>
            value={draft.theme}
            onChange={(v) => update({ theme: v })}
            label={t("settings.theme")}
            layoutId="theme-seg"
            options={[
              { value: "dark", label: t("settings.theme.dark") },
              { value: "midnight", label: t("settings.theme.midnight") },
            ]}
          />
        </SettingRow>
        <SettingRow title={t("settings.reducedMotion")} description={t("settings.reducedMotionHint")}>
          <Segmented<ReducedMotion>
            value={draft.reducedMotion}
            onChange={(v) => update({ reducedMotion: v })}
            label={t("settings.reducedMotion")}
            layoutId="motion-seg"
            options={[
              { value: "system", label: t("settings.motion.system") },
              { value: "on", label: t("settings.motion.on") },
              { value: "off", label: t("settings.motion.off") },
            ]}
          />
        </SettingRow>
      </SettingsGroup>

      <SettingsGroup title={t("settings.behavior")}>
        <SettingRow title={t("settings.launchOnStartup")} description={t("settings.launchOnStartupHint")}>
          <Toggle checked={draft.launchOnStartup} onChange={(v) => update({ launchOnStartup: v })} label={t("settings.launchOnStartup")} />
        </SettingRow>
        <SettingRow title={t("settings.minimizeToTray")} description={t("settings.minimizeToTrayHint")}>
          <Toggle checked={draft.minimizeToTray} onChange={(v) => update({ minimizeToTray: v })} label={t("settings.minimizeToTray")} />
        </SettingRow>
        <SettingRow title={t("settings.notifications")} description={t("settings.notificationsHint")}>
          <Toggle checked={draft.notifications} onChange={(v) => update({ notifications: v })} label={t("settings.notifications")} />
        </SettingRow>
        <SettingRow title={t("settings.visibility")} description={t("settings.visibilityHint")}>
          <Select<LauncherVisibility>
            value={draft.launcherVisibilityOnLaunch}
            onChange={(v) => update({ launcherVisibilityOnLaunch: v })}
            label={t("settings.visibility")}
            className="w-48"
            align="right"
            options={[
              { value: "keep", label: t("settings.visibility.keep") },
              { value: "minimize", label: t("settings.visibility.minimize") },
              { value: "hide", label: t("settings.visibility.hide") },
            ]}
          />
        </SettingRow>
      </SettingsGroup>
    </div>
  );
}
