import { Badge } from "@/components/ui/badge";
import type { StatusCountResponse } from "@/features/dashboard/types";
import type { SceneStatus } from "@/features/scenes/types";

type DashboardStatusCardProps = {
  status: StatusCountResponse;
  onOpen: () => void;
};

const statusLabels: Record<SceneStatus, string> = {
  IDEA: "Ideia",
  PLANNED: "Planejada",
  DRAFT: "Rascunho",
  WRITTEN: "Escrita",
  REVISED: "Revisada",
  FINAL: "Final",
};

export function DashboardStatusCard({ status, onOpen }: DashboardStatusCardProps) {
  const label = statusLabels[status.status];

  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={`Ver cenas com status ${label}`}
      className="flex min-h-[112px] w-full cursor-pointer flex-col justify-between rounded-md border border-zinc-200 bg-zinc-50 p-3 text-left transition-[transform,background-color,box-shadow] duration-150 ease-out hover:scale-[1.01] hover:bg-white hover:shadow-sm hover:shadow-zinc-200/70 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-zinc-500"
    >
      <span className="flex items-start justify-between gap-3">
        <span>
          <span className="block text-sm font-medium text-zinc-800">{label}</span>
          <span className="mt-2 block text-xs text-zinc-500">{formatNumber(status.wordCount)} palavras</span>
        </span>
        <Badge variant="outline">{formatNumber(status.scenesCount)} cenas</Badge>
      </span>
    </button>
  );
}

function formatNumber(value: number) {
  return new Intl.NumberFormat("pt-BR").format(value);
}
