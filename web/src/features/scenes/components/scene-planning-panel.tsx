"use client";

import { type FormEvent, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Textarea } from "@/components/ui/textarea";
import { useCharacters } from "@/features/characters/api/characters-hooks";
import { useItems } from "@/features/items/api/items-hooks";
import { useLocations } from "@/features/locations/api/locations-hooks";
import { useUpdateScenePlanning } from "@/features/scene-planning/hooks/use-scene-planning";
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
  const [povCharacterId, setPovCharacterId] = useState("");
  const [mainLocationId, setMainLocationId] = useState("");
  const [participantCharacterIds, setParticipantCharacterIds] = useState<string[]>([]);
  const [itemIds, setItemIds] = useState<string[]>([]);

  useEffect(() => {
    setGoal(scene.goal ?? "");
    setConflict(scene.conflict ?? "");
    setOutcome(scene.outcome ?? "");
    setPovCharacterId(scene.povCharacter?.id ?? "");
    setMainLocationId(scene.mainLocation?.id ?? "");
    setParticipantCharacterIds(scene.participantCharacters.map((character) => character.id));
    setItemIds(scene.items.map((item) => item.id));
    planningMutation.reset();
  }, [
    scene.id,
    scene.goal,
    scene.conflict,
    scene.outcome,
    scene.povCharacter?.id,
    scene.mainLocation?.id,
    scene.participantCharacters,
    scene.items,
  ]);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    planningMutation.mutate({
      goal: blankToNull(goal),
      conflict: blankToNull(conflict),
      outcome: blankToNull(outcome),
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
    <form onSubmit={handleSubmit} className="border-b border-zinc-200 bg-white px-4 py-4 md:px-7">
      <div className="mx-auto grid w-full max-w-5xl gap-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-zinc-950">Planejamento da cena</h2>
            <p className="text-xs text-zinc-500">Metadados narrativos separados do conteudo textual.</p>
          </div>
          <Button type="submit" size="sm" disabled={planningMutation.isPending}>
            {planningMutation.isPending ? "Salvando..." : "Salvar planejamento"}
          </Button>
        </div>

        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
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

        <div className="grid gap-4 lg:grid-cols-2">
          <PlanningChecklist
            title="Personagens participantes"
            items={charactersQuery.data?.map((character) => ({
              id: character.id,
              label: character.name,
              checked: selectedParticipantIds.has(character.id),
            }))}
            isLoading={charactersQuery.isLoading}
            isError={charactersQuery.isError}
            emptyMessage="Nenhum personagem cadastrado."
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

type PlanningTextareaProps = {
  label: string;
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
};

function PlanningTextarea({ label, value, placeholder, onChange }: PlanningTextareaProps) {
  return (
    <label className="grid gap-1 text-xs">
      <span className="font-medium text-zinc-600">{label}</span>
      <Textarea
        value={value}
        rows={2}
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
