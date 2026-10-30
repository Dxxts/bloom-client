import { useEffect, useMemo, useState } from "react";
import { useT, type TranslationKey } from "@/i18n";
import { Field, Input, Slider, TextArea, Toggle } from "@/components/ui/Form";
import { Select, type SelectOption } from "@/components/ui/Select";
import { Badge } from "@/components/ui/Card";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { useAppStore } from "@/stores/appStore";
import { EngineIcon } from "@/components/EngineIcon";
import type { EngineKind, Instance, InstanceInput, LoaderKind } from "@/types";
import { cn, formatMemory } from "@/utils/format";
import { INSTANCE_ICONS } from "./InstanceIcon";
import { LoaderIcon } from "@/components/GameIcons";

export function emptyInput(versionId = ""): InstanceInput {
  return {
    name: "",
    versionId,
    loader: "vanilla",
    loaderVersion: null,
    engine: "java",
    javaPath: null,
    minRamMb: null,
    maxRamMb: null,
    resolutionWidth: null,
    resolutionHeight: null,
    fullscreen: null,
    jvmArgs: null,
    icon: "sprout",
  };
}

export function inputFromInstance(i: Instance): InstanceInput {
  return {
    name: i.name,
    versionId: i.versionId,
    loader: i.loader,
    loaderVersion: i.loaderVersion,
    engine: i.engine,
    javaPath: i.javaPath,
    minRamMb: i.minRamMb,
    maxRamMb: i.maxRamMb,
    resolutionWidth: i.resolutionWidth,
    resolutionHeight: i.resolutionHeight,
    fullscreen: i.fullscreen,
    jvmArgs: i.jvmArgs,
    icon: i.icon,
  };
}

