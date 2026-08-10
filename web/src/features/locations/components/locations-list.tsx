import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import type { LocationResponse } from "@/features/locations/types";

type LocationsListProps = {
  locations: LocationResponse[];
  selectedLocationId: string | null;
  deletePendingLocationId: string | null;
  onEditLocation: (location: LocationResponse) => void;
  onDeleteLocation: (location: LocationResponse) => void;
};

export function LocationsList({
  locations,
  selectedLocationId,
  deletePendingLocationId,
  onEditLocation,
  onDeleteLocation,
}: LocationsListProps) {
  if (locations.length === 0) {
    return (
      <EmptyState
        title="Nenhuma localização ainda"
        description="Crie a primeira localização deste livro para organizar os espaços da narrativa."
      />
    );
  }

  return (
    <div className="grid max-h-[calc(100vh-210px)] content-start gap-2 overflow-y-auto pr-1">
      {locations.map((location) => (
        <article
          key={location.id}
          className={`grid min-h-[126px] content-start gap-2 rounded-md border bg-white p-3 shadow-sm transition ${
            selectedLocationId === location.id
              ? "border-zinc-900 ring-2 ring-zinc-200"
              : "border-zinc-200 hover:border-zinc-300"
          }`}
        >
          <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-2">
            <button type="button" className="min-w-0 text-left" onClick={() => onEditLocation(location)}>
              <h3 className="truncate text-sm font-semibold text-zinc-950">{location.name}</h3>
              <div className="mt-1 flex min-h-5 flex-wrap items-center gap-1.5 text-xs text-zinc-500">
                {location.type ? <span className="max-w-full truncate">{location.type}</span> : null}
              </div>
            </button>

            <div className="flex shrink-0 items-center gap-1">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-7 px-2 text-xs"
                onClick={() => onEditLocation(location)}
              >
                Editar
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-7 px-2 text-xs text-red-700 hover:bg-red-50"
                disabled={deletePendingLocationId === location.id}
                onClick={() => onDeleteLocation(location)}
              >
                {deletePendingLocationId === location.id ? "..." : "Excluir"}
              </Button>
            </div>
          </div>

          <SummaryLine label="Importância" value={location.narrativeImportance} />
          <SummaryLine label="Descrição" value={location.description} />
        </article>
      ))}
    </div>
  );
}

function SummaryLine({ label, value }: { label: string; value: string | null }) {
  return (
    <p className="line-clamp-2 min-h-10 text-xs leading-5 text-zinc-600">
      {value ? (
        <>
          <span className="font-medium text-zinc-800">{label}:</span> {value}
        </>
      ) : (
        <span className="text-zinc-400">{label}: não informado</span>
      )}
    </p>
  );
}
