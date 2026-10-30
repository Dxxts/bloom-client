import { useLocation, useOutlet } from "react-router";
import { AnimatePresence, motion } from "motion/react";
import { Sidebar } from "@/components/Sidebar";
import { Titlebar } from "@/components/Titlebar";
import { DialogHost, Toaster } from "@/components/ui/Feedback";
import { InstanceEditorHost } from "@/features/instances/InstanceEditor";

/** Persistent shell: title bar, sidebar and animated page outlet. */
export function AppLayout() {
  const location = useLocation();
  const outlet = useOutlet();
  // Animate between top-level sections only (not between tabs/query params).
  const sectionKey = "/" + (location.pathname.split("/")[1] ?? "");

  return (
    <div className="flex h-full flex-col bg-bg">
      <Titlebar />
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <main className="relative min-w-0 flex-1 overflow-hidden rounded-tl-xl border-l border-t border-white/[0.045] bg-bg-2">
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={sectionKey}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0, y: -6 }}
              transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
              className="absolute inset-0 overflow-y-auto"
            >
              {outlet}
            </motion.div>
          </AnimatePresence>
        </main>
      </div>
      <InstanceEditorHost />
      <Toaster />
      <DialogHost />
    </div>
  );
}
