import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import { AnimatePresence, motion } from "motion/react";
import { Coffee, Gamepad2, Info, Rocket, SlidersHorizontal, Wrench } from "lucide-react";
import { useT } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { PageHeader } from "@/components/ui/Card";
import { Tabs } from "@/components/ui/Feedback";
import { AboutSection } from "@/features/settings/AboutSection";
import { AdvancedSection } from "@/features/settings/AdvancedSection";
import { GeneralSection } from "@/features/settings/GeneralSection";
import { JavaSection } from "@/features/settings/JavaSection";
import { LauncherSection } from "@/features/settings/LauncherSection";
import { MinecraftSection } from "@/features/settings/MinecraftSection";
import { reportError } from "@/services/errors";
import { useAppStore } from "@/stores/appStore";
import { toast } from "@/stores/uiStore";
import type { Settings } from "@/types";
import { Page } from "@/layouts/Page";

type Tab = "general" | "minecraft" | "java" | "launcher" | "advanced" | "about";

// Fields changed from elsewhere in the app; never part of the draft diff.
const EXTERNAL: (keyof Settings)[] = ["selectedInstanceId", "showSnapshots"];

function comparable(s: Settings): string {
  const copy: Partial<Settings> = { ...s };
  for (const k of EXTERNAL) delete copy[k];
  return JSON.stringify(copy);
}

export function SettingsPage() {
  const t = useT();
  const [params, setParams] = useSearchParams();
  const tab = (params.get("tab") as Tab) || "general";
  const settings = useAppStore((s) => s.settings)!;
  const saveSettings = useAppStore((s) => s.saveSettings);
  const [draft, setDraft] = useState<Settings>(settings);
  const [saving, setSaving] = useState(false);

  const dirty = useMemo(() => comparable(draft) !== comparable(settings), [draft, settings]);

  // Follow external changes (e.g. reset) when there is nothing pending.
  useEffect(() => {
    if (!dirty) setDraft(settings);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings]);

  const update = (patch: Partial<Settings>) => setDraft((d) => ({ ...d, ...patch }));

  const save = async () => {
    setSaving(true);
    try {
      const current = useAppStore.getState().settings!;
      const merged = { ...draft };
      for (const k of EXTERNAL) (merged as Record<string, unknown>)[k] = current[k];
      const saved = await saveSettings(merged);
      setDraft(saved);
      toast.success(t("settings.saved"));
    } catch (e) {
      reportError(e);
    } finally {
      setSaving(false);
    }
  };

  const props = { draft, update };

  const saveBar = (
    <AnimatePresence>
      {dirty && (
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: 16 }}
          transition={{ type: "spring", stiffness: 420, damping: 34 }}
          className="sticky bottom-6 z-40 mt-6 flex items-center justify-between gap-4 rounded-2xl bg-overlay/90 px-5 py-3.5 shadow-2xl shadow-black/50 ring-1 ring-inset ring-white/10 backdrop-blur-xl"
        >
          <span className="text-[13.5px] font-semibold text-text">{t("settings.unsaved")}</span>
          <div className="flex gap-2">
            <Button size="sm" variant="ghost" onClick={() => setDraft(settings)}>
              {t("settings.discard")}
            </Button>
            <Button size="sm" variant="primary" loading={saving} onClick={() => void save()}>
              {t("common.save")}
            </Button>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );

  return (
    <Page art={3} narrow footer={saveBar}>
      <PageHeader title={t("settings.title")} />
      <div className="mb-6">
        <Tabs
          value={tab}
          onChange={(v) => setParams({ tab: v }, { replace: true })}
          layoutId="settings-tabs"
          tabs={[
            { value: "general", label: t("settings.tab.general"), icon: <SlidersHorizontal size={15} /> },
            { value: "minecraft", label: t("settings.tab.minecraft"), icon: <Gamepad2 size={15} /> },
            { value: "java", label: t("settings.tab.java"), icon: <Coffee size={15} /> },
            { value: "launcher", label: t("settings.tab.launcher"), icon: <Rocket size={15} /> },
            { value: "advanced", label: t("settings.tab.advanced"), icon: <Wrench size={15} /> },
            { value: "about", label: t("settings.tab.about"), icon: <Info size={15} /> },
          ]}
        />
      </div>

      {tab === "general" && <GeneralSection {...props} />}
      {tab === "minecraft" && <MinecraftSection {...props} />}
      {tab === "java" && <JavaSection {...props} />}
      {tab === "launcher" && <LauncherSection {...props} />}
      {tab === "advanced" && <AdvancedSection {...props} />}
      {tab === "about" && <AboutSection />}

    </Page>
  );
}
