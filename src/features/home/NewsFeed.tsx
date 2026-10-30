import { useState } from "react";
import { motion } from "motion/react";
import { ArrowUpRight, Newspaper, RefreshCw } from "lucide-react";
import { useLocale, useT } from "@/i18n";
import { SafeHtml } from "@/components/SafeHtml";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/Feedback";
import { IconButton } from "@/components/ui/IconButton";
import { Modal } from "@/components/ui/Modal";
import { Spinner } from "@/components/ui/Spinner";
import { useAsync } from "@/hooks/useAsync";
import { api } from "@/services/api";
import { errorMessage } from "@/services/errors";
import type { NewsItem } from "@/types";
import { cn, formatDate } from "@/utils/format";

function ArticleModal({ item, onClose }: { item: NewsItem | null; onClose: () => void }) {
  const t = useT();
  const locale = useLocale();
  const isMojang = item?.source === "mojang";
  const article = useAsync(() => api.getNewsArticle(item!.id), [item?.id], !!item && isMojang);
  return (
    <Modal
      open={!!item}
      onClose={onClose}
      width={680}
      title={item?.title}
      description={item?.date ? formatDate(item.date, locale) : undefined}
      footer={
        <Button variant="secondary" onClick={onClose}>
          {t("common.close")}
        </Button>
      }
    >
      {item?.imageUrl && (
        <img src={item.imageUrl} alt="" className="mb-4 aspect-[2/1] w-full rounded-lg object-cover" loading="lazy" />
      )}
      {!isMojang ? (
        <SafeHtml html={item?.bodyHtml ?? ""} className="bloom-prose pb-4" />
      ) : article.loading ? (
        <div className="flex justify-center py-10 text-muted">
          <Spinner size={22} />
        </div>
      ) : article.error ? (
        <p className="py-6 text-[13px] text-error">{errorMessage(article.error)}</p>
      ) : (
        <SafeHtml html={article.data ?? ""} className="bloom-prose pb-4" />
      )}
    </Modal>
  );
}

// The first story leads: full width in two columns, a 2×2 tile in three.
const featuredLayout = "aspect-[16/9] @[520px]:col-span-2 @[520px]:aspect-[21/9] @[860px]:row-span-2 @[860px]:aspect-auto";
const grid = "grid grid-cols-1 gap-4 @[520px]:grid-cols-2 @[860px]:grid-cols-3";

function NewsCard({
  item,
  onOpen,
  featured,
  className,
}: {
  item: NewsItem;
  onOpen: () => void;
  featured?: boolean;
  className?: string;
}) {
  const locale = useLocale();
  return (
    <motion.button
      type="button"
      onClick={onOpen}
      whileHover={{ y: -3 }}
      transition={{ type: "spring", stiffness: 420, damping: 30 }}
      className={cn(
        "group relative overflow-hidden rounded-xl bg-surface text-left ring-1 ring-white/[0.05] transition-shadow duration-200 hover:shadow-[0_18px_40px_-18px_rgb(0_0_0/0.9)] hover:ring-white/[0.12]",
        featured ? featuredLayout : "aspect-[16/10]",
        className,
      )}
    >
      {item.imageUrl ? (
        <img
          src={item.imageUrl}
          alt=""
          loading="lazy"
          className="absolute inset-0 h-full w-full object-cover transition-transform duration-500 ease-out group-hover:scale-[1.05]"
        />
      ) : (
        <div className="absolute inset-0 flex items-center justify-center bg-surface-hover text-faint">
          <Newspaper size={28} />
        </div>
      )}
      <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/30 to-transparent" />
      <span className="absolute left-3 top-3 inline-flex h-6 items-center rounded-md bg-black/55 px-2 text-[11px] font-bold uppercase tracking-[0.08em] text-text/90 backdrop-blur-md">
        {item.source === "bloom" ? "Bloom" : item.category}
      </span>
      <span className="absolute right-3 top-3 flex h-7 w-7 translate-y-1 items-center justify-center rounded-full bg-black/55 text-text opacity-0 backdrop-blur-md transition-all duration-200 group-hover:translate-y-0 group-hover:opacity-100">
        <ArrowUpRight size={15} />
      </span>
      <div className="absolute inset-x-0 bottom-0 p-4">
        <div
          className={cn(
            "line-clamp-2 font-extrabold leading-snug text-text drop-shadow-[0_2px_8px_rgb(0_0_0/0.6)]",
            featured ? "text-[19px]" : "text-[14.5px]",
          )}
        >
          {item.title}
        </div>
        {item.date && <div className="mt-1 text-[12px] font-semibold text-text/55">{formatDate(item.date, locale)}</div>}
      </div>
    </motion.button>
  );
}

export function NewsFeed() {
  const t = useT();
  const news = useAsync(() => api.getNews(), []);
  const [open, setOpen] = useState<NewsItem | null>(null);
  const items = (news.data ?? []).slice(0, 7);

  return (
    <section>
      <div className="mb-3 flex items-center justify-between gap-4">
        <h2 className="text-[16px] font-extrabold tracking-tight text-text">
          {t("home.latestNews")}
          {items.length > 0 && <span className="ml-2 font-semibold text-faint">({items.length})</span>}
        </h2>
        <div className="flex items-center gap-2">
          <span className="hidden text-[12px] text-faint @[600px]:inline">{t("home.newsSource")}</span>
          <IconButton
            label={t("common.refresh")}
            icon={<RefreshCw size={15} className={news.loading ? "animate-spin" : ""} />}
            onClick={() => void news.reload()}
            size="sm"
          />
        </div>
      </div>
      {news.loading && !news.data ? (
        <div className={grid}>
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className={cn("rounded-xl", i === 0 ? featuredLayout : "aspect-[16/10]")} />
          ))}
        </div>
      ) : news.error && !news.data ? (
        <div className="rounded-xl bg-surface">
          <EmptyState
            icon={<Newspaper size={22} />}
            title={t("home.newsError")}
            body={errorMessage(news.error)}
            action={
              <Button size="sm" onClick={() => void news.reload()}>
                {t("common.retry")}
              </Button>
            }
            className="py-10"
          />
        </div>
      ) : (
        <div className={grid}>
          {items.map((item, i) => (
            <NewsCard
              key={item.id}
              item={item}
              featured={i === 0}
              // Seven stories fill two columns evenly; three columns fit six.
              className={i === 6 ? "@[860px]:hidden" : undefined}
              onOpen={() => setOpen(item)}
            />
          ))}
        </div>
      )}
      <ArticleModal item={open} onClose={() => setOpen(null)} />
    </section>
  );
}
