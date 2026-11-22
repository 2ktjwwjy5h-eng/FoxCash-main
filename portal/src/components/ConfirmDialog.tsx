// Confirmação de ações do portal (Spec 73 — D3.4): espelho acessível do
// `ConfirmDialog` do console. Focus trap (Tab cíclico), Escape cancela,
// autofocus guiado pelo tom (danger → Cancelar; primary → Confirmar), foco
// restaurado ao fechar, `aria-labelledby`/`aria-describedby`. Sem step-up
// (o portal não tem reautenticação TOTP).

import { useEffect, useId, useRef, type ReactNode } from 'react';
import { ErrorBanner } from './ui';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  busy: boolean;
  error: unknown;
  onConfirm: () => void;
  onCancel: () => void;
  children?: ReactNode;
  /** `danger` (default) — botão vermelho + foca Cancelar;
   *  `primary` — botão azul + foca Confirmar (confirmações neutras). */
  tone?: 'danger' | 'primary';
}

function focusableElements(root: HTMLElement): HTMLElement[] {
  return Array.from(
    root.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    ),
  );
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
  const ref = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<HTMLElement | null>(null);
  // Estabiliza callbacks inline — o effect NÃO re-roda com o modal aberto
  // (senão o foco pularia a cada render e o restoreRef guardaria o elemento
  // errado ao fechar).
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const toneRef = useRef(tone);
  toneRef.current = tone;

  useEffect(() => {
    if (!open) return;
    restoreRef.current = document.activeElement as HTMLElement | null;
    const el = ref.current;
    if (el) {
      const focusables = focusableElements(el);
      const target =
        toneRef.current === 'primary' ? focusables[focusables.length - 1] : focusables[0];
      target?.focus();
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onCancelRef.current();
        return;
      }
      if (e.key !== 'Tab' || !el) return;
      const focusables = focusableElements(el);
      if (!focusables.length) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      restoreRef.current?.focus();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- callbacks lidos via ref (estabilizados)
  }, [open]);

  if (!open) return null;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={msgId}>
      <div className="modal" ref={ref}>
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
