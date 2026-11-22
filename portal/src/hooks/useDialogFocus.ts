// Focus management acessível para modais (Spec 73 — D3.1): focus trap (Tab
// cíclico dentro do modal), Escape fecha (onClose), autofocus guiado
// (`'first'` | `'last'` | ref) e restauração do foco no elemento que abriu o
// modal ao desmontar. Espelho do `console/src/hooks/useDialogFocus.ts` (o
// portal ganhou os modais de conexão/edição de bots na Spec 76 — mesmo padrão).
//
// Autofocus guiado pelo tom: no `ConfirmDialog` o 1º focável do DOM é o botão
// Cancelar (vem antes de Confirmar) — `'first'` (danger) foca Cancelar
// (segurança: Enter acidental NÃO confirma ação destrutiva) e `'last'`
// (primary) foca Confirmar. Nos modais de formulário da Spec 76, `'first'`
// foca o primeiro campo (plataforma/display_name).

import { useEffect, useRef, type RefObject } from 'react';

/** Alvo do autofocus ao abrir: primeiro focável, último focável, ou um ref. */
export type InitialFocus = 'first' | 'last' | RefObject<HTMLElement | null>;

function focusableElements(root: HTMLElement): HTMLElement[] {
  return Array.from(
    root.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    ),
  );
}

export function useDialogFocus(opts: {
  open: boolean;
  onClose: () => void;
  initialFocus?: InitialFocus;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<HTMLElement | null>(null);
  // Estabiliza os callbacks inline (nova identidade a cada render) — o effect
  // NÃO re-roda com o modal aberto (senão o foco pularia a cada digitação e o
  // restoreRef guardaria o elemento errado ao fechar).
  const onCloseRef = useRef(opts.onClose);
  onCloseRef.current = opts.onClose;
  const initialFocusRef = useRef(opts.initialFocus);
  initialFocusRef.current = opts.initialFocus;

  useEffect(() => {
    if (!opts.open) return;

    // Salva quem tinha o foco para restaurar ao fechar.
    restoreRef.current = document.activeElement as HTMLElement | null;

    const el = ref.current;
    if (el) {
      const focusables = focusableElements(el);
      const target =
        initialFocusRef.current === 'last'
          ? focusables[focusables.length - 1]
          : typeof initialFocusRef.current === 'object' && initialFocusRef.current?.current
            ? initialFocusRef.current.current
            : focusables[0];
      target?.focus();
    }

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onCloseRef.current();
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
      // Restaura o foco no elemento que abriu o modal.
      restoreRef.current?.focus();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- callbacks lidos via ref (estabilizados)
  }, [opts.open]);

  return ref;
}
