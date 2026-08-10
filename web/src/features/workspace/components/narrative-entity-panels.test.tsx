import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { CharactersPanel } from "@/features/characters/components/characters-panel";
import type { CharacterResponse } from "@/features/characters/types";
import { ItemsPanel } from "@/features/items/components/items-panel";
import type { ItemResponse } from "@/features/items/types";
import { LocationsPanel } from "@/features/locations/components/locations-panel";
import type { LocationResponse } from "@/features/locations/types";

const hooks = vi.hoisted(() => ({
  useCharacters: vi.fn(),
  useCreateCharacter: vi.fn(),
  useUpdateCharacter: vi.fn(),
  useDeleteCharacter: vi.fn(),
  useLocations: vi.fn(),
  useCreateLocation: vi.fn(),
  useUpdateLocation: vi.fn(),
  useDeleteLocation: vi.fn(),
  useItems: vi.fn(),
  useCreateItem: vi.fn(),
  useUpdateItem: vi.fn(),
  useDeleteItem: vi.fn(),
}));

vi.mock("@/features/characters/api/characters-hooks", () => ({
  useCharacters: hooks.useCharacters,
  useCreateCharacter: hooks.useCreateCharacter,
  useUpdateCharacter: hooks.useUpdateCharacter,
  useDeleteCharacter: hooks.useDeleteCharacter,
}));

vi.mock("@/features/locations/api/locations-hooks", () => ({
  useLocations: hooks.useLocations,
  useCreateLocation: hooks.useCreateLocation,
  useUpdateLocation: hooks.useUpdateLocation,
  useDeleteLocation: hooks.useDeleteLocation,
}));

vi.mock("@/features/items/api/items-hooks", () => ({
  useItems: hooks.useItems,
  useCreateItem: hooks.useCreateItem,
  useUpdateItem: hooks.useUpdateItem,
  useDeleteItem: hooks.useDeleteItem,
}));

