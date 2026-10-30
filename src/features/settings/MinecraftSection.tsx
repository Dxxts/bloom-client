import { useT } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { Input, SettingRow, Slider, TextArea, Toggle } from "@/components/ui/Form";
import { Select } from "@/components/ui/Select";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { reportError } from "@/services/errors";
import { useAppStore } from "@/stores/appStore";
import { formatMemory } from "@/utils/format";
import { SettingsGroup, type SectionProps } from "./shared";

export function DirectoryInput({
  value,
  placeholder,
  onChange,
  label,
}: {
  value: string | null;
  placeholder: string;
  onChange: (v: string | null) => void;
  label: string;
}) {
  const t = useT();
  return (
    <div className="flex gap-2">
      <Input
        value={value ?? ""}
        placeholder={placeholder}
        aria-label={label}
        onChange={(e) => onChange(e.target.value || null)}
        className="font-mono text-[12px]"
        spellCheck={false}
      />
      <Button
        variant="outline"
        onClick={() =>
          void api
            .pickDirectory()
            .then((d) => d && onChange(d))
            .catch(reportError)
        }
      >
        {t("common.browse")}
      </Button>
      {value && (
        <Button variant="ghost" onClick={() => onChange(null)}>
          {t("common.reset")}
        </Button>
      )}
    </div>
  );
}

export function MinecraftSection({ draft, update }: SectionProps) {
  const t = useT();
  const info = useAppStore((s) => s.info);
  const total = info?.totalMemoryMb ?? 8192;
  const maxSlider = Math.max(2048, Math.floor(total / 512) * 512);
  const java = useAsync(() => api.listJavaRuntimes(), []);

  return (
    <div className="space-y-6">
      <SettingsGroup title={t("settings.memory")} description={t("settings.memoryHint", { total: formatMemory(total) })}>
        <SettingRow title={t("settings.minRam")} stacked>
          <Slider
            label={t("settings.minRam")}
            min={512}
            max={maxSlider}
            step={256}
            value={draft.minRamMb}
            format={formatMemory}
            onChange={(v) => update({ minRamMb: v, maxRamMb: Math.max(v, draft.maxRamMb) })}
          />
        </SettingRow>
        <SettingRow title={t("settings.maxRam")} stacked>
          <Slider
            label={t("settings.maxRam")}
            min={512}
            max={maxSlider}
            step={256}
            value={draft.maxRamMb}
            format={formatMemory}
            onChange={(v) => update({ maxRamMb: v, minRamMb: Math.min(v, draft.minRamMb) })}
          />
        </SettingRow>
      </SettingsGroup>

      <SettingsGroup title={t("settings.javaRuntime")}>
        <SettingRow title={t("settings.javaRuntime")} description={t("settings.javaRuntimeHint")} stacked>
          <Select
            value={draft.javaPath ?? ""}
            onChange={(v) => update({ javaPath: v || null })}
            label={t("settings.javaRuntime")}
            options={[
              { value: "", label: t("editor.javaAuto") },
              ...(java.data ?? []).map((j) => ({
                value: j.path,
                label: `Java ${j.major} · ${j.vendor || j.version}`,
                description: j.path,
              })),
            ]}
          />
        </SettingRow>
        <SettingRow title={t("settings.jvmArgs")} description={t("settings.jvmArgsHint")} stacked>
          <TextArea
            rows={3}
            spellCheck={false}
            value={draft.jvmArgs}
            placeholder="-XX:+UseZGC -XX:+ZGenerational"
            onChange={(e) => update({ jvmArgs: e.target.value })}
          />
        </SettingRow>
      </SettingsGroup>

      <SettingsGroup title={t("settings.resolution")}>
        <SettingRow title={t("settings.resolution")} description={t("settings.resolutionHint")}>
          <div className="flex items-center gap-2">
            <Input
              type="number"
              className="w-24 text-center"
              aria-label={t("editor.width")}
              value={draft.resolutionWidth}
              onChange={(e) => update({ resolutionWidth: Number(e.target.value) })}
            />
            <span className="text-faint">×</span>
            <Input
              type="number"
              className="w-24 text-center"
              aria-label={t("editor.height")}
              value={draft.resolutionHeight}
              onChange={(e) => update({ resolutionHeight: Number(e.target.value) })}
            />
          </div>
        </SettingRow>
        <SettingRow title={t("settings.fullscreen")} description={t("settings.fullscreenHint")}>
          <Toggle checked={draft.fullscreen} onChange={(v) => update({ fullscreen: v })} label={t("settings.fullscreen")} />
        </SettingRow>
      </SettingsGroup>

      <SettingsGroup title={t("settings.gameDirectory")}>
        <SettingRow title={t("settings.gameDirectory")} description={t("settings.gameDirectoryHint")} stacked>
          <DirectoryInput
            value={draft.instancesDirectory}
            placeholder={info?.instancesDir ?? ""}
            onChange={(v) => update({ instancesDirectory: v })}
            label={t("settings.gameDirectory")}
          />
        </SettingRow>
      </SettingsGroup>
    </div>
  );
}
