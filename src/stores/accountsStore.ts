import { create } from "zustand";
import { api } from "@/services/api";
import type { Account } from "@/types";

interface AccountsState {
  accounts: Account[];
  loaded: boolean;
  skins: Record<string, string | null>;
  load: () => Promise<void>;
  loadSkin: (uuid: string) => Promise<void>;
}

export const useAccountsStore = create<AccountsState>((set, get) => ({
  accounts: [],
  loaded: false,
  skins: {},

  load: async () => {
    const accounts = await api.listAccounts();
    set({ accounts, loaded: true });
  },

  loadSkin: async (uuid) => {
    if (uuid in get().skins) return;
    set((s) => ({ skins: { ...s.skins, [uuid]: null } }));
    try {
      const url = await api.getAccountSkin(uuid);
      set((s) => ({ skins: { ...s.skins, [uuid]: url } }));
    } catch {
      // No skin available (offline or default skin): the UI shows a fallback.
    }
  },
}));

export const selectActiveAccount = (s: AccountsState) => s.accounts.find((a) => a.active) ?? null;
