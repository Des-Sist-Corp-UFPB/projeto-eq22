"use client";

import { type FormEvent, type ReactNode, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import type { CharacterRequest, CharacterResponse } from "@/features/characters/types";

type CharacterFormProps = {
  character: CharacterResponse | null;
  isPending: boolean;
  errorMessage: string | null;
  successMessage: string | null;
  onCancelEdit: () => void;
  onSubmit: (payload: CharacterRequest) => void;
};

type CharacterFormState = {
  name: string;
  nickname: string;
  age: string;
  sex: string;
  narrativeFunction: string;
  goal: string;
  conflict: string;
  arc: string;
  physicalDescription: string;
  personality: string;
  biography: string;
  notes: string;
};

const emptyForm: CharacterFormState = {
  name: "",
  nickname: "",
  age: "",
  sex: "",
  narrativeFunction: "",
  goal: "",
  conflict: "",
  arc: "",
  physicalDescription: "",
  personality: "",
  biography: "",
  notes: "",
};

export function CharacterForm({
  character,
  isPending,
  errorMessage,
  successMessage,
  onCancelEdit,
  onSubmit,
}: CharacterFormProps) {
  const [form, setForm] = useState<CharacterFormState>(emptyForm);
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const isEditing = Boolean(character);

  useEffect(() => {
    setForm(character ? toFormState(character) : emptyForm);
    setValidationMessage(null);
  }, [character]);

  const title = useMemo(() => (isEditing ? "Editar personagem" : "Novo personagem"), [isEditing]);

  function updateField(field: keyof CharacterFormState, value: string) {
    setValidationMessage(null);
    setForm((current) => ({ ...current, [field]: value }));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!form.name.trim()) {
      setValidationMessage("Informe o nome do personagem.");
      return;
    }

    const age = form.age.trim() ? Number(form.age) : null;
    if (age !== null && (!Number.isInteger(age) || age < 0)) {
      setValidationMessage("A idade deve ser zero ou maior.");
      return;
    }

    onSubmit({
      name: form.name.trim(),
      nickname: nullableText(form.nickname),
      age,
      sex: nullableText(form.sex),
      narrativeFunction: nullableText(form.narrativeFunction),
      goal: nullableText(form.goal),
      conflict: nullableText(form.conflict),
      arc: nullableText(form.arc),
      physicalDescription: nullableText(form.physicalDescription),
      personality: nullableText(form.personality),
      biography: nullableText(form.biography),
      notes: nullableText(form.notes),
    });
  }

  return (
    <form onSubmit={handleSubmit} className="grid gap-4 rounded-md border border-zinc-200 bg-white p-4 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-base font-semibold text-zinc-950">{title}</h2>
          <p className="text-sm text-zinc-500">Cadastre os dados narrativos essenciais.</p>
        </div>

        {isEditing ? (
          <Button type="button" variant="ghost" onClick={onCancelEdit}>
            Cancelar
          </Button>
        ) : null}
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Nome" className="md:col-span-2">
          <Input
            value={form.name}
            onChange={(event) => updateField("name", event.target.value)}
            disabled={isPending}
            placeholder="Nome do personagem"
          />
        </Field>

        <Field label="Apelido">
          <Input
            value={form.nickname}
            onChange={(event) => updateField("nickname", event.target.value)}
            disabled={isPending}
          />
        </Field>

        <Field label="Idade">
          <Input
            type="number"
            min={0}
            value={form.age}
            onChange={(event) => updateField("age", event.target.value)}
            disabled={isPending}
          />
        </Field>

        <Field label="Sexo">
          <Input value={form.sex} onChange={(event) => updateField("sex", event.target.value)} disabled={isPending} />
        </Field>

        <Field label="Função narrativa">
          <Input
            value={form.narrativeFunction}
            onChange={(event) => updateField("narrativeFunction", event.target.value)}
            disabled={isPending}
          />
        </Field>
      </div>

      <TextAreaField
        label="Objetivo"
        value={form.goal}
        onChange={(value) => updateField("goal", value)}
        disabled={isPending}
      />
      <TextAreaField
        label="Conflito"
        value={form.conflict}
        onChange={(value) => updateField("conflict", value)}
        disabled={isPending}
      />
      <TextAreaField label="Arco" value={form.arc} onChange={(value) => updateField("arc", value)} disabled={isPending} />
      <TextAreaField
        label="Descrição física"
        value={form.physicalDescription}
        onChange={(value) => updateField("physicalDescription", value)}
        disabled={isPending}
      />
      <TextAreaField
        label="Personalidade"
        value={form.personality}
        onChange={(value) => updateField("personality", value)}
        disabled={isPending}
      />
      <TextAreaField
        label="Biografia"
        value={form.biography}
        onChange={(value) => updateField("biography", value)}
        disabled={isPending}
      />
      <TextAreaField
        label="Notas"
        value={form.notes}
        onChange={(value) => updateField("notes", value)}
        disabled={isPending}
      />

      {validationMessage ? <FeedbackMessage variant="error">{validationMessage}</FeedbackMessage> : null}
      {errorMessage ? <FeedbackMessage variant="error">{errorMessage}</FeedbackMessage> : null}
      {successMessage ? <FeedbackMessage variant="success">{successMessage}</FeedbackMessage> : null}

      <div>
        <Button type="submit" disabled={isPending}>
          {isPending ? "Salvando..." : isEditing ? "Salvar personagem" : "Criar personagem"}
        </Button>
      </div>
    </form>
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
  onChange,
}: {
  label: string;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  return (
    <Field label={label}>
      <Textarea
        value={value}
        rows={3}
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

function toFormState(character: CharacterResponse): CharacterFormState {
  return {
    name: character.name,
    nickname: character.nickname ?? "",
    age: character.age === null ? "" : String(character.age),
    sex: character.sex ?? "",
    narrativeFunction: character.narrativeFunction ?? "",
    goal: character.goal ?? "",
    conflict: character.conflict ?? "",
    arc: character.arc ?? "",
    physicalDescription: character.physicalDescription ?? "",
    personality: character.personality ?? "",
    biography: character.biography ?? "",
    notes: character.notes ?? "",
  };
}
