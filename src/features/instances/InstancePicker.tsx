import { useSearchParams } from "react-router";
import { useT } from "@/i18n";
import { Select } from "@/components/ui/Select";
import { useInstancesStore, useSelectedInstance } from "@/stores/instancesStore";
import type { Instance } from "@/types";
import { EngineIcon } from "@/components/EngineIcon";

/**
 * Instance chosen for content pages: `?instance=` in the URL, otherwise the
 * globally selected instance.
 */
export function usePickedInstance(): [Instance | null, (id: string) => void] {
  const [params, setParams] = useSearchParams();
  const instances = useInstancesStore((s) => s.instances);
  const fallback = useSelectedInstance();
  const fromUrl = instances.find((i) => i.id === params.get("instance"));
  return [fromUrl ?? fallback, (id) => setParams({ instance: id }, { replace: true })];
}

export function InstancePicker({ value, onChange }: { value: Instance | null; onChange: (id: string) => void }) {
  const t = useT();
  const instances = useInstancesStore((s) => s.instances);
  return (
    <Select
      value={value?.id ?? null}
      onChange={onChange}
      label={t("mods.instance")}
      className="w-[260px]"
      align="right"
      options={instances.map((i) => ({
        value: i.id,
        label: i.name,
        icon: <EngineIcon engine={i.engine} />,
        description: `${i.versionId} · ${i.loader}`,
      }))}
    />
  );
}
