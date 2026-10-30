const petal = "M0 0 C -46 -46 -46 -128 0 -164 C 46 -128 46 -46 0 0 Z";
const innerPetal = "M0 0 C -38 -40 -38 -104 0 -132 C 38 -104 38 -40 0 0 Z";

/** The Bloom flower mark (vector, original artwork). */
export function BloomMark({ size = 24, className }: { size?: number; className?: string }) {
  return (
    <svg viewBox="-170 -170 340 340" width={size} height={size} className={className} aria-hidden>
      <g fill="#2f7a52">
        {[45, 135, 225, 315].map((r) => (
          <path key={r} d={innerPetal} transform={`rotate(${r})`} />
        ))}
      </g>
      <g fill="var(--bloom-primary)">
        {[0, 90, 180, 270].map((r) => (
          <path key={r} d={petal} transform={`rotate(${r})`} />
        ))}
      </g>
      <circle r="30" fill="var(--bloom-bg)" />
      <circle r="16" fill="var(--bloom-primary-hover)" />
    </svg>
  );
}
