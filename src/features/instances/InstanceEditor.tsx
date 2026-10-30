import { useEffect, useState } from "react";
import { create } from "zustand";
import { useNavigate } from "react-router";
import { useT } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { api } from "@/services/api";
import { reportError } from "@/services/errors";
import { useInstancesStore } from "@/stores/instancesStore";
import { toast } from "@/stores/uiStore";
import type { Instance, InstanceInput } from "@/types";
import { emptyInput, InstanceForm, inputFromInstance } from "./InstanceForm";

interface EditorState {
  open: boolean;
  editing: Instance | null;
  initialVersion: string;
  openCreate: (versionId?: string) => void;
  openEdit: (instance: Instance) => void;
  close: () => void;
}

export const useInstanceEditor = create<EditorState>((set) => ({
  open: false,
  editing: null,
  initialVersion: "",
  openCreate: (versionId = "") => set({ open: true, editing: null, initialVersion: versionId }),
  openEdit: (instance) => set({ open: true, editing: instance, initialVersion: "" }),
  close: () => set({ open: false }),
}));

/** Global create/edit instance dialog (mounted once in the layout). */
export function InstanceEditorHost() {
  const t = useT();
  const navigate = useNavigate();
  const { open, editing, initialVersion, close } = useInstanceEditor();
  const [value, setValue] = useState<InstanceInput>(emptyInput());
  const [saving, setSaving] = useState(false);
  const loadInstances = useInstancesStore((s) => s.load);
  const select = useInstancesStore((s) => s.select);

  useEffect(() => {
    if (open) setValue(editing ? inputFromInstance(editing) : emptyInput(initialVersion));
  }, [open, editing, initialVersion]);

  const save = async () => {
    setSaving(true);
    try {
      const input = { ...value, name: value.name.trim() || value.versionId };
      if (editing) {
        const updated = await api.updateInstance(editing.id, input);
        toast.success(t("instances.updated", { name: updated.name }));
      } else {
        const created = await api.createInstance(input);
        await select(created.id);
        toast.success(t("instances.created", { name: created.name }));
        navigate("/");
      }
      await loadInstances();
      close();
    } catch (e) {
      reportError(e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={close}
      title={editing ? t("editor.editTitle") : t("editor.createTitle")}
      width={580}
      footer={
        <>
          <Button variant="ghost" onClick={close}>
            {t("common.cancel")}
          </Button>
          <Button variant="primary" loading={saving} disabled={!value.versionId} onClick={() => void save()}>
            {editing ? t("common.save") : t("common.create")}
          </Button>
        </>
      }
    >
      {open && <InstanceForm value={value} onChange={setValue} />}
    </Modal>
  );
}
