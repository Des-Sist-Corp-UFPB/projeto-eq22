type CollapseChevronButtonProps = {
  isExpanded: boolean;
  label: string;
  onClick: () => void;
};

export function CollapseChevronButton({ isExpanded, label, onClick }: CollapseChevronButtonProps) {
  return (
    <button
      type="button"
      aria-expanded={isExpanded}
      aria-label={label}
      title={label}
      onClick={onClick}
      className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-zinc-200 bg-white text-zinc-500 transition hover:border-zinc-300 hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-2"
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 20 20"
        className={`h-3.5 w-3.5 transition-transform duration-150 ${isExpanded ? "rotate-90" : ""}`}
        fill="none"
      >
        <path d="M7 5l6 5-6 5" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" />
      </svg>
    </button>
  );
}
