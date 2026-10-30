import { create } from "zustand";
import { api } from "@/services/api";
import type { Instance } from "@/types";
import { useAppStore } from "./appStore";

interface InstancesState {
  instances: Instance[];
  loaded: boolean;
  load: () => Promise<void>;
  select: (id: string | null) => Promise<void>;
}

export const useInstancesStore = create<InstancesState>((set) => ({
  instances: [],
  loaded: false,

  load: async () => {
    const instances = await api.listInstances();
    set({ instances, loaded: true });
  },

  select: async (id) => {
    await api.setSelectedInstance(id);
    const settings = useAppStore.getState().settings;
    if (settings) useAppStore.getState().replaceSettings({ ...settings, selectedInstanceId: id });
  },
}));

/** Selected instance, falling back to the most recently played one. */
export function useSelectedInstance(): Instance | null {
  const instances = useInstancesStore((s) => s.instances);
  const selectedId = useAppStore((s) => s.settings?.selectedInstanceId ?? null);
  return instances.find((i) => i.id === selectedId) ?? instances[0] ?? null;
}
