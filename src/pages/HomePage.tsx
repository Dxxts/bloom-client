import { useState } from "react";
import { Clock3 } from "lucide-react";
import { useT, type TranslationKey } from "@/i18n";
import { Reveal } from "@/components/ui/Reveal";
import { HeroAmbient, useHeroSlide } from "@/features/home/HeroBackdrop";
import { HeroLaunch } from "@/features/home/HeroLaunch";
import { NewsFeed } from "@/features/home/NewsFeed";
import { FriendsPanel, ProfileCard, RecentActivity } from "@/features/home/SideCards";
import { selectActiveAccount, useAccountsStore } from "@/stores/accountsStore";
import { useInstancesStore } from "@/stores/instancesStore";
import { formatDuration } from "@/utils/format";

function greetingKey(): TranslationKey {
  const h = new Date().getHours();
  if (h < 12) return "home.greeting.morning";
  if (h < 18) return "home.greeting.afternoon";
  return "home.greeting.evening";
}

function Greeting() {
  const t = useT();
  const account = useAccountsStore(selectActiveAccount);
  const totalSeconds = useInstancesStore((s) => s.instances.reduce((sum, i) => sum + i.totalPlaySeconds, 0));
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
      <p className="text-[15px] font-semibold text-muted">
        {account ? (
          <>
            {t(greetingKey())}, <span className="font-extrabold text-text">{account.username}</span>
          </>
        ) : (
          t("home.greetingGuest")
        )}
      </p>
      {totalSeconds > 0 && (
        <span className="flex items-center gap-1.5 text-[12.5px] font-semibold text-faint">
          <Clock3 size={13} />
          {t("home.totalPlayed", { duration: formatDuration(totalSeconds) })}
        </span>
      )}
    </div>
  );
}

export function HomePage() {
  const [hovering, setHovering] = useState(false);
  const [slide, setSlide] = useHeroSlide(hovering);
  return (
    <div className="relative isolate min-h-full">
      <HeroAmbient index={slide} />
      <div className="@container relative mx-auto max-w-[1560px] p-6">
        {/* Spans both columns so it lines up with the whole layout, not just the hero. */}
        <Reveal index={0} className="mb-5">
          <Greeting />
        </Reveal>
        <div className="grid grid-cols-1 gap-5 @[900px]:grid-cols-[minmax(0,1fr)_288px] @[1240px]:grid-cols-[minmax(0,1fr)_320px]">
          <div className="@container min-w-0 space-y-5">
            <Reveal index={1}>
              <HeroLaunch slide={slide} onSlide={setSlide} onHover={setHovering} />
            </Reveal>
            <Reveal index={2}>
              <NewsFeed />
            </Reveal>
          </div>
          <aside className="grid content-start gap-4 @[640px]:grid-cols-2 @[900px]:grid-cols-1">
            <Reveal index={2}>
              <ProfileCard />
            </Reveal>
            <Reveal index={3}>
              <RecentActivity />
            </Reveal>
            <Reveal index={4} className="@[640px]:col-span-2 @[900px]:col-span-1">
              <FriendsPanel />
            </Reveal>
          </aside>
        </div>
      </div>
    </div>
  );
}
