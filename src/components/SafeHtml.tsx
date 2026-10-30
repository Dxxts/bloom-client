import { useMemo, type ReactNode } from "react";
import { parseSafeHtml, type SafeNode } from "@/utils/sanitize";

function render(nodes: SafeNode[], keyPrefix = ""): ReactNode[] {
  return nodes.map((n, i) => {
    const key = `${keyPrefix}${i}`;
    if (n.type === "text") return n.text;
    const children = render(n.children, `${key}.`);
    switch (n.tag) {
      case "br":
        return <br key={key} />;
      case "a":
        return n.href ? (
          <a key={key} href={n.href} target="_blank" rel="noopener noreferrer">
            {children}
          </a>
        ) : (
          <span key={key}>{children}</span>
        );
      default: {
        const Tag = n.tag;
        return <Tag key={key}>{children}</Tag>;
      }
    }
  });
}

/** Renders remote HTML through the allow-list sanitizer. */
export function SafeHtml({ html, className }: { html: string; className?: string }) {
  const nodes = useMemo(() => parseSafeHtml(html), [html]);
  return (
    <div className={className} data-selectable>
      {render(nodes)}
    </div>
  );
}
