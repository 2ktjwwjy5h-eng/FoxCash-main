// Step-up TOTP no cliente (Spec 72, fatia 2 — D3.2): envolve uma ação sensível
// e trata o 403 `step_up_required` do backend. Fluxo:
//   run(action)
//     → action() — se 403 step_up_required:
//         → se o step-up recente está dentro da janela (15 min, UX), repete a
//           ação (o BACKEND ainda valida — se a janela real expirou, 403 de novo);
//         → senão abre o StepUpPrompt → POST /api/v1/master/mfa/step-up {code}
//           → 200: grava steppedUpAt = now → repete a ação (1 tentativa)
//             e resolve a Promise original com o resultado;
//           → 401/400/429: erro no modal (mantém aberto para nova tentativa);
//         → cancelar → rejeita a Promise com o ApiError original.
//     → outros erros (403 forbidden, 409, ...): rethrow (banner da página).
// Regra de ouro: o cache NUNCA autoriza sozinho (fail-closed) — a repetição é
// sempre tentada e o 403 do backend é a única fonte de verdade.

import { useCallback, useRef, useState } from 'react';
import { api, ApiError } from '../apiClient';
import { StepUpPrompt } from '../components/StepUpPrompt';

/** Janela de step-up por sessão (15 min) — espelho de
 *  MasterStepUpProperties.window (Spec 69). A janela REAL é do backend
 *  (stepped_up_at na sessão); este valor só evita o prompt em ações
 *  sequenciais (UX). */
const STEP_UP_WINDOW_MS = 15 * 60 * 1000;

interface PendingStepUp {
  action: () => Promise<unknown>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  resolve: (value: any) => void;
  reject: (err: unknown) => void;
  /** ApiError original (step_up_required) — usado no cancelamento. */
  originalError: ApiError;
}

interface UseStepUp {
  /** Envolve uma ação sensível: trata o 403 step_up_required (prompt → repete).
   *  Resolve com o resultado FINAL da ação (pós-step-up). */
  run: <T>(action: () => Promise<T>) => Promise<T>;
  /** O prompt TOTP está aberto? */
  promptOpen: boolean;
  /** Renderiza o StepUpPrompt dentro do componente que usa o hook. */
  promptElement: React.ReactElement | null;
}

export function useStepUp(): UseStepUp {
  const [promptOpen, setPromptOpen] = useState(false);
  const [promptError, setPromptError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const steppedUpAtRef = useRef<number>(0);
  const pendingRef = useRef<PendingStepUp | null>(null);

  const run = useCallback(<T,>(action: () => Promise<T>): Promise<T> => {
    // eslint-disable-next-line no-async-promise-executor
    return new Promise<T>((resolve, reject) => {
      const attempt = (): Promise<void> =>
        action().then(
          (value) => {
            resolve(value);
            pendingRef.current = null;
          },
          (err) => {
            if (!(err instanceof ApiError) || !err.stepUpRequired) {
              reject(err);
              pendingRef.current = null;
              return;
            }
            // Janela recente (UX): repete direto — o backend ainda valida de verdade.
            if (steppedUpAtRef.current && Date.now() - steppedUpAtRef.current < STEP_UP_WINDOW_MS) {
              void attempt();
              return;
            }
            // Sem step-up recente: abre o prompt e guarda a ação para repetir.
            pendingRef.current = { action, resolve, reject, originalError: err };
            setPromptError(null);
            setPromptOpen(true);
          },
        );

      void attempt();
    });
  }, []);

  const handleConfirm = useCallback(async (code: string) => {
    const pending = pendingRef.current;
    if (!pending) return;
    setBusy(true);
    setPromptError(null);
    try {
      await api.post<void>('/api/v1/master/mfa/step-up', { code });
      steppedUpAtRef.current = Date.now();
      setPromptOpen(false);
      // Repete a ação (1 tentativa). Se o backend rejeitar de novo (janela real
      // expirada), o 403 reaparece e o prompt reabre — sem loop infinito.
      try {
        const value = await pending.action();
        pending.resolve(value);
        pendingRef.current = null;
      } catch (actionErr) {
        if (actionErr instanceof ApiError && actionErr.stepUpRequired) {
          // Janela real expirada: reabre o prompt com a mesma ação pendente.
          setPromptOpen(true);
          setPromptError(null);
        } else {
          pending.reject(actionErr);
          pendingRef.current = null;
          setPromptOpen(false);
        }
      }
    } catch (stepUpErr) {
      // Falha do próprio step-up (código inválido, rate limit...): mantém o modal.
      setPromptError(stepUpErr);
    } finally {
      setBusy(false);
    }
  }, []);

  const handleCancel = useCallback(() => {
    const pending = pendingRef.current;
    setPromptOpen(false);
    setPromptError(null);
    pendingRef.current = null;
    if (pending) {
      pending.reject(pending.originalError);
    }
  }, []);

  const promptElement = (
    <StepUpPrompt
      open={promptOpen}
      error={promptError}
      busy={busy}
      onConfirm={handleConfirm}
      onCancel={handleCancel}
    />
  );

  return {
    run,
    promptOpen,
    promptElement,
  };
}
