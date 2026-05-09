import { EmptyState } from "@/components/ui/empty-state";
import type { LocationResponse } from "@/features/locations/types";

type LocationsListProps = {
  locations: LocationResponse[];
  selectedLocationId: string | null;
  onEditLocation: (location: LocationResponse) => void;
};

export function LocationsList({ locations, selectedLocationId, onEditLocation }: LocationsListProps) {
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
        <button
          key={location.id}
          type="button"
          className={`grid min-h-[126px] content-start gap-2 rounded-md border bg-white p-3 text-left shadow-sm transition ${
            selectedLocationId === location.id
              ? "border-zinc-900 ring-2 ring-zinc-200"
              : "border-zinc-200 hover:border-zinc-300"
          }`}
          onClick={() => onEditLocation(location)}
        >
          <div className="min-w-0">
            <h3 className="truncate text-sm font-semibold text-zinc-950">{location.name}</h3>
            <div className="mt-1 flex min-h-5 flex-wrap items-center gap-1.5 text-xs text-zinc-500">
              {location.type ? <span className="max-w-full truncate">{location.type}</span> : null}
            </div>
          </div>

          <SummaryLine label="Importância" value={location.narrativeImportance} />
          <SummaryLine label="Descrição" value={location.description} />
        </button>
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