/** Shared create/edit form for instances. */
export function InstanceForm({
  value,
  onChange,
  showAdvanced = true,
}: {
  value: InstanceInput;
  onChange: (v: InstanceInput) => void;
  showAdvanced?: boolean;
}) {
  const t = useT();
  const settings = useAppStore((s) => s.settings)!;
  const totalMemory = useAppStore((s) => s.info?.totalMemoryMb ?? 8192);
  const [showSnapshots, setShowSnapshots] = useState(false);
  const versions = useAsync(() => api.listVersions(false), []);
  const java = useAsync(() => api.listJavaRuntimes(), []);
  const loaders = useAsync(() => api.listLoaders(), []);
  const engines = useAsync(() => api.listRenderEngines(), []);
  const set = <K extends keyof InstanceInput>(k: K, v: InstanceInput[K]) => onChange({ ...value, [k]: v });

  // Default to the latest release once versions are loaded.
  useEffect(() => {
    if (!value.versionId && versions.data?.latestRelease) set("versionId", versions.data.latestRelease);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versions.data]);

  useEffect(() => {
    const current = versions.data?.versions.find((v) => v.id === value.versionId);
    if (current && current.releaseType !== "release") setShowSnapshots(true);
  }, [versions.data, value.versionId]);

  // Version options are `<engine>:<version>`, in two separate groups: the
  // Rust engine (only the versions it is built for) and the Java renderer.
  const versionOptions = useMemo<SelectOption<string>[]>(() => {
    const list = versions.data?.versions ?? [];
    const badges = (id: string) => {
      const v = list.find((x) => x.id === id);
      return (
        <span className="flex gap-1">
          {id === versions.data?.latestRelease && <Badge tone="primary">{t("play.latest")}</Badge>}
          {v?.installed && <Badge>{t("play.installed")}</Badge>}
          {v && v.releaseType !== "release" && <Badge tone="warning">{t(`type.${v.releaseType}` as TranslationKey)}</Badge>}
        </span>
      );
    };
    const rust = engines.data?.find((e) => e.kind === "rust");
    const rustOptions: SelectOption<string>[] = (rust?.supportedVersions ?? []).map((id) => ({
      value: `rust:${id}`,
      label: id,
      icon: <EngineIcon engine="rust" />,
      group: "rust",
      groupLabel: (
        <>
          <EngineIcon engine="rust" size={13} />
          {t("editor.groupRust")}
        </>
      ),
      description: rust?.available ? t("editor.rustDescription") : t("editor.rustUnavailable"),
      disabled: !rust?.available,
      badge: badges(id),
    }));
    const javaOptions: SelectOption<string>[] = list
      .filter((v) => showSnapshots || v.releaseType === "release" || (value.engine === "java" && v.id === value.versionId))
      .map((v) => ({
        value: `java:${v.id}`,
        label: v.id,
        icon: <EngineIcon engine="java" />,
        group: "java",
        groupLabel: (
          <>
            <EngineIcon engine="java" size={13} />
            {t("editor.groupJava")}
          </>
        ),
        badge: badges(v.id),
      }));
    return [...rustOptions, ...javaOptions];
  }, [versions.data, engines.data, showSnapshots, value.engine, value.versionId, t]);

  const selectVersion = (option: string) => {
    const split = option.indexOf(":");
    const engine = option.slice(0, split) as EngineKind;
    const versionId = option.slice(split + 1);
    // The Rust engine is a Fabric mod; leaving it goes back to vanilla.
    const loader: LoaderKind = engine === "rust" ? "fabric" : value.engine === "rust" ? "vanilla" : value.loader;
    onChange({ ...value, engine, versionId, loader, loaderVersion: engine === value.engine ? value.loaderVersion : null });
  };

  const javaOptions = useMemo<SelectOption<string>[]>(
    () => [
      { value: "", label: t("editor.javaAuto") },
      ...(java.data ?? []).map((j) => ({
        value: j.path,
        label: `Java ${j.major} · ${j.vendor || j.version}`,
        description: j.path,
      })),
    ],
    [java.data, t],
  );

  const memoryOverride = value.minRamMb !== null || value.maxRamMb !== null;
  const resolutionOverride = value.resolutionWidth !== null || value.resolutionHeight !== null;
  const maxSlider = Math.max(2048, Math.floor(totalMemory / 512) * 512);

  return (
    <div className="space-y-6 pb-2">
      <Field label={t("editor.name")}>
        <Input
          value={value.name}
          maxLength={48}
          placeholder={t("editor.namePlaceholder")}
          onChange={(e) => set("name", e.target.value)}
          data-autofocus
        />
      </Field>

      <Field label={t("editor.icon")}>
        <div className="flex flex-wrap gap-1.5">
          {Object.entries(INSTANCE_ICONS).map(([key, Icon]) => (
            <button
              key={key}
              type="button"
              aria-label={key}
              aria-pressed={value.icon === key}
              onClick={() => set("icon", key)}
              className={cn(
                "flex h-9 w-9 items-center justify-center rounded-lg border transition-colors",
                value.icon === key
                  ? "border-primary/60 bg-primary-soft text-primary"
                  : "border-line bg-bg-2 text-muted hover:border-line-strong hover:text-text",
              )}
            >
              <Icon size={17} />
            </button>
          ))}
        </div>
      </Field>

      <div className="grid grid-cols-[1fr_auto] items-end gap-3">
        <Field label={t("editor.version")}>
          <Select
            value={value.versionId ? `${value.engine}:${value.versionId}` : null}
            options={versionOptions}
            onChange={selectVersion}
            label={t("editor.version")}
            placeholder={versions.loading ? t("editor.loadingVersions") : "—"}
          />
        </Field>
        <label className="flex h-10 items-center gap-2.5 text-[12.5px] font-semibold text-muted">
          <Toggle checked={showSnapshots} onChange={setShowSnapshots} label={t("editor.showSnapshots")} />
          {t("editor.showSnapshots")}
        </label>
      </div>

      <Field label={t("editor.loader")} hint={value.engine === "rust" ? t("editor.loaderByEngine") : undefined}>
        <div className="grid grid-cols-4 gap-2">
          {(loaders.data ?? []).map((l) => (
            <button
              key={l.kind}
              type="button"
              disabled={!l.available || value.engine === "rust"}
              aria-pressed={value.loader === l.kind}
              onClick={() => set("loader", l.kind as LoaderKind)}
              className={cn(
                "flex h-[68px] flex-col items-center justify-center gap-1 rounded-xl border text-[13px] font-semibold transition-colors",
                value.loader === l.kind
                  ? "border-primary/60 bg-primary-soft text-primary"
                  : "border-line bg-bg-2 text-text hover:border-line-strong",
                (!l.available || (value.engine === "rust" && value.loader !== l.kind)) && "cursor-not-allowed opacity-50 hover:border-line",
              )}
            >
              <LoaderIcon loader={l.kind} size={20} />
              {l.name}
              {!l.available && <span className="text-[10.5px] font-medium text-faint">{t("editor.loaderSoon")}</span>}
            </button>
          ))}
        </div>
      </Field>

      {showAdvanced && (
        <>
          <div className="space-y-3 rounded-xl border border-line bg-bg-2 p-4">
            <label className="flex items-center justify-between gap-4">
              <span className="text-[13px] font-semibold text-text">{t("editor.overrideMemory")}</span>
              <Toggle
                checked={memoryOverride}
                label={t("editor.overrideMemory")}
                onChange={(on) =>
                  onChange({
                    ...value,
                    minRamMb: on ? settings.minRamMb : null,
                    maxRamMb: on ? settings.maxRamMb : null,
                  })
                }
              />
            </label>
            {memoryOverride ? (
              <div className="space-y-3 pt-1">
                <div className="text-[12px] text-muted">{t("editor.minRam")}</div>
                <Slider
                  label={t("editor.minRam")}
                  min={512}
                  max={maxSlider}
                  step={256}
                  value={value.minRamMb ?? settings.minRamMb}
                  format={formatMemory}
                  onChange={(v) =>
                    onChange({ ...value, minRamMb: v, maxRamMb: Math.max(v, value.maxRamMb ?? settings.maxRamMb) })
                  }
                />
                <div className="text-[12px] text-muted">{t("editor.maxRam")}</div>
                <Slider
                  label={t("editor.maxRam")}
                  min={512}
                  max={maxSlider}
                  step={256}
                  value={value.maxRamMb ?? settings.maxRamMb}
                  format={formatMemory}
                  onChange={(v) =>
                    onChange({ ...value, maxRamMb: v, minRamMb: Math.min(v, value.minRamMb ?? settings.minRamMb) })
                  }
                />
              </div>
            ) : (
              <p className="text-[12px] text-faint">
                {t("common.inherited", {
                  value: `${formatMemory(settings.minRamMb)} – ${formatMemory(settings.maxRamMb)}`,
                })}
              </p>
            )}
          </div>

          <div className="space-y-3 rounded-xl border border-line bg-bg-2 p-4">
            <label className="flex items-center justify-between gap-4">
              <span className="text-[13px] font-semibold text-text">{t("editor.overrideResolution")}</span>
              <Toggle
                checked={resolutionOverride}
                label={t("editor.overrideResolution")}
                onChange={(on) =>
                  onChange({
                    ...value,
                    resolutionWidth: on ? settings.resolutionWidth : null,
                    resolutionHeight: on ? settings.resolutionHeight : null,
                  })
                }
              />
            </label>
            {resolutionOverride ? (
              <div className="grid grid-cols-2 gap-3">
                <Input
                  type="number"
                  aria-label={t("editor.width")}
                  value={value.resolutionWidth ?? ""}
                  onChange={(e) => set("resolutionWidth", Number(e.target.value) || null)}
                />
                <Input
                  type="number"
                  aria-label={t("editor.height")}
                  value={value.resolutionHeight ?? ""}
                  onChange={(e) => set("resolutionHeight", Number(e.target.value) || null)}
                />
              </div>
            ) : (
              <p className="text-[12px] text-faint">
                {t("common.inherited", { value: `${settings.resolutionWidth} × ${settings.resolutionHeight}` })}
              </p>
            )}
            <label className="flex items-center justify-between gap-4 pt-1">
              <span className="text-[13px] text-muted">{t("editor.fullscreen")}</span>
              <Toggle
                checked={value.fullscreen ?? settings.fullscreen}
                label={t("editor.fullscreen")}
                onChange={(v) => set("fullscreen", v === settings.fullscreen ? null : v)}
              />
            </label>
          </div>

          <Field label={t("editor.java")}>
            <Select
              value={value.javaPath ?? ""}
              options={javaOptions}
              onChange={(v) => set("javaPath", v || null)}
              label={t("editor.java")}
            />
          </Field>

          <Field label={t("editor.jvmArgs")} hint={t("editor.jvmArgsHint")}>
            <TextArea
              rows={2}
              spellCheck={false}
              value={value.jvmArgs ?? ""}
              placeholder={settings.jvmArgs || "-XX:+UseZGC"}
              onChange={(e) => set("jvmArgs", e.target.value || null)}
            />
          </Field>
        </>
      )}
    </div>
  );
}
