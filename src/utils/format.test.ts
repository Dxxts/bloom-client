import { describe, expect, it } from "vitest";
import { formatBytes, formatDuration, formatMemory, percent, relativeTime } from "./format";
import { safeHref } from "./sanitize";
import { translate } from "@/i18n/translate";

describe("format", () => {
  it("formats bytes", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(5 * 1024 * 1024 * 1024)).toBe("5.0 GB");
  });

  it("formats memory", () => {
    expect(formatMemory(512)).toBe("512 MB");
    expect(formatMemory(4096)).toBe("4 GB");
    expect(formatMemory(3584)).toBe("3.5 GB");
  });

  it("formats durations", () => {
    expect(formatDuration(42)).toBe("42s");
    expect(formatDuration(600)).toBe("10m");
    expect(formatDuration(3600 + 5 * 60)).toBe("1h 05m");
  });

  it("clamps percentages", () => {
    expect(percent(5, 10)).toBe(50);
    expect(percent(20, 10)).toBe(100);
    expect(percent(1, 0)).toBe(0);
  });

  it("computes relative time", () => {
    const t = (k: string, v?: Record<string, number>) => `${k}:${v?.n ?? ""}`;
    expect(relativeTime(1000, t, "en-US", 1030)).toBe("common.justNow:");
    expect(relativeTime(1000, t, "en-US", 1000 + 300)).toBe("common.minutesAgo:5");
    expect(relativeTime(1000, t, "en-US", 1000 + 7200)).toBe("common.hoursAgo:2");
  });
});

describe("sanitize", () => {
  it("only allows https links", () => {
    expect(safeHref("https://minecraft.net/x")).toBe("https://minecraft.net/x");
    expect(safeHref("javascript:alert(1)")).toBeUndefined();
    expect(safeHref("http://example.com")).toBeUndefined();
    expect(safeHref("/article")).toBe("https://www.minecraft.net/article");
  });
});

describe("i18n", () => {
  it("interpolates variables and falls back to English", () => {
    expect(translate("en", "home.aboutVersion", { version: "1.0" })).toBe("Version 1.0");
    expect(translate("it", "home.aboutVersion", { version: "1.0" })).toBe("Versione 1.0");
    expect(translate("it", "progress.files", { done: 1, total: 2 })).toBe("1 / 2 file");
  });
});
