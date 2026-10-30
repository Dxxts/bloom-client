import { createHashRouter } from "react-router";
import { AppLayout } from "@/layouts/AppLayout";
import { AccountsPage } from "@/pages/AccountsPage";
import { HomePage } from "@/pages/HomePage";
import { InstanceDetailPage } from "@/pages/InstanceDetailPage";
import { InstancesPage } from "@/pages/InstancesPage";
import { ModsPage } from "@/pages/ModsPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { PlayPage } from "@/pages/PlayPage";
import { ResourcePacksPage } from "@/pages/ResourcePacksPage";
import { SettingsPage } from "@/pages/SettingsPage";

export const router = createHashRouter([
  {
    path: "/",
    element: <AppLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: "play", element: <PlayPage /> },
      { path: "instances", element: <InstancesPage /> },
      { path: "instances/:id", element: <InstanceDetailPage /> },
      { path: "mods", element: <ModsPage /> },
      { path: "resource-packs", element: <ResourcePacksPage /> },
      { path: "accounts", element: <AccountsPage /> },
      { path: "settings", element: <SettingsPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
]);
