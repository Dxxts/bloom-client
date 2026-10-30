import { LoaderCircle } from "lucide-react";
import { cn } from "@/utils/format";

export function Spinner({ size = 16, className }: { size?: number; className?: string }) {
  return <LoaderCircle size={size} className={cn("animate-spin", className)} aria-hidden />;
}
