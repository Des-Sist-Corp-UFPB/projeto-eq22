/**
 * Original artwork, drawn here rather than shipped as an asset: three stacked manuscript pages
 * whose text lines thin out towards the top page, which is still being written. Purely decorative,
 * so it is hidden from assistive technology.
 */
export function ManuscriptArtwork({ className = "" }: { className?: string }) {
  return (
    <svg
      className={`h-auto w-full max-w-md ${className}`}
      viewBox="0 0 420 300"
      fill="none"
      role="presentation"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <linearGradient id="manuscript-page" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#ffffff" />
          <stop offset="100%" stopColor="#f4f2ea" />
        </linearGradient>
      </defs>

      {[
        { x: 44, y: 58, rotate: -6, lines: 7 },
        { x: 76, y: 42, rotate: -2, lines: 6 },
        { x: 112, y: 26, rotate: 3, lines: 4 },
      ].map((page, pageIndex) => (
        <g key={page.x} transform={`rotate(${page.rotate} ${page.x + 100} ${page.y + 110})`}>
          <rect
            x={page.x}
            y={page.y}
            width="200"
            height="220"
            rx="10"
            fill="url(#manuscript-page)"
            stroke="#d8d4c6"
            strokeWidth="1.5"
          />
          {Array.from({ length: page.lines }, (_, lineIndex) => (
            <rect
              key={lineIndex}
              x={page.x + 24}
              y={page.y + 34 + lineIndex * 22}
              // The last line of the topmost page stops short: the sentence in progress.
              width={pageIndex === 2 && lineIndex === page.lines - 1 ? 68 : 152 - (lineIndex % 3) * 18}
              height="6"
              rx="3"
              fill="#2b2b2b"
              opacity={0.08 + pageIndex * 0.04}
            />
          ))}
        </g>
      ))}
    </svg>
  );
}
