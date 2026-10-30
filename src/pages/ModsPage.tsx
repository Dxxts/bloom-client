import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ChevronDown, FolderOpen, Info, Plus, Puzzle, Trash2 } from "lucide-react";
import { useLocale, useT } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { staggerItem } from "@/components/ui/Reveal";
import { Badge, Card, PageHeader, Skeleton } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/Feedback";
import { SearchInput, Toggle } from "@/components/ui/Form";
import { IconButton } from "@/components/ui/IconButton";
import { InstancePicker, usePickedInstance } from "@/features/instances/InstancePicker";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { errorMessage, reportError } from "@/services/errors";
import { useInstancesStore } from "@/stores/instancesStore";
import { toast, useUiStore } from "@/stores/uiStore";
import type { ModEntry } from "@/types";
import { cn, formatBytes, formatDate } from "@/utils/format";
import { Page } from "@/layouts/Page";

function ModRow({
  mod,
  instanceId,
  index,
  onChanged,
}: {
  mod: ModEntry;
  instanceId: string;
  index: number;
  onChanged: () => void;
}) {
  const t = useT();
  const locale = useLocale();
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  const confirm = useUiStore((s) => s.confirm);
  const title = mod.name ?? mod.fileName.replace(/\.jar(\.disabled)?$/i, "");

  const toggle = async (enabled: boolean) => {
    setBusy(true);
    try {
      await api.setModEnabled(instanceId, mod.fileName, enabled);
      onChanged();
    } catch (e) {
      reportError(e);
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    const ok = await confirm({
      title: t("common.delete"),
      message: t("mods.deleteConfirm", { name: title }),
      confirmLabel: t("common.delete"),
      cancelLabel: t("common.cancel"),
      danger: true,
    });
    if (!ok) return;
    try {
      await api.deleteMod(instanceId, mod.fileName);
      onChanged();
    } catch (e) {
      reportError(e);
    }
  };

  return (
    <motion.li {...staggerItem(index)} className={cn("transition-colors", !mod.enabled && "opacity-60")}>
      <div className="flex items-center gap-4 px-5 py-3">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white/[0.05] text-muted ring-1 ring-inset ring-white/[0.07]">
          <Puzzle size={18} />
        </div>
        <button type="button" className="min-w-0 flex-1 text-left" onClick={() => setExpanded((e) => !e)}>
          <div className="flex items-center gap-2">
            <span className="truncate text-[14px] font-bold text-text">{title}</span>
            {mod.version && <span className="shrink-0 font-mono text-[11.5px] text-faint">{mod.version}</span>}
            {mod.loader && <Badge className="capitalize">{mod.loader}</Badge>}
          </div>
          <div className="truncate text-[12.5px] text-muted">
            {mod.authors.length > 0 ? t("mods.by", { authors: mod.authors.join(", ") }) : mod.fileName}
          </div>
        </button>
        <ChevronDown
          size={15}
          className={cn("shrink-0 text-faint transition-transform", expanded && "rotate-180")}
          aria-hidden
        />
        <Toggle checked={mod.enabled} onChange={(v) => void toggle(v)} disabled={busy} label={title} />
        <IconButton size="sm" tone="danger" label={t("common.delete")} icon={<Trash2 size={15} />} onClick={() => void remove()} />
      </div>
      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            className="overflow-hidden"
          >
            <div className="mx-5 mb-4 space-y-3 rounded-xl bg-black/25 p-4 text-[12.5px] ring-1 ring-inset ring-white/[0.06]">
              {mod.description && <p className="leading-relaxed text-muted" data-selectable>{mod.description}</p>}
              <dl className="grid grid-cols-[110px_1fr] gap-x-4 gap-y-1.5">
                <dt className="text-faint">{t("mods.file")}</dt>
                <dd className="truncate font-mono text-text" data-selectable>{mod.fileName}</dd>
                {mod.modId && (
                  <>
                    <dt className="text-faint">{t("mods.id")}</dt>
                    <dd className="font-mono text-text">{mod.modId}</dd>
                  </>
                )}
                <dt className="text-faint">{t("mods.size")}</dt>
                <dd className="text-text">{formatBytes(mod.size)}</dd>
                <dt className="text-faint">{t("mods.modified")}</dt>
                <dd className="text-text">{formatDate(mod.modifiedAt, locale, true)}</dd>
              </dl>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.li>
  );
}

export function ModsPage() {
  const t = useT();
  const instances = useInstancesStore((s) => s.instances);
  const [instance, setInstance] = usePickedInstance();
  const [query, setQuery] = useState("");
  const mods = useAsync(() => api.listMods(instance!.id), [instance?.id], !!instance);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = mods.data ?? [];
    if (!q) return list;
    return list.filter((m) =>
      [m.name, m.fileName, m.modId, m.description, ...m.authors].some((v) => v?.toLowerCase().includes(q)),
    );
  }, [mods.data, query]);

  const importMods = async () => {
    if (!instance) return;
    try {
      const n = await api.importMods(instance.id);
      if (n > 0) {
        toast.success(t("mods.imported", { n }));
        await mods.reload();
      }
    } catch (e) {
      reportError(e);
    }
  };

  if (instances.length === 0) {
    return (
      <Page art={3}>
        <PageHeader title={t("mods.title")} subtitle={t("mods.subtitle")} />
        <Card>
          <EmptyState icon={<Puzzle size={22} />} title={t("mods.emptyTitle")} body={t("mods.noInstance")} />
        </Card>
      </Page>
    );
  }

  const enabledCount = (mods.data ?? []).filter((m) => m.enabled).length;

  return (
    <Page art={3}>
      <PageHeader
        title={t("mods.title")}
        subtitle={t("mods.subtitle")}
        actions={<InstancePicker value={instance} onChange={setInstance} />}
      />

      {instance?.loader === "vanilla" && (
        <div className="mb-4 flex items-start gap-3 rounded-xl bg-info/[0.07] px-4 py-3 text-[13px] text-muted ring-1 ring-inset ring-info/20">
          <Info size={16} className="mt-0.5 shrink-0 text-info" />
          {t("mods.vanillaNotice")}
        </div>
      )}

      <div className="mb-4 flex items-center gap-3">
        <SearchInput value={query} onChange={setQuery} placeholder={t("mods.searchPlaceholder")} className="w-72" />
        {mods.data && mods.data.length > 0 && (
          <span className="text-[12.5px] text-faint">
            {t("mods.count", { enabled: enabledCount, total: mods.data.length })}
          </span>
        )}
        <div className="ml-auto flex gap-2">
          <Button
            variant="secondary"
            icon={<FolderOpen size={15} />}
            onClick={() => instance && void api.openInstanceFolder(instance.id, "mods").catch(reportError)}
          >
            {t("common.openFolder")}
          </Button>
          <Button variant="primary" icon={<Plus size={15} />} onClick={() => void importMods()}>
            {t("mods.import")}
          </Button>
        </div>
      </div>

      <Card className="overflow-hidden">
        {mods.loading && !mods.data ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-14 w-full" />
            ))}
          </div>
        ) : mods.error ? (
          <EmptyState icon={<Puzzle size={22} />} title={t("error.title")} body={errorMessage(mods.error)} />
        ) : (mods.data ?? []).length === 0 ? (
          <EmptyState
            icon={<Puzzle size={22} />}
            title={t("mods.emptyTitle")}
            body={t("mods.emptyBody")}
            action={
              <Button variant="primary" icon={<Plus size={15} />} onClick={() => void importMods()}>
                {t("mods.import")}
              </Button>
            }
          />
        ) : filtered.length === 0 ? (
          <EmptyState icon={<Puzzle size={22} />} title={t("mods.noMatch")} />
        ) : (
          <ul className="divide-y divide-line">
            {filtered.map((m, n) => (
              <ModRow key={m.fileName} mod={m} index={n} instanceId={instance!.id} onChanged={() => void mods.reload()} />
            ))}
          </ul>
        )}
      </Card>
    </Page>
  );
}
