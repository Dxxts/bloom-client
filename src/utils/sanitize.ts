// Allow-list HTML parser for remote content (Mojang patch notes). The HTML is
// never injected with innerHTML: it is parsed with DOMParser (inert, no
// scripts run) and converted to a plain tree of safe nodes that React renders.

export type SafeNode =
  | { type: "text"; text: string }
  | { type: "element"; tag: SafeTag; href?: string; children: SafeNode[] };

export type SafeTag = "p" | "h1" | "h2" | "h3" | "h4" | "ul" | "ol" | "li" | "strong" | "em" | "code" | "a" | "br" | "blockquote";

const ALLOWED: Record<string, SafeTag> = {
  p: "p",
  h1: "h1",
  h2: "h2",
  h3: "h3",
  h4: "h4",
  h5: "h4",
  h6: "h4",
  ul: "ul",
  ol: "ol",
  li: "li",
  b: "strong",
  strong: "strong",
  i: "em",
  em: "em",
  code: "code",
  a: "a",
  br: "br",
  blockquote: "blockquote",
};

const DROP_WITH_CONTENT = new Set(["script", "style", "iframe", "object", "embed", "noscript", "template", "svg", "math"]);

export function safeHref(raw: string | null): string | undefined {
  if (!raw) return undefined;
  try {
    const url = new URL(raw, "https://www.minecraft.net/");
    return url.protocol === "https:" ? url.toString() : undefined;
  } catch {
    return undefined;
  }
}

function convert(node: Node): SafeNode[] {
  if (node.nodeType === Node.TEXT_NODE) {
    const text = node.textContent ?? "";
    return text ? [{ type: "text", text }] : [];
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return [];
  const el = node as Element;
  const name = el.tagName.toLowerCase();
  if (DROP_WITH_CONTENT.has(name)) return [];
  const children = Array.from(el.childNodes).flatMap(convert);
  const tag = ALLOWED[name];
  if (!tag) return children; // unwrap unknown elements, keep their text
  if (tag === "a") {
    return [{ type: "element", tag, href: safeHref(el.getAttribute("href")), children }];
  }
  return [{ type: "element", tag, children }];
}

export function parseSafeHtml(html: string): SafeNode[] {
  const doc = new DOMParser().parseFromString(html, "text/html");
  return Array.from(doc.body.childNodes).flatMap(convert);
}
