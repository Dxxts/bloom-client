import { useMemo, useState } from "react";
import { motion } from "motion/react";
import { Folder, FolderOpen, ImageIcon, Trash2, Upload } from "lucide-react";
import { useT } from "@/i18n";
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
import type { ResourcePackEntry } from "@/types";
import { cn, formatBytes } from "@/utils/format";
import { Page } from "@/layouts/Page";

function PackCard({
  pack,
  instanceId,
  index,
  onChanged,
}: {
  pack: ResourcePackEntry;
  instanceId: string;
  index: number;
  onChanged: () => void;
}) {
  const t = useT();
  const confirm = useUiStore((s) => s.confirm);
  const [busy, setBusy] = useState(false);
  const name = pack.fileName.replace(/\.zip$/i, "");

  const toggle = async (enabled: boolean) => {
    setBusy(true);
    try {
      await api.setResourcePackEnabled(instanceId, pack.fileName, enabled);
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
      message: t("packs.deleteConfirm", { name }),
      confirmLabel: t("common.delete"),
      cancelLabel: t("common.cancel"),
      danger: true,
    });
    if (!ok) return;
    try {
      await api.deleteResourcePack(instanceId, pack.fileName);
      onChanged();
    } catch (e) {
      reportError(e);
    }
  };

  return (
    <motion.div layout {...staggerItem(index)}>
      <Card className={cn("flex h-full gap-4 p-4 hover:ring-white/[0.12]", "transition-[translate,box-shadow] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] hover:-translate-y-1 hover:shadow-[inset_0_1px_0_rgb(255_255_255/0.06),0_24px_40px_-20px_rgb(0_0_0/0.95)]", pack.enabled && "!ring-primary/30")}>
        <div className="h-16 w-16 shrink-0 overflow-hidden rounded-xl border border-line bg-bg-2">
          {pack.iconDataUrl ? (
            <img src={pack.iconDataUrl} alt="" className="h-full w-full object-cover [image-rendering:pixelated]" />
          ) : (
            <div className="flex h-full w-full items-center justify-center text-faint">
              <ImageIcon size={22} />
            </div>
          )}
        </div>
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex items-start gap-2">
            <div className="min-w-0 flex-1 truncate text-[14px] font-bold text-text" title={pack.fileName}>
              {name}
            </div>
            <Toggle checked={pack.enabled} onChange={(v) => void toggle(v)} disabled={busy} label={name} />
          </div>
          <p className="mt-0.5 line-clamp-2 text-[12.5px] leading-snug text-muted">{pack.description ?? "—"}</p>
          <div className="mt-auto flex items-center gap-2 pt-2">
            {pack.packFormat !== null && <Badge>{t("packs.format", { n: pack.packFormat })}</Badge>}
            {pack.isDirectory && (
              <Badge>
                <Folder size={11} /> {t("packs.folder")}
              </Badge>
            )}
            <span className="text-[11.5px] text-faint">{formatBytes(pack.size)}</span>
            <IconButton
              size="sm"
              tone="danger"
              label={t("common.delete")}
              icon={<Trash2 size={14} />}
              onClick={() => void remove()}
              className="ml-auto"
            />
          </div>
        </div>
      </Card>
    </motion.div>
  );
}

export function ResourcePacksPage() {
  const t = useT();
  const instances = useInstancesStore((s) => s.instances);
  const [instance, setInstance] = usePickedInstance();
  const [query, setQuery] = useState("");
  const packs = useAsync(() => api.listResourcePacks(instance!.id), [instance?.id], !!instance);

  const [active, available] = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = (packs.data ?? []).filter(
      (p) => !q || p.fileName.toLowerCase().includes(q) || p.description?.toLowerCase().includes(q),
    );
    return [list.filter((p) => p.enabled), list.filter((p) => !p.enabled)];
  }, [packs.data, query]);

  const importPacks = async () => {
    if (!instance) return;
    try {
      const n = await api.importResourcePacks(instance.id);
      if (n > 0) {
        toast.success(t("packs.imported", { n }));
        await packs.reload();
      }
    } catch (e) {
      reportError(e);
    }
  };

  if (instances.length === 0) {
    return (
      <Page art={1}>
        <PageHeader title={t("packs.title")} subtitle={t("packs.subtitle")} />
        <Card>
          <EmptyState icon={<ImageIcon size={22} />} title={t("packs.emptyTitle")} body={t("packs.noInstance")} />
        </Card>
      </Page>
    );
  }

  const section = (title: string, list: ResourcePackEntry[]) =>
    list.length > 0 && (
      <section className="space-y-3">
        <h2 className="text-[12px] font-bold uppercase tracking-[0.08em] text-muted">
          {title} <span className="text-faint">· {list.length}</span>
        </h2>
        <div className="grid grid-cols-2 gap-3 xl:grid-cols-3">
          {list.map((p, n) => (
            <PackCard key={p.fileName} pack={p} index={n} instanceId={instance!.id} onChanged={() => void packs.reload()} />
          ))}
        </div>
      </section>
    );

  return (
    <Page art={1}>
      <PageHeader
        title={t("packs.title")}
        subtitle={t("packs.subtitle")}
        actions={<InstancePicker value={instance} onChange={setInstance} />}
      />
      <div className="mb-5 flex items-center gap-3">
        <SearchInput value={query} onChange={setQuery} placeholder={t("packs.searchPlaceholder")} className="w-72" />
        <div className="ml-auto flex gap-2">
          <Button
            variant="secondary"
            icon={<FolderOpen size={15} />}
            onClick={() => instance && void api.openInstanceFolder(instance.id, "resourcepacks").catch(reportError)}
          >
            {t("common.openFolder")}
          </Button>
          <Button variant="primary" icon={<Upload size={15} />} onClick={() => void importPacks()}>
            {t("packs.import")}
          </Button>
        </div>
      </div>

      {packs.loading && !packs.data ? (
        <div className="grid grid-cols-2 gap-3 xl:grid-cols-3">
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className="h-24 rounded-2xl" />
          ))}
        </div>
      ) : packs.error ? (
        <Card>
          <EmptyState icon={<ImageIcon size={22} />} title={t("error.title")} body={errorMessage(packs.error)} />
        </Card>
      ) : (packs.data ?? []).length === 0 ? (
        <Card>
          <EmptyState
            icon={<ImageIcon size={22} />}
            title={t("packs.emptyTitle")}
            body={t("packs.emptyBody")}
            action={
              <Button variant="primary" icon={<Upload size={15} />} onClick={() => void importPacks()}>
                {t("packs.import")}
              </Button>
            }
          />
        </Card>
      ) : (
        <div className="space-y-7">
          {section(t("packs.active"), active)}
          {section(t("packs.available"), available)}
        </div>
      )}
    </Page>
  );
}
