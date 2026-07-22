"use client";

import { type FormEvent, type ReactNode, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import type { CharacterResponse } from "@/features/characters/types";
import type { ItemRequest, ItemResponse } from "@/features/items/types";

type ItemFormProps = {
  item: ItemResponse | null;
  characters: CharacterResponse[];
  isPending: boolean;
  charactersPending: boolean;
  errorMessage: string | null;
  successMessage: string | null;
  onCancelEdit: () => void;
  onSubmit: (payload: ItemRequest) => void;
};

type ItemFormState = {
  name: string;
  type: string;
  description: string;
  origin: string;
  currentOwnerCharacterId: string;
  narrativeImportance: string;
  notes: string;
};

const emptyForm: ItemFormState = {
  name: "",
  type: "",
  description: "",
  origin: "",
  currentOwnerCharacterId: "",
  narrativeImportance: "",
  notes: "",
};

export function ItemForm({
  item,
  characters,
  isPending,
  charactersPending,
  errorMessage,
  successMessage,
  onCancelEdit,
  onSubmit,
}: ItemFormProps) {
  const [form, setForm] = useState<ItemFormState>(emptyForm);
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const isEditing = Boolean(item);

  useEffect(() => {
    setForm(item ? toFormState(item) : emptyForm);
    setValidationMessage(null);
  }, [item]);

  const title = useMemo(() => (isEditing ? "Editar item" : "Novo item"), [isEditing]);

  function updateField(field: keyof ItemFormState, value: string) {
    setValidationMessage(null);
    setForm((current) => ({ ...current, [field]: value }));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!form.name.trim()) {
      setValidationMessage("Informe o nome do item.");
      return;
    }

    onSubmit({
      name: form.name.trim(),
      type: nullableText(form.type),
      description: nullableText(form.description),
      origin: nullableText(form.origin),
      currentOwnerCharacterId: form.currentOwnerCharacterId || null,
      narrativeImportance: nullableText(form.narrativeImportance),
      notes: nullableText(form.notes),
    });
  }

  return (
    <form onSubmit={handleSubmit} className="grid gap-4 rounded-md border border-zinc-200 bg-white p-4 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200 pb-4">
        <div>
          <h2 className="text-base font-semibold text-zinc-950">{title}</h2>
          <p className="text-sm text-zinc-500">Organize objetos, pistas e artefatos importantes do livro.</p>
        </div>

        <Button type="button" variant="ghost" onClick={onCancelEdit}>
          Cancelar
        </Button>
      </div>

      <FormSection title="Dados básicos">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Nome" className="md:col-span-2">
            <Input
              value={form.name}
              onChange={(event) => updateField("name", event.target.value)}
              disabled={isPending}
              placeholder="Nome do item"
            />
          </Field>

          <Field label="Tipo">
            <Input
              value={form.type}
              onChange={(event) => updateField("type", event.target.value)}
              disabled={isPending}
              placeholder="Arma, pista, relíquia..."
            />
          </Field>

          <Field label="Dono atual">
            <select
              value={form.currentOwnerCharacterId}
              onChange={(event) => updateField("currentOwnerCharacterId", event.target.value)}
              disabled={isPending || charactersPending}
              className="min-h-10 rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-950 shadow-sm outline-none transition focus:border-zinc-950 focus:ring-2 focus:ring-zinc-200 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-500"
            >
              <option value="">Nenhum dono</option>
              {characters.map((character) => (
                <option key={character.id} value={character.id}>
                  {character.name}
                </option>
              ))}
            </select>
          </Field>
        </div>
      </FormSection>

      <FormSection title="Função narrativa">
        <TextAreaField
          label="Descrição"
          value={form.description}
          onChange={(value) => updateField("description", value)}
          disabled={isPending}
        />
        <TextAreaField
          label="Origem"
          value={form.origin}
          onChange={(value) => updateField("origin", value)}
          disabled={isPending}
        />
        <TextAreaField
          label="Importância narrativa"
          value={form.narrativeImportance}
          onChange={(value) => updateField("narrativeImportance", value)}
          disabled={isPending}
        />
      </FormSection>

      <FormSection title="Notas">
        <TextAreaField
          label="Notas"
          value={form.notes}
          onChange={(value) => updateField("notes", value)}
          disabled={isPending}
          rows={4}
        />
      </FormSection>

      {validationMessage ? <FeedbackMessage variant="error">{validationMessage}</FeedbackMessage> : null}
      {errorMessage ? <FeedbackMessage variant="error">{errorMessage}</FeedbackMessage> : null}
      {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}

      <div className="border-t border-zinc-200 pt-4">
        <Button type="submit" disabled={isPending}>
          {isPending ? "Salvando..." : isEditing ? "Salvar item" : "Criar item"}
        </Button>
      </div>
    </form>
  );
}

function FormSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="grid gap-3 rounded-md border border-zinc-200 bg-zinc-50/70 p-4">
      <h3 className="text-sm font-semibold text-zinc-950">{title}</h3>
      <div className="grid gap-3">{children}</div>
    </section>
  );
}

function Field({ label, className = "", children }: { label: string; className?: string; children: ReactNode }) {
  return (
    <label className={`grid gap-1 text-sm ${className}`}>
      <span className="font-medium text-zinc-700">{label}</span>
      {children}
    </label>
  );
}

function TextAreaField({
  label,
  value,
  disabled,
  rows = 3,
  onChange,
}: {
  label: string;
  value: string;
  disabled: boolean;
  rows?: number;
  onChange: (value: string) => void;
}) {
  return (
    <Field label={label}>
      <Textarea
        value={value}
        rows={rows}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        className="resize-y"
      />
    </Field>
  );
}

function nullableText(value: string) {
  return value.trim() ? value.trim() : null;
}

function toFormState(item: ItemResponse): ItemFormState {
  return {
    name: item.name,
    type: item.type ?? "",
    description: item.description ?? "",
    origin: item.origin ?? "",
    currentOwnerCharacterId: item.currentOwnerCharacterId ?? "",
    narrativeImportance: item.narrativeImportance ?? "",
    notes: item.notes ?? "",
  };
}
