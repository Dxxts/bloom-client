import type { ReactNode } from "react";
import { Card } from "@/components/ui/Card";
import type { Settings } from "@/types";

export interface SectionProps {
  draft: Settings;
  update: (patch: Partial<Settings>) => void;
}

export function SettingsGroup({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <div>
        <h2 className="text-[12px] font-bold uppercase tracking-[0.08em] text-muted">{title}</h2>
        {description && <p className="mt-0.5 text-[12.5px] text-faint">{description}</p>}
      </div>
      <Card className="overflow-visible">{children}</Card>
    </section>
  );
}
