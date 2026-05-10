import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Textarea } from "@/components/ui/textarea";
import { useCharacters } from "@/features/characters/api/characters-hooks";
import { useItems } from "@/features/items/api/items-hooks";
import { useLocations } from "@/features/locations/api/locations-hooks";
import type { Scene } from "@/features/scenes/types";

type ScenePlanningPanelProps = {
  bookId: string;
  scene: Scene;
};

export function ScenePlanningPanel({ bookId, scene }: ScenePlanningPanelProps) {
  const charactersQuery = useCharacters(bookId);
  const locationsQuery = useLocations(bookId);
  const itemsQuery = useItems(bookId);

  const participantIds = new Set(scene.participantCharacters.map((character) => character.id));
  const itemIds = new Set(scene.items.map((item) => item.id));

  return (
    <section className="border-b border-zinc-200 bg-white px-4 py-4 md:px-7">
      <div className="mx-auto grid w-full max-w-5xl gap-4">
        <div>
          <h2 className="text-sm font-semibold text-zinc-950">Planejamento da cena</h2>
          <p className="text-xs text-zinc-500">Metadados narrativos separados do conteudo textual.</p>
        </div>

        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="grid gap-3">
            <PlanningTextarea label="Objetivo" value={scene.goal ?? ""} placeholder="Objetivo dramatico da cena." />
            <PlanningTextarea label="Conflito" value={scene.conflict ?? ""} placeholder="Forca ou obstaculo em oposicao." />
            <PlanningTextarea label="Resultado" value={scene.outcome ?? ""} placeholder="Mudanca produzida pela cena." />
          </div>

          <div className="grid gap-3">
            <label className="grid gap-1 text-xs">
              <span className="font-medium text-zinc-600">Ponto de vista</span>
              <select
                value={scene.povCharacter?.id ?? ""}
                disabled
                className="min-h-9 rounded-md border border-zinc-200 bg-zinc-50 px-3 py-1.5 text-sm text-zinc-700"
              >
                <option value="">Nenhum POV definido</option>
                {charactersQuery.data?.map((character) => (
                  <option key={character.id} value={character.id}>
                    {character.name}
                  </option>
                ))}
              </select>
            </label>
            <PlanningListState
              isLoading={charactersQuery.isLoading}
              isError={charactersQuery.isError}
              isEmpty={(charactersQuery.data?.length ?? 0) === 0}
              emptyMessage="Nenhum personagem cadastrado."
            />

            <label className="grid gap-1 text-xs">
              <span className="font-medium text-zinc-600">Localizacao principal</span>
              <select
                value={scene.mainLocation?.id ?? ""}
                disabled
                className="min-h-9 rounded-md border border-zinc-200 bg-zinc-50 px-3 py-1.5 text-sm text-zinc-700"
              >
                <option value="">Nenhuma localizacao definida</option>
                {locationsQuery.data?.map((location) => (
                  <option key={location.id} value={location.id}>
                    {location.name}
                  </option>
                ))}
              </select>
            </label>
            <PlanningListState
              isLoading={locationsQuery.isLoading}
              isError={locationsQuery.isError}
              isEmpty={(locationsQuery.data?.length ?? 0) === 0}
              emptyMessage="Nenhuma localizacao cadastrada."
            />
          </div>
        </div>

        <div className="grid gap-4 lg:grid-cols-2">
          <PlanningChecklist
            title="Personagens participantes"
            items={charactersQuery.data?.map((character) => ({
              id: character.id,
              label: character.name,
              checked: participantIds.has(character.id),
            }))}
            isLoading={charactersQuery.isLoading}
            isError={charactersQuery.isError}
            emptyMessage="Nenhum personagem cadastrado."
          />

          <PlanningChecklist
            title="Itens"
            items={itemsQuery.data?.map((item) => ({
              id: item.id,
              label: item.name,
              checked: itemIds.has(item.id),
            }))}
            isLoading={itemsQuery.isLoading}
            isError={itemsQuery.isError}
            emptyMessage="Nenhum item cadastrado."
          />
        </div>
      </div>
    </section>
  );
}

type PlanningTextareaProps = {
  label: string;
  value: string;
  placeholder: string;
};

function PlanningTextarea({ label, value, placeholder }: PlanningTextareaProps) {
  return (
    <label className="grid gap-1 text-xs">
      <span className="font-medium text-zinc-600">{label}</span>
      <Textarea
        value={value}
        rows={2}
        disabled
        placeholder={placeholder}
        className="resize-y bg-zinc-50 text-sm text-zinc-700 disabled:cursor-default disabled:bg-zinc-50 disabled:text-zinc-700"
      />
    </label>
  );
}

type PlanningListStateProps = {
  isLoading: boolean;
  isError: boolean;
  isEmpty: boolean;
  emptyMessage: string;
};

function PlanningListState({ isLoading, isError, isEmpty, emptyMessage }: PlanningListStateProps) {
  if (isLoading) {
    return <p className="text-xs text-zinc-500">Carregando lista...</p>;
  }

  if (isError) {
    return <p className="text-xs text-red-600">Nao foi possivel carregar esta lista.</p>;
  }

  if (isEmpty) {
    return <p className="text-xs text-zinc-500">{emptyMessage}</p>;
  }

  return null;
}

type PlanningChecklistItem = {
  id: string;
  label: string;
  checked: boolean;
};

type PlanningChecklistProps = {
  title: string;
  items: PlanningChecklistItem[] | undefined;
  isLoading: boolean;
  isError: boolean;
  emptyMessage: string;
};

function PlanningChecklist({ title, items, isLoading, isError, emptyMessage }: PlanningChecklistProps) {
  return (
    <div className="rounded-md border border-zinc-200 bg-zinc-50/60 p-3">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">{title}</h3>

      <div className="mt-2 grid max-h-40 gap-2 overflow-y-auto pr-1">
        {isLoading ? <p className="text-xs text-zinc-500">Carregando lista...</p> : null}
        {isError ? <FeedbackMessage variant="error">Nao foi possivel carregar esta lista.</FeedbackMessage> : null}
        {!isLoading && !isError && items?.length === 0 ? <p className="text-xs text-zinc-500">{emptyMessage}</p> : null}

        {items?.map((item) => (
          <label key={item.id} className="flex items-center gap-2 text-sm text-zinc-700">
            <input
              type="checkbox"
              checked={item.checked}
              disabled
              readOnly
              className="h-4 w-4 rounded border-zinc-300 text-zinc-900"
            />
            <span className="truncate">{item.label}</span>
          </label>
        ))}
      </div>
    </div>
  );
}
