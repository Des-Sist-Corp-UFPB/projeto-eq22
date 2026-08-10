"use client";

import { type FormEvent, type ReactNode, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import type { LocationRequest, LocationResponse } from "@/features/locations/types";

type LocationFormProps = {
  location: LocationResponse | null;
  isPending: boolean;
  errorMessage: string | null;
  successMessage: string | null;
  onCancelEdit: () => void;
  onSubmit: (payload: LocationRequest) => void;
};

type LocationFormState = {
  name: string;
  type: string;
  description: string;
  historyContext: string;
  narrativeImportance: string;
  notes: string;
};

const emptyForm: LocationFormState = {
  name: "",
  type: "",
  description: "",
  historyContext: "",
  narrativeImportance: "",
  notes: "",
};

export function LocationForm({
  location,
  isPending,
  errorMessage,
  successMessage,
  onCancelEdit,
  onSubmit,
}: LocationFormProps) {
  const [form, setForm] = useState<LocationFormState>(emptyForm);
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const isEditing = Boolean(location);

  useEffect(() => {
    setForm(location ? toFormState(location) : emptyForm);
    setValidationMessage(null);
  }, [location]);

  const title = useMemo(() => (isEditing ? "Editar localização" : "Nova localização"), [isEditing]);

  function updateField(field: keyof LocationFormState, value: string) {
    setValidationMessage(null);
    setForm((current) => ({ ...current, [field]: value }));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!form.name.trim()) {
      setValidationMessage("Informe o nome da localização.");
      return;
    }

    onSubmit({
      name: form.name.trim(),
      type: nullableText(form.type),
      description: nullableText(form.description),
      historyContext: nullableText(form.historyContext),
      narrativeImportance: nullableText(form.narrativeImportance),
      notes: nullableText(form.notes),
    });
  }

  return (
    <form onSubmit={handleSubmit} className="grid gap-4 rounded-md border border-zinc-200 bg-white p-4 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-200 pb-4">
        <div>
          <h2 className="text-base font-semibold text-zinc-950">{title}</h2>
          <p className="text-sm text-zinc-500">Organize os espaços narrativos importantes do livro.</p>
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
              placeholder="Nome da localização"
            />
          </Field>

          <Field label="Tipo" className="md:col-span-2">
            <Input
              value={form.type}
              onChange={(event) => updateField("type", event.target.value)}
              disabled={isPending}
              placeholder="Cidade, casa, planeta, reino..."
            />
          </Field>
        </div>
      </FormSection>

      <FormSection title="Descrição narrativa">
        <TextAreaField
          label="Descrição"
          value={form.description}
          onChange={(value) => updateField("description", value)}
          disabled={isPending}
        />
        <TextAreaField
          label="Contexto histórico"
          value={form.historyContext}
          onChange={(value) => updateField("historyContext", value)}
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
          {isPending ? "Salvando..." : isEditing ? "Salvar localização" : "Criar localização"}
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

function toFormState(location: LocationResponse): LocationFormState {
  return {
    name: location.name,
    type: location.type ?? "",
    description: location.description ?? "",
    historyContext: location.historyContext ?? "",
    narrativeImportance: location.narrativeImportance ?? "",
    notes: location.notes ?? "",
  };
}