const character: CharacterResponse = {
  id: "character-1",
  bookId: "book-1",
  name: "Alice",
  nickname: "Ali",
  age: 31,
  sex: "F",
  narrativeFunction: "Protagonista",
  goal: "Encontrar a chave",
  conflict: "Medo de falhar",
  arc: "Aprender a confiar",
  physicalDescription: "Cabelos escuros",
  personality: "Curiosa",
  biography: "Cartógrafa",
  notes: "Carrega um mapa",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const location: LocationResponse = {
  id: "location-1",
  bookId: "book-1",
  name: "Torre",
  type: "Ruína",
  description: "Uma torre no penhasco",
  historyContext: "Foi abandonada",
  narrativeImportance: "Guarda a chave",
  notes: "Acesso pela maré baixa",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const item: ItemResponse = {
  id: "item-1",
  bookId: "book-1",
  name: "Chave",
  type: "Artefato",
  description: "Chave de bronze",
  origin: "Torre",
  currentOwnerCharacterId: character.id,
  currentOwnerCharacter: { id: character.id, name: character.name, nickname: character.nickname },
  narrativeImportance: "Abre o arquivo",
  notes: "Tem uma inscrição",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function query<T>(data: T) {
  return { data, isLoading: false, isError: false };
}

function mutation() {
  return {
    mutate: vi.fn(),
    reset: vi.fn(),
    error: null,
    isPending: false,
    variables: undefined,
  };
}

describe("narrative entity panels", () => {
  let characterCreate: ReturnType<typeof mutation>;
  let characterUpdate: ReturnType<typeof mutation>;
  let characterDelete: ReturnType<typeof mutation>;
  let locationCreate: ReturnType<typeof mutation>;
  let locationUpdate: ReturnType<typeof mutation>;
  let locationDelete: ReturnType<typeof mutation>;
  let itemCreate: ReturnType<typeof mutation>;
  let itemUpdate: ReturnType<typeof mutation>;
  let itemDelete: ReturnType<typeof mutation>;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, "confirm").mockReturnValue(true);

    characterCreate = mutation();
    characterUpdate = mutation();
    characterDelete = mutation();
    locationCreate = mutation();
    locationUpdate = mutation();
    locationDelete = mutation();
    itemCreate = mutation();
    itemUpdate = mutation();
    itemDelete = mutation();

    hooks.useCharacters.mockReturnValue(query([character]));
    hooks.useCreateCharacter.mockReturnValue(characterCreate);
    hooks.useUpdateCharacter.mockReturnValue(characterUpdate);
    hooks.useDeleteCharacter.mockReturnValue(characterDelete);
    hooks.useLocations.mockReturnValue(query([location]));
    hooks.useCreateLocation.mockReturnValue(locationCreate);
    hooks.useUpdateLocation.mockReturnValue(locationUpdate);
    hooks.useDeleteLocation.mockReturnValue(locationDelete);
    hooks.useItems.mockReturnValue(query([item]));
    hooks.useCreateItem.mockReturnValue(itemCreate);
    hooks.useUpdateItem.mockReturnValue(itemUpdate);
    hooks.useDeleteItem.mockReturnValue(itemDelete);
  });

  test("creates, validates, edits, and deletes characters", () => {
    characterCreate.mutate.mockImplementation((payload, options) =>
      options.onSuccess({ ...character, id: "character-2", ...payload }),
    );
    characterUpdate.mutate.mockImplementation((variables, options) =>
      options.onSuccess({ ...character, ...variables.payload }),
    );

    render(<CharactersPanel bookId="book-1" />);

    expect(screen.getByText("Alice")).toBeInTheDocument();
    expect(screen.getByText("31 anos")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Editar" }));
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Alice Silva" } });
    fireEvent.change(screen.getByLabelText("Idade"), { target: { value: "32" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar personagem" }));

    expect(characterUpdate.mutate).toHaveBeenCalledWith(
      expect.objectContaining({
        characterId: character.id,
        payload: expect.objectContaining({ name: "Alice Silva", age: 32 }),
      }),
      expect.any(Object),
    );

    fireEvent.click(screen.getByRole("button", { name: "Novo personagem" }));
    fireEvent.click(screen.getByRole("button", { name: "Criar personagem" }));
    expect(screen.getByText("Informe o nome do personagem.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "  Bruno  " } });
    fireEvent.change(screen.getByLabelText("Idade"), { target: { value: "-1" } });
    fireEvent.submit(screen.getByRole("button", { name: "Criar personagem" }).closest("form")!);
    expect(screen.getByText("A idade deve ser zero ou maior.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Idade"), { target: { value: "20" } });
    fireEvent.change(screen.getByLabelText("Apelido"), { target: { value: " B " } });
    fireEvent.change(screen.getByLabelText("Sexo"), { target: { value: "M" } });
    fireEvent.change(screen.getByLabelText(/Fun/), { target: { value: "Aliado" } });
    fireEvent.change(screen.getByLabelText("Objetivo"), { target: { value: "Ajudar" } });
    fireEvent.change(screen.getByLabelText("Conflito"), { target: { value: "Dúvida" } });
    fireEvent.change(screen.getByLabelText("Arco"), { target: { value: "Coragem" } });
    fireEvent.change(screen.getByLabelText(/f.sica/i), { target: { value: "Alto" } });
    fireEvent.change(screen.getByLabelText("Personalidade"), { target: { value: "Leal" } });
    fireEvent.change(screen.getByLabelText("Biografia"), { target: { value: "Ferreiro" } });
    fireEvent.change(screen.getByLabelText("Notas"), { target: { value: "Sem segredo" } });
    fireEvent.click(screen.getByRole("button", { name: "Criar personagem" }));

    expect(characterCreate.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ name: "Bruno", nickname: "B", age: 20 }),
      expect.any(Object),
    );

    fireEvent.click(screen.getByRole("button", { name: "Excluir" }));
    expect(characterDelete.mutate).toHaveBeenCalledWith(character.id, expect.any(Object));
  });

  test("creates, edits, and deletes locations", () => {
    locationCreate.mutate.mockImplementation((payload, options) =>
      options.onSuccess({ ...location, id: "location-2", ...payload }),
    );
    locationUpdate.mutate.mockImplementation((variables, options) =>
      options.onSuccess({ ...location, ...variables.payload }),
    );

    render(<LocationsPanel bookId="book-1" />);

    expect(screen.getByText("Torre")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Editar" }));
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Torre Norte" } });
    fireEvent.click(screen.getByRole("button", { name: /Salvar localiza/ }));
    expect(locationUpdate.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ locationId: location.id }),
      expect.any(Object),
    );

    fireEvent.click(screen.getByRole("button", { name: /Nova localiza/ }));
    fireEvent.click(screen.getByRole("button", { name: /Criar localiza/ }));
    expect(screen.getByText(/Informe o nome da localiza/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "  Porto  " } });
    fireEvent.change(screen.getByLabelText("Tipo"), { target: { value: " Cidade " } });
    fireEvent.change(screen.getByLabelText(/Descri/), { target: { value: "Mar aberto" } });
    fireEvent.change(screen.getByLabelText(/Contexto/), { target: { value: "Antigo" } });
    fireEvent.change(screen.getByLabelText(/Import.ncia narrativa/), { target: { value: "Partida" } });
    fireEvent.change(screen.getByLabelText("Notas"), { target: { value: "Ventos fortes" } });
    fireEvent.click(screen.getByRole("button", { name: /Criar localiza/ }));
    expect(locationCreate.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ name: "Porto", type: "Cidade" }),
      expect.any(Object),
    );

    fireEvent.click(screen.getByRole("button", { name: "Excluir" }));
    expect(locationDelete.mutate).toHaveBeenCalledWith(location.id, expect.any(Object));
  });

  test("creates, edits, and deletes items with an owner", () => {
    itemCreate.mutate.mockImplementation((payload, options) =>
      options.onSuccess({ ...item, id: "item-2", ...payload }),
    );
    itemUpdate.mutate.mockImplementation((variables, options) => options.onSuccess({ ...item, ...variables.payload }));

    render(<ItemsPanel bookId="book-1" />);

    expect(screen.getByText("Chave")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Editar" }));
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Chave antiga" } });
    fireEvent.click(screen.getByRole("button", { name: "Salvar item" }));
    expect(itemUpdate.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ itemId: item.id }),
      expect.any(Object),
    );

    fireEvent.click(screen.getByRole("button", { name: "Novo item" }));
    fireEvent.click(screen.getByRole("button", { name: "Criar item" }));
    expect(screen.getByText("Informe o nome do item.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "  Mapa  " } });
    fireEvent.change(screen.getByLabelText("Tipo"), { target: { value: " Pista " } });
    fireEvent.change(screen.getByLabelText("Dono atual"), { target: { value: character.id } });
    fireEvent.change(screen.getByLabelText(/Descri/), { target: { value: "Mapa rasgado" } });
    fireEvent.change(screen.getByLabelText("Origem"), { target: { value: "Torre" } });
    fireEvent.change(screen.getByLabelText(/Import.ncia narrativa/), { target: { value: "Guia" } });
    fireEvent.change(screen.getByLabelText("Notas"), { target: { value: "Metade ausente" } });
    fireEvent.click(screen.getByRole("button", { name: "Criar item" }));
    expect(itemCreate.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ name: "Mapa", type: "Pista", currentOwnerCharacterId: character.id }),
      expect.any(Object),
    );

    fireEvent.click(screen.getByRole("button", { name: "Excluir" }));
    expect(itemDelete.mutate).toHaveBeenCalledWith(item.id, expect.any(Object));
  });

  test("shows loading and query errors", () => {
    hooks.useCharacters.mockReturnValue({ data: undefined, isLoading: true, isError: true });
    hooks.useLocations.mockReturnValue({ data: undefined, isLoading: true, isError: true });
    hooks.useItems.mockReturnValue({ data: undefined, isLoading: true, isError: true });

    const { unmount } = render(<CharactersPanel bookId="book-1" />);
    expect(screen.getByText(/Carregando personagens/)).toBeInTheDocument();
    unmount();

    const locations = render(<LocationsPanel bookId="book-1" />);
    expect(screen.getByText(/Carregando localiza/)).toBeInTheDocument();
    locations.unmount();

    render(<ItemsPanel bookId="book-1" />);
    expect(screen.getByText("Carregando itens...")).toBeInTheDocument();
    expect(screen.getByText(/selecionar o dono atual/)).toBeInTheDocument();
  });
});
