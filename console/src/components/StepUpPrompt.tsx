// Prompt de step-up TOTP (Spec 72, fatia 2 — D3.3): quando uma ação sensível
// responde 403 `step_up_required`, o console abre este modal pedindo o código
// do app autenticador (Spec 44/69) → o hook `useStepUp` chama
// `POST /api/v1/master/mfa/step-up` e REPETE a ação. Nunca 401 — a sessão não
// cai. Não exibe secret/oráculo (o erro do backend é genérico).
// Spec 73 (D3.3): modal acessível — focus trap, Escape cancela, autofocus no
// campo TOTP (initialFocus=ref), `aria-labelledby`/`aria-describedby`.

import { useEffect, useId, useRef, useState } from 'react';
import { ErrorBanner } from './ui';
import { useDialogFocus } from '../hooks/useDialogFocus';

export interface StepUpPromptProps {
  open: boolean;
  /** Erro do backend na tentativa de step-up (código inválido, rate limit...). */
  error: unknown;
  busy: boolean;
  onConfirm: (code: string) => void;
  onCancel: () => void;
}

export function StepUpPrompt({ open, error, busy, onConfirm, onCancel }: StepUpPromptProps) {
  const [code, setCode] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  const titleId = useId();
  const msgId = useId();

  const dialogRef = useDialogFocus({
    open,
    onClose: onCancel,
    initialFocus: inputRef,
  });

  useEffect(() => {
    if (open) {
      setCode('');
    }
  }, [open]);

  if (!open) return null;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (code.trim() && !busy) {
      onConfirm(code.trim());
    }
  };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={msgId}>
      <div className="modal" ref={dialogRef}>
        <h2 id={titleId} style={{ margin: 0, fontSize: 18 }}>
          Reautenticação necessária
        </h2>
        <p id={msgId} style={{ margin: 0 }}>
          Esta ação sensível exige confirmação com o app de autenticação (TOTP).
        </p>
        <form onSubmit={submit}>
          <label htmlFor="stepup-code">Código TOTP</label>
          <input
            ref={inputRef}
            id="stepup-code"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder="000000"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          />
          <ErrorBanner error={error} />
          <div className="modal-actions">
            <button type="button" className="btn" onClick={onCancel} disabled={busy}>
              Cancelar
            </button>
            <button type="submit" className="btn btn-primary" disabled={busy || !code.trim()}>
              {busy ? 'Verificando…' : 'Confirmar'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
