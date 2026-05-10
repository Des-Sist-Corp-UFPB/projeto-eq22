"use client";

import { type FormEvent, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Textarea } from "@/components/ui/textarea";
import { useCharacters } from "@/features/characters/api/characters-hooks";
import { useItems } from "@/features/items/api/items-hooks";
import { useLocations } from "@/features/locations/api/locations-hooks";
import { useUpdateScenePlanning } from "@/features/scene-planning/hooks/use-scene-planning";
import type { CharacterResponse } from "@/features/characters/types";
import type { Scene } from "@/features/scenes/types";

type ScenePlanningPanelProps = {
  bookId: string;
  scene: Scene;
};

export function ScenePlanningPanel({ bookId, scene }: ScenePlanningPanelProps) {
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
  const [itemIds, setItemIds] = useState<string[]>([]);
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
    setItemIds(scene.items.map((item) => item.id));
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

  return (
    <form
      onSubmit={handleSubmit}
      className="min-h-0 overflow-y-auto border-t border-zinc-200 bg-zinc-50/80 px-4 py-4 lg:border-l lg:border-t-0"
    >
      <div className="grid w-full gap-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-zinc-950">Planejamento da cena</h2>
            <p className="text-xs text-zinc-500">Metadados narrativos separados do conteudo textual.</p>
          </div>
          <Button type="submit" size="sm" disabled={planningMutation.isPending}>
            {planningMutation.isPending ? "Salvando..." : "Salvar planejamento"}
          </Button>
        </div>

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
            isLoading={charactersQuery.isLoading}
            isError={charactersQuery.isError}
            onSearchChange={setParticipantSearch}
            onToggle={toggleParticipant}
          />

          <PlanningChecklist
            title="Itens"
            items={itemsQuery.data?.map((item) => ({
              id: item.id,
              label: item.name,
              checked: selectedItemIds.has(item.id),
            }))}
            isLoading={itemsQuery.isLoading}
            isError={itemsQuery.isError}
            emptyMessage="Nenhum item cadastrado."
            onToggle={toggleItem}
          />
        </div>

        <div className="min-h-8">
          {planningMutation.isSuccess && planningMutation.data?.id === scene.id ? (
            <FeedbackMessage variant="success">Planejamento salvo.</FeedbackMessage>
          ) : null}

          {planningMutation.isError ? (
            <FeedbackMessage variant="error">
              Nao foi possivel salvar o planejamento. Verifique os vinculos e tente novamente.
            </FeedbackMessage>
          ) : null}
        </div>
      </div>
    </form>
  );
}

type ParticipantSelectorProps = {
  characters: CharacterResponse[] | undefined;
  selectedIds: Set<string>;
  search: string;
  isLoading: boolean;
  isError: boolean;
  onSearchChange: (search: string) => void;
  onToggle: (id: string) => void;
};

function ParticipantSelector({
  characters,
  selectedIds,
  search,
  isLoading,
  isError,
  onSearchChange,
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

  return (
    <div className="rounded-md border border-zinc-200 bg-zinc-50/60 p-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Personagens participantes</h3>
          <p className="mt-1 text-xs text-zinc-500">{selectedCount} {selectedCount === 1 ? "selecionado" : "selecionados"}</p>
        </div>
      </div>

      <label className="mt-3 block text-xs">
        <span className="sr-only">Buscar personagem participante</span>
        <input
          value={search}
          onChange={(event) => onSearchChange(event.target.value)}
          placeholder="Buscar personagem..."
          className="min-h-9 w-full rounded-md border border-zinc-300 bg-white px-3 py-1.5 text-sm text-zinc-900 outline-none transition focus:border-zinc-800 focus:ring-2 focus:ring-zinc-200"
        />
      </label>

      <div className="mt-3 grid max-h-72 gap-3 overflow-y-auto pr-1">
        {isLoading ? <p className="text-xs text-zinc-500">Carregando lista...</p> : null}
        {isError ? <FeedbackMessage variant="error">Nao foi possivel carregar esta lista.</FeedbackMessage> : null}
        {!isLoading && !isError && characters?.length === 0 ? <p className="text-xs text-zinc-500">Nenhum personagem cadastrado.</p> : null}

        {!isLoading && !isError && selectedCharacters.length > 0 ? (
          <div className="grid gap-2">
            <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">Selecionados</h4>
            <div className="grid gap-1.5">
              {selectedCharacters.map((character) => (
                <ParticipantRow key={character.id} character={character} checked onToggle={onToggle} highlighted />
              ))}
            </div>
          </div>
        ) : null}

        {!isLoading && !isError && characters && characters.length > 0 ? (
          <div className="grid gap-2">
            <h4 className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">
              {selectedCharacters.length > 0 ? "Outros personagens" : "Todos os personagens"}
            </h4>
            {otherCharacters.length > 0 ? (
              <div className="grid gap-1.5">
                {otherCharacters.map((character) => (
                  <ParticipantRow
                    key={character.id}
                    character={character}
                    checked={selectedIds.has(character.id)}
                    onToggle={onToggle}
                  />
                ))}
              </div>
            ) : (
              <p className="text-xs text-zinc-500">Nenhum personagem encontrado.</p>
            )}
          </div>
        ) : null}
      </div>
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
