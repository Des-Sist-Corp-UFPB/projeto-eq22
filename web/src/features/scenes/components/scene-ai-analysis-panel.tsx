"use client";

import { type FormEvent, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { FeedbackMessage } from "@/components/ui/feedback-message";
import { Textarea } from "@/components/ui/textarea";
import {
  analyzeScene,
  type SceneAnalysisResult,
} from "@/features/scenes/api/analyze-scene";
import { ApiError } from "@/lib/api/client";

const CONTENT_ID = "scene-ai-analysis-content";
const FOCUS_HELP_ID = "scene-ai-analysis-focus-help";
const MAX_FOCUS_LENGTH = 300;
const UNAVAILABLE_MESSAGE = "A análise com IA está indisponível no momento. Tente novamente mais tarde.";
const GENERIC_ERROR_MESSAGE = "Não foi possível concluir a análise. Tente novamente.";

export type SceneContentSyncState = "saved" | "dirty" | "saving" | "error" | "loading" | "outdated" | "empty";

type SceneAiAnalysisPanelProps = {
  sceneId: string;
  contentRevision: number;
  contentSyncState: SceneContentSyncState;
};

const contentSyncMessages: Partial<Record<SceneContentSyncState, string>> = {
  dirty: "Salve as alterações antes de analisar a versão mais recente.",
  saving: "O conteúdo está sendo salvo. Aguarde para analisar.",
  error: "O conteúdo mais recente não foi salvo. Salve novamente antes de analisar.",
  loading: "Aguarde o carregamento do conteúdo da cena.",
  outdated: "Há uma versão salva mais recente. Conclua a edição para sincronizar antes de analisar.",
  empty: "Escreva algum conteúdo na cena antes de solicitar uma análise.",
};

export function SceneAiAnalysisPanel({ sceneId, contentRevision, contentSyncState }: SceneAiAnalysisPanelProps) {
  const activeSceneIdRef = useRef(sceneId);
  activeSceneIdRef.current = sceneId;
  const activeContentRevisionRef = useRef(contentRevision);
  activeContentRevisionRef.current = contentRevision;
  const contentSyncStateRef = useRef(contentSyncState);
  contentSyncStateRef.current = contentSyncState;

  const previousIdentityRef = useRef({ sceneId, contentRevision });
  const requestSequenceRef = useRef(0);
  const activeControllerRef = useRef<AbortController | null>(null);
  const loadingRef = useRef(false);
  const [isOpen, setIsOpen] = useState(false);
  const [focus, setFocus] = useState("");
  const [result, setResult] = useState<SceneAnalysisResult | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    const previousIdentity = previousIdentityRef.current;
    if (
      previousIdentity.sceneId === sceneId &&
      previousIdentity.contentRevision === contentRevision
    ) {
      return;
    }

    const sceneChanged = previousIdentity.sceneId !== sceneId;
    previousIdentityRef.current = { sceneId, contentRevision };
    requestSequenceRef.current += 1;
    activeControllerRef.current?.abort();
    activeControllerRef.current = null;
    loadingRef.current = false;
    setResult(null);
    setErrorMessage(null);
    setIsLoading(false);
    if (sceneChanged) {
      setFocus("");
    }
  }, [contentRevision, sceneId]);

  useEffect(() => {
    if (contentSyncState === "saved") {
      return;
    }

    requestSequenceRef.current += 1;
    activeControllerRef.current?.abort();
    activeControllerRef.current = null;
    loadingRef.current = false;
    setResult(null);
    setErrorMessage(null);
    setIsLoading(false);
  }, [contentSyncState]);

  useEffect(() => {
    return () => {
      requestSequenceRef.current += 1;
      activeControllerRef.current?.abort();
      activeControllerRef.current = null;
    };
  }, []);

  async function handleAnalyze(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (
      loadingRef.current ||
      contentSyncStateRef.current !== "saved" ||
      focus.length > MAX_FOCUS_LENGTH
    ) {
      return;
    }

    const controller = new AbortController();
    const capturedSceneId = sceneId;
    const capturedContentRevision = contentRevision;
    const capturedSequence = ++requestSequenceRef.current;
    const trimmedFocus = focus.trim();

    activeControllerRef.current = controller;
    loadingRef.current = true;
    setIsLoading(true);
    setResult(null);
    setErrorMessage(null);

    const requestIsStale = () =>
      controller.signal.aborted ||
      capturedSequence !== requestSequenceRef.current ||
      capturedSceneId !== activeSceneIdRef.current ||
      capturedContentRevision !== activeContentRevisionRef.current ||
      contentSyncStateRef.current !== "saved";

    try {
      const analysis = await analyzeScene(
        capturedSceneId,
        trimmedFocus ? { focus: trimmedFocus } : {},
        controller.signal
      );

      if (requestIsStale()) {
        return;
      }

      setResult(analysis);
    } catch (error) {
      if (requestIsStale() || isAbortError(error)) {
        return;
      }

      setErrorMessage(error instanceof ApiError && error.status === 503 ? UNAVAILABLE_MESSAGE : GENERIC_ERROR_MESSAGE);
    } finally {
      if (activeControllerRef.current === controller) {
        activeControllerRef.current = null;
        loadingRef.current = false;
        if (!requestIsStale()) {
          setIsLoading(false);
        }
      }
    }
  }

  const isAnalysisAvailable = contentSyncState === "saved";

  return (
    <section className="border-b border-zinc-200 bg-white px-4 py-3 md:px-7">
      <div className="rounded-lg border border-zinc-200 bg-zinc-50/70">
        <button
          type="button"
          aria-expanded={isOpen}
          aria-controls={CONTENT_ID}
          aria-label={isOpen ? "Recolher análise com IA" : "Expandir análise com IA"}
          onClick={() => setIsOpen((open) => !open)}
          className="flex min-h-14 w-full items-center gap-3 rounded-md px-3 py-3 text-left transition hover:text-zinc-950 focus:outline-none focus:ring-2 focus:ring-zinc-800 focus:ring-offset-2"
        >
          <span
            aria-hidden="true"
            className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-zinc-200 bg-white text-zinc-600 transition ${
              isOpen ? "rotate-90" : ""
            }`}
          >
            ›
          </span>
          <span className="min-w-0 flex-1 text-sm font-semibold text-zinc-950">Análise com IA</span>
        </button>

        <div id={CONTENT_ID} hidden={!isOpen} className="border-t border-zinc-200 bg-zinc-50/60 p-3 md:p-4">
          <form className="grid gap-3" onSubmit={handleAnalyze}>
            <label htmlFor="scene-ai-analysis-focus" className="text-xs font-medium text-zinc-600">
              Foco da análise
            </label>
            <Textarea
              id="scene-ai-analysis-focus"
              value={focus}
              rows={3}
              maxLength={MAX_FOCUS_LENGTH}
              aria-describedby={FOCUS_HELP_ID}
              disabled={isLoading}
              onChange={(event) => {
                if (event.target.value.length <= MAX_FOCUS_LENGTH) {
                  setFocus(event.target.value);
                }
              }}
              placeholder="Opcional: destaque ritmo, diálogo, tensão..."
              className="resize-y bg-white text-sm text-zinc-900 focus:ring-2 focus:ring-zinc-200"
            />
            <div id={FOCUS_HELP_ID} className="flex flex-wrap items-start justify-between gap-2 text-xs text-zinc-500">
              <span>
                <span className="block">A IA analisa a última versão salva.</span>
                {contentSyncMessages[contentSyncState] ? (
                  <span className="mt-0.5 block">{contentSyncMessages[contentSyncState]}</span>
                ) : null}
              </span>
              <span>{focus.length}/{MAX_FOCUS_LENGTH}</span>
            </div>
            <div>
              <Button type="submit" size="sm" disabled={isLoading || !isAnalysisAvailable}>
                {isLoading ? "Analisando..." : "Analisar com IA"}
              </Button>
            </div>
          </form>

          {errorMessage && isAnalysisAvailable ? (
            <FeedbackMessage variant="error" className="mt-4">
              {errorMessage}
            </FeedbackMessage>
          ) : null}

          {result && isAnalysisAvailable ? <SceneAnalysisSections result={result} /> : null}
        </div>
      </div>
    </section>
  );
}

function SceneAnalysisSections({ result }: { result: SceneAnalysisResult }) {
  return (
    <div className="mt-5 grid gap-5 border-t border-zinc-200 pt-4 text-sm text-zinc-700">
      <AnalysisTextSection title="Resumo" text={result.summary} />
      <AnalysisTextSection title="Tom" text={result.tone} />
      <AnalysisTextSection title="Ritmo" text={result.pacing} />
      <AnalysisListSection title="Pontos fortes" items={result.strengths} />
      <AnalysisListSection title="Problemas encontrados" items={result.issues} />
      <AnalysisListSection title="Sugestões" items={result.suggestions} />
    </div>
  );
}

function AnalysisTextSection({ title, text }: { title: string; text: string }) {
  return (
    <section>
      <h3 className="text-xs font-semibold uppercase text-zinc-500">{title}</h3>
      <p className="mt-1 whitespace-pre-wrap leading-6">{text}</p>
    </section>
  );
}

function AnalysisListSection({ title, items }: { title: string; items: string[] }) {
  return (
    <section>
      <h3 className="text-xs font-semibold uppercase text-zinc-500">{title}</h3>
      <ul className="mt-1 grid list-disc gap-1 pl-5 leading-6">
        {items.map((item, index) => (
          <li key={`${title}-${index}`}>{item}</li>
        ))}
      </ul>
    </section>
  );
}

function isAbortError(error: unknown) {
  return typeof error === "object" && error !== null && "name" in error && error.name === "AbortError";
}
