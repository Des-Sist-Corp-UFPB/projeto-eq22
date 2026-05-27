"use client";

import { type FormEvent, useEffect, useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { TextAreaField, TextField } from "@/components/ui/text-field";
import {
  useCreateNotebookNote,
  useDeleteNotebookNote,
  useNotebookCategories,
  useNotebookNotes,
  useUpdateNotebookNote,
} from "@/features/notebook/api/notebook-hooks";
import type { NotebookCategory, NotebookNote, NotebookNoteRequest } from "@/features/notebook/types";
import { ApiError } from "@/lib/api/client";

type NotebookPanelProps = {
  bookId: string;
};

type DetailMode = "empty" | "create" | "edit";
type CategoryFilter = "all" | "uncategorized" | string;

type NoteFormState = {
  title: string;
  content: string;
  categoryId: string;
};

const emptyForm: NoteFormState = {
  title: "",
  content: "",
  categoryId: "",
};

const dateFormatter = new Intl.DateTimeFormat("pt-BR", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});

export function NotebookPanel({ bookId }: NotebookPanelProps) {
  const [selectedFilter, setSelectedFilter] = useState<CategoryFilter>("all");
  const selectedCategoryId = selectedFilter === "all" || selectedFilter === "uncategorized" ? null : selectedFilter;
  const categoriesQuery = useNotebookCategories(bookId);
  const notesQuery = useNotebookNotes(bookId, selectedCategoryId);
  const createMutation = useCreateNotebookNote(bookId);
  const updateMutation = useUpdateNotebookNote(bookId);
  const deleteMutation = useDeleteNotebookNote(bookId);
  const [selectedNote, setSelectedNote] = useState<NotebookNote | null>(null);
  const [detailMode, setDetailMode] = useState<DetailMode>("empty");
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const notes = useMemo(() => {
    const values = notesQuery.data ?? [];
    return selectedFilter === "uncategorized" ? values.filter((note) => !note.categoryId) : values;
  }, [notesQuery.data, selectedFilter]);

  const activeMutation = detailMode === "edit" ? updateMutation : createMutation;
  const errorMessage = useMemo(() => getNotebookErrorMessage(activeMutation.error), [activeMutation.error]);
  const deleteErrorMessage = useMemo(() => getNotebookErrorMessage(deleteMutation.error), [deleteMutation.error]);

  function startCreate() {
    setSelectedNote(null);
    setDetailMode("create");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function startEdit(note: NotebookNote) {
    setSelectedNote(note);
    setDetailMode("edit");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function clearDetail() {
    setSelectedNote(null);
    setDetailMode("empty");
    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();
  }

  function handleSubmit(payload: NotebookNoteRequest) {
    setSuccessMessage(null);

    if (detailMode === "edit" && selectedNote) {
      updateMutation.mutate(
        { noteId: selectedNote.id, payload },
        {
          onSuccess: (note) => {
            setSelectedNote(note);
            setSuccessMessage("Nota atualizada com sucesso.");
          },
        }
      );
      return;
    }

    createMutation.mutate(payload, {
      onSuccess: (note) => {
        setSelectedNote(note);
        setDetailMode("edit");
        setSuccessMessage("Nota criada com sucesso.");
      },
    });
  }

  function handleDeleteNote(note: NotebookNote) {
    const confirmed = window.confirm(`Excluir a nota "${note.title}"? Esta ação não pode ser desfeita.`);
    if (!confirmed) {
      return;
    }

    setSuccessMessage(null);
    createMutation.reset();
    updateMutation.reset();
    deleteMutation.reset();

    deleteMutation.mutate(note.id, {
      onSuccess: () => {
        if (selectedNote?.id === note.id) {
          setSelectedNote(null);
          setDetailMode("empty");
        }
        setSuccessMessage("Nota excluída com sucesso.");
      },
    });
  }

  return (
    <section className="h-full overflow-y-auto bg-zinc-50 p-4 md:p-6">
      <div className="mx-auto grid max-w-6xl gap-4">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-zinc-950">Caderno</h1>
            <p className="mt-1 text-sm text-zinc-500">Guarde notas gerais deste livro.</p>
          </div>

          <Button type="button" variant="secondary" onClick={startCreate}>
            Nova nota
          </Button>
        </header>

        {categoriesQuery.isLoading || notesQuery.isLoading ? <LoadingState label="Carregando caderno..." /> : null}

        {categoriesQuery.isError ? (
          <ErrorState message="Não foi possível carregar as categorias do caderno. Verifique o backend e tente novamente." />
        ) : null}

        {notesQuery.isError ? (
          <ErrorState message="Não foi possível carregar as notas do caderno. Verifique o backend e tente novamente." />
        ) : null}

        {deleteErrorMessage ? <ErrorState message={deleteErrorMessage} /> : null}
        {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}

        {categoriesQuery.data && notesQuery.data ? (
          <div className="grid min-h-[calc(100vh-180px)] gap-4 lg:grid-cols-[340px_minmax(0,1fr)]">
            <aside className="grid min-h-0 min-w-0 content-start gap-3 lg:max-w-[340px]">
              <CategoryFilters
                categories={categoriesQuery.data}
                selectedFilter={selectedFilter}
                onSelectFilter={(filter) => {
                  setSelectedFilter(filter);
                  clearDetail();
                }}
              />

              <NotesList
                notes={notes}
                selectedNoteId={detailMode === "edit" ? selectedNote?.id ?? null : null}
                deletePendingNoteId={deleteMutation.variables ?? null}
                onEditNote={startEdit}
                onDeleteNote={handleDeleteNote}
              />
            </aside>

            <div className="min-w-0">
              {detailMode === "empty" ? (
                <EmptyState title="Selecione uma nota para visualizar ou editar." className="h-full" />
              ) : (
                <NotebookNoteForm
                  note={detailMode === "edit" ? selectedNote : null}
                  categories={categoriesQuery.data}
                  isPending={activeMutation.isPending}
                  errorMessage={errorMessage}
                  onCancelEdit={clearDetail}
                  onSubmit={handleSubmit}
                />
              )}
            </div>
          </div>
        ) : null}
      </div>
    </section>
  );
}

function CategoryFilters({
  categories,
  selectedFilter,
  onSelectFilter,
}: {
  categories: NotebookCategory[];
  selectedFilter: CategoryFilter;
  onSelectFilter: (filter: CategoryFilter) => void;
}) {
  return (
    <div className="grid gap-2 rounded-md border border-zinc-200 bg-white p-3 shadow-sm">
      <h2 className="text-sm font-semibold text-zinc-950">Categorias</h2>
      <div className="flex flex-wrap gap-2">
        <FilterButton isActive={selectedFilter === "all"} onClick={() => onSelectFilter("all")}>
          Todas
        </FilterButton>
        <FilterButton isActive={selectedFilter === "uncategorized"} onClick={() => onSelectFilter("uncategorized")}>
          Sem categoria
        </FilterButton>
        {categories.map((category) => (
          <FilterButton
            key={category.id}
            isActive={selectedFilter === category.id}
            onClick={() => onSelectFilter(category.id)}
          >
            {category.name}
          </FilterButton>
        ))}
      </div>
    </div>
  );
}

function FilterButton({
  isActive,
  children,
  onClick,
}: {
  isActive: boolean;
  children: string;
  onClick: () => void;
}) {
  return (
    <Button type="button" size="sm" variant={isActive ? "primary" : "secondary"} onClick={onClick}>
      {children}
    </Button>
  );
}

function NotesList({
  notes,
  selectedNoteId,
  deletePendingNoteId,
  onEditNote,
  onDeleteNote,
}: {
  notes: NotebookNote[];
  selectedNoteId: string | null;
  deletePendingNoteId: string | null;
  onEditNote: (note: NotebookNote) => void;
  onDeleteNote: (note: NotebookNote) => void;
}) {
  if (notes.length === 0) {
    return (
      <EmptyState
        title="Nenhuma nota no caderno"
        description="Crie a primeira nota geral deste livro."
      />
    );
  }

  return (
    <div className="grid max-h-[calc(100vh-300px)] content-start gap-2 overflow-y-auto pr-1">
      {notes.map((note) => (
        <article
          key={note.id}
          className={`grid min-h-[136px] content-start gap-2 rounded-md border bg-white p-3 shadow-sm transition ${
            selectedNoteId === note.id ? "border-zinc-900 ring-2 ring-zinc-200" : "border-zinc-200 hover:border-zinc-300"
          }`}
        >
          <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-2">
            <button type="button" className="min-w-0 text-left" onClick={() => onEditNote(note)}>
              <h3 className="truncate text-sm font-semibold text-zinc-950">{note.title}</h3>
              <div className="mt-1 flex min-h-5 flex-wrap items-center gap-1.5 text-xs text-zinc-500">
                {note.category ? <Badge variant="outline">{note.category.name}</Badge> : null}
                {note.updatedAt ? <span>Atualizada em {formatDate(note.updatedAt)}</span> : null}
              </div>
            </button>

            <div className="flex shrink-0 items-center gap-1">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-7 px-2 text-xs"
                onClick={() => onEditNote(note)}
              >
                Editar
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-7 px-2 text-xs text-red-700 hover:bg-red-50"
                disabled={deletePendingNoteId === note.id}
                onClick={() => onDeleteNote(note)}
              >
                {deletePendingNoteId === note.id ? "..." : "Excluir"}
              </Button>
            </div>
          </div>

          <p className="line-clamp-3 min-h-15 text-xs leading-5 text-zinc-600">
            {note.content?.trim() ? note.content : <span className="text-zinc-400">Sem conteúdo.</span>}
          </p>
        </article>
      ))}
    </div>
  );
}

function NotebookNoteForm({
  note,
  categories,
  isPending,
  errorMessage,
  onCancelEdit,
  onSubmit,
}: {
  note: NotebookNote | null;
  categories: NotebookCategory[];
  isPending: boolean;
  errorMessage: string | null;
  onCancelEdit: () => void;
  onSubmit: (payload: NotebookNoteRequest) => void;
}) {
  const [form, setForm] = useState<NoteFormState>(emptyForm);
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const isEditing = Boolean(note);

  useEffect(() => {
    setForm(note ? toFormState(note) : emptyForm);
    setValidationMessage(null);
  }, [note]);

  function updateField(field: keyof NoteFormState, value: string) {
    setValidationMessage(null);
    setForm((current) => ({ ...current, [field]: value }));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!form.title.trim()) {
      setValidationMessage("Informe o título da nota.");
      return;
    }

    onSubmit({
      title: form.title.trim(),
      content: nullableText(form.content),
      categoryId: form.categoryId || null,
    });
  }

  return (
    <form onSubmit={handleSubmit} className="grid gap-4 rounded-md border border-zinc-200 bg-white p-4 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200 pb-4">
        <div>
          <h2 className="text-base font-semibold text-zinc-950">{isEditing ? "Editar nota" : "Nova nota"}</h2>
          <p className="text-sm text-zinc-500">Registre ideias, perguntas, pesquisas e referências gerais.</p>
        </div>

        <Button type="button" variant="ghost" onClick={onCancelEdit}>
          Cancelar
        </Button>
      </div>

      <TextField
        label="Título"
        value={form.title}
        onChange={(event) => updateField("title", event.target.value)}
        disabled={isPending}
        placeholder="Título da nota"
      />

      <label className="grid gap-1 text-sm">
        <span className="font-medium text-zinc-700">Categoria</span>
        <select
          value={form.categoryId}
          onChange={(event) => updateField("categoryId", event.target.value)}
          disabled={isPending}
          className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-950 shadow-sm outline-none transition focus:border-zinc-950 focus:ring-2 focus:ring-zinc-200 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-500"
        >
          <option value="">Sem categoria</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
      </label>

      <TextAreaField
        label="Conteúdo"
        value={form.content}
        rows={8}
        onChange={(event) => updateField("content", event.target.value)}
        disabled={isPending}
        className="resize-y"
      />

      {validationMessage ? <FeedbackMessage variant="error">{validationMessage}</FeedbackMessage> : null}
      {errorMessage ? <FeedbackMessage variant="error">{errorMessage}</FeedbackMessage> : null}

      <div className="border-t border-zinc-200 pt-4">
        <Button type="submit" disabled={isPending}>
          {isPending ? "Salvando..." : isEditing ? "Salvar nota" : "Criar nota"}
        </Button>
      </div>
    </form>
  );
}

function nullableText(value: string) {
  return value.trim() ? value.trim() : null;
}

function toFormState(note: NotebookNote): NoteFormState {
  return {
    title: note.title,
    content: note.content ?? "",
    categoryId: note.categoryId ?? "",
  };
}

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }

  return dateFormatter.format(date);
}

function getNotebookErrorMessage(error: unknown) {
  if (!error) {
    return null;
  }

  if (error instanceof ApiError) {
    return error.message || "Não foi possível salvar a nota agora.";
  }

  return "Não foi possível salvar a nota. Verifique a API e tente novamente.";
}
