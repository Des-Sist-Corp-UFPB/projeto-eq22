"use client";

import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import type { ExportFormat } from "@/features/export/api/export-api";

type ExportOptionsPopoverProps = {
  triggerLabel: string;
  title: string;
  submitLabel: string;
  pendingLabel: string;
  isOpen: boolean;
  isPending: boolean;
  format: ExportFormat;
  formatGroupName: string;
  validationMessage?: string | null;
  errorMessage?: string | null;
  description?: string;
  onToggle: () => void;
  onFormatChange: (format: ExportFormat) => void;
  onSubmit: () => void;
  children: ReactNode;
};

const formatOptions: Array<{ value: ExportFormat; label: string }> = [
  { value: "txt", label: "TXT (.txt)" },
  { value: "md", label: "Markdown (.md)" },
  { value: "docx", label: "Word (.docx)" },
];

export function ExportOptionsPopover({
  triggerLabel,
  title,
  submitLabel,
  pendingLabel,
  isOpen,
  isPending,
  format,
  formatGroupName,
  validationMessage,
  errorMessage,
  description,
  onToggle,
  onFormatChange,
  onSubmit,
  children,
}: ExportOptionsPopoverProps) {
  return (
    <div className="relative">
      <Button type="button" size="sm" variant="secondary" aria-expanded={isOpen} onClick={onToggle}>
        {triggerLabel}
      </Button>

      {isOpen ? (
        <div className="absolute right-0 top-10 z-10 w-80 rounded-md border border-zinc-200 bg-white p-4 shadow-lg shadow-zinc-200/80">
          <div className="grid gap-3">
            <div>
              <h2 className="text-sm font-semibold text-zinc-950">{title}</h2>
              {description ? <p className="mt-1 text-xs leading-5 text-zinc-500">{description}</p> : null}
            </div>

            <fieldset className="grid gap-2">
              <legend className="text-sm font-medium text-zinc-950">Formato</legend>
              {formatOptions.map((option) => (
                <label key={option.value} className="flex items-center gap-3 text-sm text-zinc-700">
                  <input
                    type="radio"
                    name={formatGroupName}
                    value={option.value}
                    checked={format === option.value}
                    onChange={() => onFormatChange(option.value)}
                  />
                  {option.label}
                </label>
              ))}
            </fieldset>

            {children}

            {validationMessage ? <FeedbackMessage variant="error">{validationMessage}</FeedbackMessage> : null}

            <Button type="button" size="sm" disabled={isPending} onClick={onSubmit}>
              {isPending ? pendingLabel : submitLabel}
            </Button>
          </div>

          {errorMessage ? (
            <FeedbackMessage variant="error" className="mt-3">
              {errorMessage}
            </FeedbackMessage>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
