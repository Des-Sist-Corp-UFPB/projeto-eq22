import { fireEvent, screen, within } from "@testing-library/react";
import React from "react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { ScenePlanningPanel } from "@/features/scenes/components/scene-planning-panel";
import {
  characterAda,
  characterBruno,
  itemKey,
  itemMap,
  locationLibrary,
  sceneForPlanning,
} from "@/test/fixtures";
import { renderWithClient } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  useCharacters: vi.fn(),
  useLocations: vi.fn(),
  useItems: vi.fn(),
  useUpdateScenePlanning: vi.fn(),
  mutate: vi.fn(),
  reset: vi.fn(),
}));

vi.mock("@/features/characters/api/characters-hooks", () => ({
  useCharacters: mocks.useCharacters,
}));

vi.mock("@/features/locations/api/locations-hooks", () => ({
  useLocations: mocks.useLocations,
}));

vi.mock("@/features/items/api/items-hooks", () => ({
  useItems: mocks.useItems,
}));

vi.mock("@/features/scene-planning/hooks/use-scene-planning", () => ({
  useUpdateScenePlanning: mocks.useUpdateScenePlanning,
}));

describe("ScenePlanningPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.useCharacters.mockReturnValue({
      isLoading: false,
      isError: false,
      data: [characterAda, characterBruno],
    });
    mocks.useLocations.mockReturnValue({
      isLoading: false,
      isError: false,
      data: [locationLibrary],
    });
    mocks.useItems.mockReturnValue({
      isLoading: false,
      isError: false,
      data: [itemKey, itemMap],
    });
    mocks.useUpdateScenePlanning.mockReturnValue({
      mutate: mocks.mutate,
      reset: mocks.reset,
      isPending: false,
      isSuccess: false,
      isError: false,
      data: null,
      error: null,
    });
  });

  test("renderiza campos de planejamento e listas recolhidas", () => {
    renderWithClient(<ScenePlanningPanel formId="planning-form" bookId="book-1" scene={sceneForPlanning} />);

    expect(screen.getByLabelText("Objetivo")).toBeInTheDocument();
    expect(screen.getByLabelText("Conflito")).toBeInTheDocument();
    expect(screen.getByLabelText("Resultado")).toBeInTheDocument();
    expect(screen.getByLabelText("Notas da cena")).toBeInTheDocument();
    expect(screen.getByLabelText("Ponto de vista")).toBeInTheDocument();
    expect(screen.getByLabelText("Localizacao principal")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Expandir lista de personagens participantes" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Expandir lista de itens da cena" })).toBeInTheDocument();
  });

  test("permite selecionar POV, localizacao, participantes e itens e monta payload de planning", () => {
    const { container } = renderWithClient(
      <ScenePlanningPanel formId="planning-form" bookId="book-1" scene={sceneForPlanning} />
    );

    fireEvent.change(screen.getByLabelText("Objetivo"), { target: { value: "Encontrar a chave" } });
    fireEvent.change(screen.getByLabelText("Conflito"), { target: { value: "A porta esta trancada" } });
    fireEvent.change(screen.getByLabelText("Resultado"), { target: { value: "Ada decide voltar" } });
    fireEvent.change(screen.getByLabelText("Notas da cena"), { target: { value: "Revisar pista" } });
    fireEvent.change(screen.getByLabelText("Ponto de vista"), { target: { value: characterAda.id } });
    fireEvent.change(screen.getByLabelText("Localizacao principal"), { target: { value: locationLibrary.id } });

    fireEvent.click(screen.getByRole("button", { name: "Expandir lista de personagens participantes" }));
    fireEvent.click(checkboxForText("Ada"));
    fireEvent.click(checkboxForText("Bruno"));
    fireEvent.click(checkboxForText("Bruno"));

    fireEvent.click(screen.getByRole("button", { name: "Expandir lista de itens da cena" }));
    const itemsRegion = screen.getByText("Todos os itens").closest("div");
    expect(itemsRegion).not.toBeNull();
    fireEvent.click(within(itemsRegion as HTMLElement).getByLabelText(/Chave de prata/));

    const form = container.querySelector("form");
    expect(form).not.toBeNull();
    fireEvent.submit(form as HTMLFormElement);

    expect(mocks.mutate).toHaveBeenCalledWith({
      goal: "Encontrar a chave",
      conflict: "A porta esta trancada",
      outcome: "Ada decide voltar",
      planningNotes: "Revisar pista",
      povCharacterId: characterAda.id,
      participantCharacterIds: [characterAda.id],
      mainLocationId: locationLibrary.id,
      itemIds: [itemKey.id],
    });
  });

  test("envia null para campos textuais e vinculos opcionais vazios", () => {
    const { container } = renderWithClient(
      <ScenePlanningPanel formId="planning-form" bookId="book-1" scene={sceneForPlanning} />
    );

    fireEvent.change(screen.getByLabelText("Objetivo"), { target: { value: "   " } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    expect(mocks.mutate).toHaveBeenCalledWith({
      goal: null,
      conflict: null,
      outcome: null,
      planningNotes: null,
      povCharacterId: null,
      participantCharacterIds: [],
      mainLocationId: null,
      itemIds: [],
    });
  });

  test("mostra feedback de sucesso", () => {
    mocks.useUpdateScenePlanning.mockReturnValue({
      mutate: mocks.mutate,
      reset: mocks.reset,
      isPending: false,
      isSuccess: true,
      isError: false,
      data: sceneForPlanning,
      error: null,
    });

    renderWithClient(<ScenePlanningPanel formId="planning-form" bookId="book-1" scene={sceneForPlanning} />);

    expect(screen.getByText("Planejamento salvo.")).toBeInTheDocument();
  });

  test("mostra feedback de erro", () => {
    mocks.useUpdateScenePlanning.mockReturnValue({
      mutate: mocks.mutate,
      reset: mocks.reset,
      isPending: false,
      isSuccess: false,
      isError: true,
      data: null,
      error: new Error("Falha"),
    });

    renderWithClient(<ScenePlanningPanel formId="planning-form" bookId="book-1" scene={sceneForPlanning} />);

    expect(screen.getByText(/Nao foi possivel salvar o planejamento/)).toBeInTheDocument();
  });
});

function checkboxForText(text: string) {
  const label = screen
    .getAllByText(text)
    .map((element) => element.closest("label"))
    .find((element): element is HTMLLabelElement => Boolean(element?.querySelector("input[type='checkbox']")));
  expect(label).not.toBeNull();
  return within(label).getByRole("checkbox");
}
