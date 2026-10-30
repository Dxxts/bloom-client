import { useNavigate } from "react-router";
import { Compass } from "lucide-react";
import { useT } from "@/i18n";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/Feedback";

export function NotFoundPage() {
  const t = useT();
  const navigate = useNavigate();
  return (
    <div className="flex h-full items-center justify-center p-7">
      <EmptyState
        icon={<Compass size={22} />}
        title={t("notFound.title")}
        action={<Button onClick={() => navigate("/")}>{t("notFound.back")}</Button>}
      />
    </div>
  );
}
