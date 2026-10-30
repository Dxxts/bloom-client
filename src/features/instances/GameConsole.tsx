import { useEffect, useMemo, useRef, useState } from "react";
import { ClipboardCopy, FolderOpen, SquareTerminal } from "lucide-react";
import { useT } from "@/i18n";
import { EmptyState } from "@/components/ui/Feedback";
import { SearchInput, Toggle } from "@/components/ui/Form";
import { IconButton } from "@/components/ui/IconButton";
import { api } from "@/services/api";
import { reportError } from "@/services/errors";
import { useLogsStore } from "@/stores/sessionsStore";
import { toast } from "@/stores/uiStore";
import { cn } from "@/utils/format";

function lineClass(line: string, stream: string): string {
  if (/\b(ERROR|FATAL|SEVERE)\b|Exception|^\s+at /.test(line)) return "text-error";
  if (/\bWARN(ING)?\b/.test(line)) return "text-warning";
  if (stream === "stderr") return "text-[#c9a3a3]";
  if (line.startsWith("[Bloom]")) return "text-primary";
  return "text-[#b9bcc4]";
}

/** Live game output with filtering and auto-scroll. */
export function GameConsole({ instanceId }: { instanceId: string }) {
  const t = useT();
  const lines = useLogsStore((s) => s.logs[instanceId]);
  const replace = useLogsStore((s) => s.replace);
  const [filter, setFilter] = useState("");
  const [follow, setFollow] = useState(true);
  const scroller = useRef<HTMLDivElement>(null);

  // Hydrate from the backend buffer (covers output produced while the
  // console was not mounted).
  useEffect(() => {
    void api
      .getSessionLogs(instanceId)
      .then((l) => {
        if (l.length) replace(instanceId, l);
      })
      .catch(() => undefined);
  }, [instanceId, replace]);

  const visible = useMemo(() => {
    const all = lines ?? [];
    const q = filter.trim().toLowerCase();
    // Rendering is capped to keep the DOM light; the full output is in the
    // log file and in "Copy all".
    return (q ? all.filter((l) => l.line.toLowerCase().includes(q)) : all).slice(-2000);
  }, [lines, filter]);

  useEffect(() => {
    if (follow && scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight;
  }, [visible, follow]);

  const copyAll = async () => {
    try {
      await navigator.clipboard.writeText(visible.map((l) => l.line).join("\n"));
      toast.success(t("common.copied"));
    } catch (e) {
      reportError(e);
    }
  };

  return (
    <div className="flex h-[calc(100vh-290px)] min-h-[320px] flex-col overflow-hidden rounded-2xl border border-line bg-[#08090b]">
      <div className="flex items-center gap-3 border-b border-line px-3 py-2">
        <SearchInput value={filter} onChange={setFilter} placeholder={t("console.filter")} className="w-64" />
        <span className="font-mono text-[11.5px] text-faint">{t("console.lines", { n: visible.length })}</span>
        <label className="ml-auto flex items-center gap-2 text-[12px] font-semibold text-muted">
          <Toggle checked={follow} onChange={setFollow} label={t("console.autoscroll")} />
          {t("console.autoscroll")}
        </label>
        <IconButton size="sm" label={t("console.copy")} icon={<ClipboardCopy size={15} />} onClick={() => void copyAll()} />
        <IconButton
          size="sm"
          label={t("console.openLogs")}
          icon={<FolderOpen size={15} />}
          onClick={() => void api.openInstanceFolder(instanceId, "logs").catch(reportError)}
        />
      </div>
      {visible.length === 0 ? (
        <EmptyState icon={<SquareTerminal size={22} />} title={t("console.title")} body={t("console.empty")} className="flex-1" />
      ) : (
        <div
          ref={scroller}
          onWheel={(e) => {
            if (e.deltaY < 0) setFollow(false);
          }}
          className="flex-1 overflow-auto px-4 py-3 font-mono text-[12px] leading-[1.6]"
          data-selectable
        >
          {visible.map((l, i) => (
            <div key={i} className={cn("whitespace-pre-wrap break-all", lineClass(l.line, l.stream))}>
              {l.line}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
