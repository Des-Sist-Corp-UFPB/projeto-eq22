"use client";

import { type FormEvent, useEffect, useState } from "react";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Textarea } from "@/components/ui/textarea";
import { useCharacters } from "@/features/characters/api/characters-hooks";
import { useItems } from "@/features/items/api/items-hooks";
import { useLocations } from "@/features/locations/api/locations-hooks";
import { useUpdateScenePlanning } from "@/features/scene-planning/hooks/use-scene-planning";
import type { CharacterResponse } from "@/features/characters/types";
import type { ItemResponse } from "@/features/items/types";
import type { Scene } from "@/features/scenes/types";

type ScenePlanningPanelProps = {
  formId: string;
  bookId: string;
  scene: Scene;
};

export function ScenePlanningPanel({ formId, bookId, scene }: ScenePlanningPanelProps) {
  const charactersQuery = useCharacters(bookId);
  const locationsQuery = useLocations(bookId);
  const itemsQuery = useItems(bookId);
  const planningMutation = useUpdateScenePlanning(bookId, scene.id);
  const [goal, setGoal] = useState("");
  const [conflict, setConflict] = useState("");
  const [outcome, setOutcome] = useState("");
  const [planningNotes, setPlanningNotes] = useState("");
  const [povCharacterId, setPovCharacterId] = useState("");
  const [mainLocationId, setMainLocationId] = useState("");
  const [participantCharacterIds, setParticipantCharacterIds] = useState<string[]>([]);
  const [participantSearch, setParticipantSearch] = useState("");
  const [participantsExpanded, setParticipantsExpanded] = useState(false);
  const [itemIds, setItemIds] = useState<string[]>([]);
  const [itemSearch, setItemSearch] = useState("");
  const [itemsExpanded, setItemsExpanded] = useState(false);
  const sceneParticipantIdsKey = scene.participantCharacters.map((character) => character.id).join("|");
  const sceneItemIdsKey = scene.items.map((item) => item.id).join("|");

  useEffect(() => {
    planningMutation.reset();
  }, [scene.id]);

  useEffect(() => {
    setGoal(scene.goal ?? "");
    setConflict(scene.conflict ?? "");
    setOutcome(scene.outcome ?? "");
    setPlanningNotes(scene.planningNotes ?? "");
    setPovCharacterId(scene.povCharacter?.id ?? "");
    setMainLocationId(scene.mainLocation?.id ?? "");
    setParticipantCharacterIds(scene.participantCharacters.map((character) => character.id));
    setParticipantSearch("");
    setParticipantsExpanded(false);
    setItemIds(scene.items.map((item) => item.id));
    setItemSearch("");
    setItemsExpanded(false);
  }, [
    scene.id,
    scene.goal,
    scene.conflict,
    scene.outcome,
    scene.planningNotes,
    scene.povCharacter?.id,
    scene.mainLocation?.id,
    sceneParticipantIdsKey,
    sceneItemIdsKey,
  ]);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    planningMutation.mutate({
      goal: blankToNull(goal),
      conflict: blankToNull(conflict),
      outcome: blankToNull(outcome),
      planningNotes: blankToNull(planningNotes),
      povCharacterId: povCharacterId || null,
      participantCharacterIds,
      mainLocationId: mainLocationId || null,
      itemIds,
    });
  }

  function toggleParticipant(characterId: string) {
    planningMutation.reset();
    setParticipantCharacterIds((currentIds) => toggleId(currentIds, characterId));
  }

  function toggleItem(itemId: string) {
    planningMutation.reset();
    setItemIds((currentIds) => toggleId(currentIds, itemId));
  }

  const selectedParticipantIds = new Set(participantCharacterIds);
  const selectedItemIds = new Set(itemIds);
  const showPlanningFeedback = (planningMutation.isSuccess && planningMutation.data?.id === scene.id) || planningMutation.isError;

  return (
    <form
      id={formId}
      onSubmit={handleSubmit}
      className="w-full px-4 pt-4"
    >
      <div className="grid w-full gap-4">
        <div className="grid gap-4">
          <div className="grid gap-3">
            <PlanningTextarea
              label="Objetivo"
              value={goal}
              placeholder="Objetivo dramatico da cena."
              onChange={(value) => {
                planningMutation.reset();
                setGoal(value);
              }}
            />
            <PlanningTextarea
              label="Conflito"
              value={conflict}
              placeholder="Forca ou obstaculo em oposicao."
              onChange={(value) => {
                planningMutation.reset();
                setConflict(value);
              }}
            />
            <PlanningTextarea
              label="Resultado"
              value={outcome}
              placeholder="Mudanca produzida pela cena."
              onChange={(value) => {
                planningMutation.reset();
                setOutcome(value);
              }}
            />
            <PlanningTextarea
              label="Notas da cena"
              value={planningNotes}
              placeholder="Lembretes livres: revisar dialogo, pesquisar termo historico, corrigir continuidade."
              rows={4}
              onChange={(value) => {
                planningMutation.reset();
                setPlanningNotes(value);
              }}
            />
          </div>

          <div className="grid gap-3">
            <label className="grid gap-1 text-xs">
              <span className="font-medium text-zinc-600">Ponto de vista</span>
              <select
                value={povCharacterId}
                onChange={(event) => {
                  planningMutation.reset();
                  setPovCharacterId(event.target.value);
                }}
                className="min-h-9 rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm text-zinc-900 outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200"
              >
                <option value="">Nenhum</option>
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
                value={mainLocationId}
                onChange={(event) => {
                  planningMutation.reset();
                  setMainLocationId(event.target.value);
                }}
                className="min-h-9 rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm text-zinc-900 outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200"
              >
                <option value="">Nenhuma</option>
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

        <div className="grid gap-4">
          <ParticipantSelector
            characters={charactersQuery.data}
            selectedIds={selectedParticipantIds}
            search={participantSearch}
            expanded={participantsExpanded}
            isLoading={charactersQuery.isLoading}
            isError={charactersQuery.isError}
            onSearchChange={setParticipantSearch}
            onExpandedChange={setParticipantsExpanded}
            onToggle={toggleParticipant}
          />

          <ItemSelector
            items={itemsQuery.data}
            selectedIds={selectedItemIds}
            search={itemSearch}
            expanded={itemsExpanded}
            isLoading={itemsQuery.isLoading}
            isError={itemsQuery.isError}
            onSearchChange={setItemSearch}
            onExpandedChange={setItemsExpanded}
            onToggle={toggleItem}
          />
        </div>

        {showPlanningFeedback ? (
          <div className="-mx-4 border-t border-zinc-200 bg-zinc-50/95 px-4 py-3">
            {planningMutation.isSuccess && planningMutation.data?.id === scene.id ? (
              <FeedbackMessage variant="success">Planejamento salvo.</FeedbackMessage>
            ) : null}

            {planningMutation.isError ? (
              <FeedbackMessage variant="error">
                Nao foi possivel salvar o planejamento. Verifique os vinculos e tente novamente.
              </FeedbackMessage>
            ) : null}
          </div>
        ) : null}
      </div>
    </form>
  );
}

