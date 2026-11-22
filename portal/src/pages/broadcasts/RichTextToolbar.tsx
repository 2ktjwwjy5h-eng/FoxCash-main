// Toolbar + área de texto do editor de broadcast (Spec 80/D3): insere o
// markup nativo da plataforma selecionada na posição do cursor do textarea
// (sem lib rich-text — CSS puro, padrão Spec 73).

import type { RefObject } from 'react';
import type { BotPlatform } from './types';
import { FORMAT_TOKENS } from './markup';

/** Envolve a seleção do textarea com open/close; devolve o novo valor. */
export function wrapSelection(textarea: HTMLTextAreaElement, open: string, close: string): string {
  const { selectionStart, selectionEnd, value } = textarea;
  const selected = value.slice(selectionStart, selectionEnd) || open + close;
  const next = value.slice(0, selectionStart) + open + selected + close + value.slice(selectionEnd);
  requestAnimationFrame(() => {
    textarea.focus();
    textarea.setSelectionRange(
      selectionStart + open.length,
      selectionStart + open.length + selected.length,
    );
  });
  return next;
}

/** Insere texto na posição do cursor (tags dinâmicas); devolve o novo valor. */
export function insertAtCursor(textarea: HTMLTextAreaElement, text: string): string {
  const { selectionStart, selectionEnd, value } = textarea;
  const next = value.slice(0, selectionStart) + text + value.slice(selectionEnd);
  requestAnimationFrame(() => {
    textarea.focus();
    textarea.setSelectionRange(selectionStart + text.length, selectionStart + text.length);
  });
  return next;
}

export function RichTextToolbar({
  platform,
  textareaRef,
  onApply,
}: {
  platform: BotPlatform;
  textareaRef: RefObject<HTMLTextAreaElement>;
  onApply: (value: string) => void;
}) {
  const tokens = FORMAT_TOKENS[platform];
  return (
    <div className="bc-toolbar" role="toolbar" aria-label="Formatação">
      {tokens.map((t) => (
        <button
          key={t.id}
          type="button"
          title={t.title}
          aria-label={t.title}
          className="btn btn-small"
          onClick={() => {
            const el = textareaRef.current;
            if (el) onApply(wrapSelection(el, t.open, t.close));
          }}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

export function RichTextArea({
  textareaRef,
  value,
  onChange,
  disabled,
}: {
  textareaRef: RefObject<HTMLTextAreaElement>;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  return (
    <label className="field">
      <span className="field-label">Mensagem</span>
      <textarea
        ref={textareaRef}
        className="input bc-content"
        rows={6}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        placeholder="Escreva a mensagem da transmissão…"
      />
    </label>
  );
}
