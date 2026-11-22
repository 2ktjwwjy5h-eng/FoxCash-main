// Confirmação de ações (Spec 71, fatia 1 — D5.6 + Spec 73 — D3.2): as ações
// pedem confirmação explícita antes de executar; o backend continua a
// autoridade (403 → banner "sem permissão").
// Spec 73: modal acessível — focus trap (Tab cíclico), Escape cancela,
// autofocus guiado pelo tom (danger → Cancelar; primary → Confirmar), foco
// restaurado ao fechar, `aria-labelledby`/`aria-describedby`.

import { useId } from 'react';
import { ErrorBanner } from './ui';
import { useDialogFocus } from '../hooks/useDialogFocus';
import type { ReactNode } from 'react';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  busy: boolean;
  error: unknown;
  onConfirm: () => void;
  onCancel: () => void;
  /** Campos adicionais (ex.: motivo do write-off — Spec 72). */
  children?: ReactNode;
  /** Spec 73: `danger` (default) — botão vermelho + foca Cancelar;
   *  `primary` — botão azul + foca Confirmar (confirmações neutras). */
  tone?: 'danger' | 'primary';
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  busy,
  error,
  onConfirm,
  onCancel,
  children,
  tone = 'danger',
}: ConfirmDialogProps) {
  const titleId = useId();
  const msgId = useId();
  const dialogRef = useDialogFocus({
    open,
    onClose: onCancel,
    initialFocus: tone === 'danger' ? 'first' : 'last',
  });

  if (!open) return null;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={msgId}>
      <div className="modal" ref={dialogRef}>
        <h2 id={titleId} style={{ margin: 0, fontSize: 18 }}>
          {title}
        </h2>
        <p id={msgId} style={{ margin: 0 }}>
          {message}
        </p>
        {children}
        <ErrorBanner error={error} />
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            Cancelar
          </button>
          <button
            type="button"
            className={tone === 'primary' ? 'btn btn-primary' : 'btn btn-danger'}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? 'Executando…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