type ParticipantSelectorProps = {
  characters: CharacterResponse[] | undefined;
  selectedIds: Set<string>;
  search: string;
  expanded: boolean;
  isLoading: boolean;
  isError: boolean;
  onSearchChange: (search: string) => void;
  onExpandedChange: (expanded: boolean) => void;
  onToggle: (id: string) => void;
};

function ParticipantSelector({
  characters,
  selectedIds,
  search,
  expanded,
  isLoading,
  isError,
  onSearchChange,
  onExpandedChange,
  onToggle,
}: ParticipantSelectorProps) {
  const selectedCharacters = characters?.filter((character) => selectedIds.has(character.id)) ?? [];
  const normalizedSearch = normalizeSearch(search);
  const otherCharacters = characters?.filter((character) => {
    if (selectedIds.has(character.id)) {
      return false;
    }

    if (!normalizedSearch) {
      return true;
    }

    return characterMatchesSearch(character, normalizedSearch);
  }) ?? [];
  const selectedCount = selectedIds.size;
  const selectedSummary = selectedCharacters.map((character) => character.name).join(", ");

  return (
    <div className="rounded-md border border-zinc-200 bg-zinc-50/60 p-3">
      <div className="grid gap-2">
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Personagens participantes</h3>
          <p className="mt-1 text-xs text-zinc-500">{selectedCount} {selectedCount === 1 ? "selecionado" : "selecionados"}</p>
        </div>

        {selectedSummary ? (
          <p className="line-clamp-2 text-sm text-zinc-700">{selectedSummary}</p>
        ) : null}
      </div>

      <button
        type="button"
        aria-expanded={expanded}
        aria-label={expanded ? "Recolher lista de personagens participantes" : "Expandir lista de personagens participantes"}
        onClick={() => onExpandedChange(!expanded)}
        className="mt-3 flex min-h-9 w-full items-center justify-between gap-3 rounded-md border border-zinc-200 bg-white px-3 py-2 text-left text-sm font-medium text-zinc-800 transition hover:border-zinc-300 hover:bg-zinc-50 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-2"
      >
        <span>Gerenciar participantes</span>
        <span
          aria-hidden="true"
          className={`h-2.5 w-2.5 shrink-0 rotate-45 border-b-2 border-r-2 border-zinc-500 transition-transform ${
            expanded ? "rotate-45" : "-rotate-45"
          }`}
        />
      </button>

      {expanded ? (
        <div className="mt-3 grid gap-3">
          <label className="block text-xs">
            <span className="sr-only">Buscar personagem participante</span>
            <input
              value={search}
              onChange={(event) => onSearchChange(event.target.value)}
              placeholder="Buscar personagem..."
              className="min-h-9 w-full rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm text-zinc-900 outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200"
            />
          </label>

          <div className="grid max-h-72 gap-3 overflow-y-auto pr-1">
            {isLoading ? <p className="text-xs text-zinc-500">Carregando lista...</p> : null}
            {isError ? <FeedbackMessage variant="error">Nao foi possivel carregar esta lista.</FeedbackMessage> : null}
            {!isLoading && !isError && characters?.length === 0 ? <p className="text-xs text-zinc-500">Nenhum personagem cadastrado.</p> : null}

            {!isLoading && !isError && selectedCharacters.length > 0 ? (
              <div className="grid gap-1.5">
                <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">Selecionados</h4>
                {selectedCharacters.map((character) => (
                  <ParticipantRow key={character.id} character={character} checked onToggle={onToggle} highlighted />
                ))}
              </div>
            ) : null}

            {!isLoading && !isError && characters && characters.length > 0 ? (
              <div className="grid gap-1.5">
                <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">
                  {selectedCharacters.length > 0 ? "Outros personagens" : "Todos os personagens"}
                </h4>
                {otherCharacters.length > 0 ? (
                  otherCharacters.map((character) => (
                    <ParticipantRow
                      key={character.id}
                      character={character}
                      checked={selectedIds.has(character.id)}
                      onToggle={onToggle}
                    />
                  ))
                ) : (
                  <p className="text-xs text-zinc-500">Nenhum personagem encontrado.</p>
                )}
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}

type ParticipantRowProps = {
  character: CharacterResponse;
  checked: boolean;
  highlighted?: boolean;
  onToggle: (id: string) => void;
};

function ParticipantRow({ character, checked, highlighted = false, onToggle }: ParticipantRowProps) {
  return (
    <label
      className={`flex cursor-pointer items-start gap-2 rounded-md border px-2.5 py-2 text-sm transition ${
        highlighted ? "border-zinc-300 bg-white shadow-sm" : "border-zinc-200 bg-white/70 hover:border-zinc-300 hover:bg-white"
      }`}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={() => onToggle(character.id)}
        className="mt-0.5 h-4 w-4 shrink-0 rounded border-zinc-300 text-zinc-900"
      />
      <span className="min-w-0">
        <span className="block truncate font-medium text-zinc-900">{character.name}</span>
        {character.nickname || character.narrativeFunction ? (
          <span className="mt-0.5 block truncate text-xs text-zinc-500">
            {[character.nickname, character.narrativeFunction].filter(Boolean).join(" · ")}
          </span>
        ) : null}
      </span>
    </label>
  );
}

type ItemSelectorProps = {
  items: ItemResponse[] | undefined;
  selectedIds: Set<string>;
  search: string;
  expanded: boolean;
  isLoading: boolean;
  isError: boolean;
  onSearchChange: (search: string) => void;
  onExpandedChange: (expanded: boolean) => void;
  onToggle: (id: string) => void;
};

function ItemSelector({
  items,
  selectedIds,
  search,
  expanded,
  isLoading,
  isError,
  onSearchChange,
  onExpandedChange,
  onToggle,
}: ItemSelectorProps) {
  const selectedItems = items?.filter((item) => selectedIds.has(item.id)) ?? [];
  const normalizedSearch = normalizeSearch(search);
  const otherItems = items?.filter((item) => {
    if (selectedIds.has(item.id)) {
      return false;
    }

    if (!normalizedSearch) {
      return true;
    }

    return itemMatchesSearch(item, normalizedSearch);
  }) ?? [];
  const selectedCount = selectedIds.size;
  const selectedSummary = selectedItems.map((item) => item.name).join(", ");

  return (
    <div className="rounded-md border border-zinc-200 bg-zinc-50/60 p-3">
      <div className="grid gap-2">
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Itens</h3>
          <p className="mt-1 text-xs text-zinc-500">{selectedCount} {selectedCount === 1 ? "selecionado" : "selecionados"}</p>
        </div>

        {selectedSummary ? <p className="line-clamp-2 text-sm text-zinc-700">{selectedSummary}</p> : null}
      </div>

      <button
        type="button"
        aria-expanded={expanded}
        aria-label={expanded ? "Recolher lista de itens da cena" : "Expandir lista de itens da cena"}
        onClick={() => onExpandedChange(!expanded)}
        className="mt-3 flex min-h-9 w-full items-center justify-between gap-3 rounded-md border border-zinc-200 bg-white px-3 py-2 text-left text-sm font-medium text-zinc-800 transition hover:border-zinc-300 hover:bg-zinc-50 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-2"
      >
        <span>Gerenciar itens</span>
        <span
          aria-hidden="true"
          className={`h-2.5 w-2.5 shrink-0 rotate-45 border-b-2 border-r-2 border-zinc-500 transition-transform ${
            expanded ? "rotate-45" : "-rotate-45"
          }`}
        />
      </button>

      {expanded ? (
        <div className="mt-3 grid gap-3">
          <label className="block text-xs">
            <span className="sr-only">Buscar item da cena</span>
            <input
              value={search}
              onChange={(event) => onSearchChange(event.target.value)}
              placeholder="Buscar item..."
              className="min-h-9 w-full rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm text-zinc-900 outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200"
            />
          </label>

          <div className="grid max-h-72 gap-3 overflow-y-auto pr-1">
            {isLoading ? <p className="text-xs text-zinc-500">Carregando lista...</p> : null}
            {isError ? <FeedbackMessage variant="error">Nao foi possivel carregar esta lista.</FeedbackMessage> : null}
            {!isLoading && !isError && items?.length === 0 ? <p className="text-xs text-zinc-500">Nenhum item cadastrado.</p> : null}

            {!isLoading && !isError && selectedItems.length > 0 ? (
              <div className="grid gap-1.5">
                <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">Selecionados</h4>
                {selectedItems.map((item) => (
                  <ItemRow key={item.id} item={item} checked onToggle={onToggle} highlighted />
                ))}
              </div>
            ) : null}

            {!isLoading && !isError && items && items.length > 0 ? (
              <div className="grid gap-1.5">
                <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">
                  {selectedItems.length > 0 ? "Outros itens" : "Todos os itens"}
                </h4>
                {otherItems.length > 0 ? (
                  otherItems.map((item) => (
                    <ItemRow key={item.id} item={item} checked={selectedIds.has(item.id)} onToggle={onToggle} />
                  ))
                ) : (
                  <p className="text-xs text-zinc-500">Nenhum item encontrado.</p>
                )}
              </div>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}

type ItemRowProps = {
  item: ItemResponse;
  checked: boolean;
  highlighted?: boolean;
  onToggle: (id: string) => void;
};

function ItemRow({ item, checked, highlighted = false, onToggle }: ItemRowProps) {
  const details = [item.type, item.origin, item.narrativeImportance].filter(Boolean).join(" · ");

  return (
    <label
      className={`flex cursor-pointer items-start gap-2 rounded-md border px-2.5 py-2 text-sm transition ${
        highlighted ? "border-zinc-300 bg-white shadow-sm" : "border-zinc-200 bg-white/70 hover:border-zinc-300 hover:bg-white"
      }`}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={() => onToggle(item.id)}
        className="mt-0.5 h-4 w-4 shrink-0 rounded border-zinc-300 text-zinc-900"
      />
      <span className="min-w-0">
        <span className="block truncate font-medium text-zinc-900">{item.name}</span>
        {details ? <span className="mt-0.5 block truncate text-xs text-zinc-500">{details}</span> : null}
      </span>
    </label>
  );
}

type PlanningTextareaProps = {
  label: string;
  value: string;
  placeholder: string;
  rows?: number;
  onChange: (value: string) => void;
};

function PlanningTextarea({ label, value, placeholder, rows = 2, onChange }: PlanningTextareaProps) {
  return (
    <label className="grid gap-1 text-xs">
      <span className="font-medium text-zinc-600">{label}</span>
      <Textarea
        value={value}
        rows={rows}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        className="resize-y bg-white text-sm text-zinc-900 focus:ring-2 focus:ring-zinc-200"
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
  onToggle: (id: string) => void;
};

function PlanningChecklist({ title, items, isLoading, isError, emptyMessage, onToggle }: PlanningChecklistProps) {
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
              onChange={() => onToggle(item.id)}
              className="h-4 w-4 rounded border-zinc-300 text-zinc-900"
            />
            <span className="truncate">{item.label}</span>
          </label>
        ))}
      </div>
    </div>
  );
}

function blankToNull(value: string) {
  const trimmedValue = value.trim();
  return trimmedValue ? trimmedValue : null;
}

function toggleId(ids: string[], id: string) {
  if (ids.includes(id)) {
    return ids.filter((currentId) => currentId !== id);
  }

  return [...ids, id];
}

function normalizeSearch(value: string) {
  return value.trim().toLocaleLowerCase();
}

function characterMatchesSearch(character: CharacterResponse, normalizedSearch: string) {
  return [character.name, character.nickname, character.narrativeFunction]
    .filter(Boolean)
    .some((value) => value?.toLocaleLowerCase().includes(normalizedSearch));
}

function itemMatchesSearch(item: ItemResponse, normalizedSearch: string) {
  return [item.name, item.type, item.origin, item.narrativeImportance, item.currentOwnerCharacterId]
    .filter(Boolean)
    .some((value) => value?.toLocaleLowerCase().includes(normalizedSearch));
}
