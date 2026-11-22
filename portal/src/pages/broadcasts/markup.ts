// Marcação rich-text do editor de broadcast (Spec 80/D3) e preview.
// Telegram: HTML (b/i/u/s/code/pre/blockquote/tg-spoiler); Discord: Markdown.
// O preview é uma simulação segura: o texto é escapado ANTES de aplicar apenas
// os tokens conhecidos — nunca injeta HTML arbitrário (sem lib rich-text).

import type { BotPlatform } from './types';

export interface FormatToken {
  id: string;
  label: string;
  title: string;
  open: string;
  close: string;
}

/** Tokens de formatação por plataforma (inseridos na posição do cursor). */
export const FORMAT_TOKENS: Record<BotPlatform, FormatToken[]> = {
  TELEGRAM: [
    { id: 'bold', label: 'B', title: 'Negrito', open: '<b>', close: '</b>' },
    { id: 'italic', label: 'I', title: 'Itálico', open: '<i>', close: '</i>' },
    { id: 'underline', label: 'U', title: 'Sublinhado', open: '<u>', close: '</u>' },
    { id: 'strike', label: 'S', title: 'Riscado', open: '<s>', close: '</s>' },
    { id: 'code', label: '</>', title: 'Código inline', open: '<code>', close: '</code>' },
    { id: 'pre', label: 'Pre', title: 'Bloco de código', open: '<pre>', close: '</pre>' },
    { id: 'quote', label: '❝', title: 'Citação', open: '<blockquote>', close: '</blockquote>' },
    { id: 'spoiler', label: 'Spoiler', title: 'Spoiler', open: '<tg-spoiler>', close: '</tg-spoiler>' },
  ],
  DISCORD: [
    { id: 'bold', label: 'B', title: 'Negrito', open: '**', close: '**' },
    { id: 'italic', label: 'I', title: 'Itálico', open: '*', close: '*' },
    { id: 'underline', label: 'U', title: 'Sublinhado', open: '__', close: '__' },
    { id: 'strike', label: 'S', title: 'Riscado', open: '~~', close: '~~' },
    { id: 'code', label: '</>', title: 'Código inline', open: '`', close: '`' },
    { id: 'pre', label: 'Pre', title: 'Bloco de código', open: '```\n', close: '\n```' },
    { id: 'quote', label: '❝', title: 'Citação', open: '> ', close: '' },
    { id: 'spoiler', label: 'Spoiler', title: 'Spoiler', open: '||', close: '||' },
  ],
};

/** Tags dinâmicas inseríveis no editor (dicas de resolução no preview). */
export const TAG_INSERTS: Array<{ tag: string; hint: string }> = [
  { tag: '{primeiro_nome}', hint: 'Primeiro nome do destinatário' },
  { tag: '{username}', hint: 'Usuário na plataforma' },
  { tag: '{data}', hint: 'Data de envio' },
];

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** Substitui as tags dinâmicas por exemplos legíveis no preview (D3). */
export function resolveTags(text: string): string {
  return text
    .replace(/\{primeiro_nome\}/g, 'João')
    .replace(/\{username\}/g, '@joao')
    .replace(/\{data\}/g, new Date().toLocaleDateString('pt-BR'));
}

/**
 * Renderiza o markup do editor para HTML seguro (apenas para o preview).
 * O texto é escapado primeiro; só os tokens conhecidos da plataforma viram
 * elementos (o resto permanece texto).
 */
export function renderMarkup(text: string, platform: BotPlatform): string {
  let s = escapeHtml(text);
  if (platform === 'TELEGRAM') {
    const map: Array<[string, string]> = [
      ['&lt;b&gt;', '<strong>'],
      ['&lt;/b&gt;', '</strong>'],
      ['&lt;i&gt;', '<em>'],
      ['&lt;/i&gt;', '</em>'],
      ['&lt;u&gt;', '<u>'],
      ['&lt;/u&gt;', '</u>'],
      ['&lt;s&gt;', '<s>'],
      ['&lt;/s&gt;', '</s>'],
      ['&lt;code&gt;', '<code>'],
      ['&lt;/code&gt;', '</code>'],
      ['&lt;pre&gt;', '<pre>'],
      ['&lt;/pre&gt;', '</pre>'],
      ['&lt;blockquote&gt;', '<blockquote>'],
      ['&lt;/blockquote&gt;', '</blockquote>'],
      ['&lt;tg-spoiler&gt;', '<span class="bc-spoiler">'],
      ['&lt;/tg-spoiler&gt;', '</span>'],
    ];
    for (const [from, to] of map) s = s.split(from).join(to);
    return s;
  }
  // Discord Markdown — ordem importa: bloco antes de inline, ** antes de *.
  s = s.replace(/```([\s\S]*?)```/g, '<pre>$1</pre>');
  s = s.replace(/`([^`\n]+)`/g, '<code>$1</code>');
  s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/__([^_\n]+)__/g, '<u>$1</u>');
  s = s.replace(/~~([^~\n]+)~~/g, '<s>$1</s>');
  s = s.replace(/(^|\n)> ([^\n]+)/g, '$1<blockquote>$2</blockquote>');
  s = s.replace(/\|\|([^|\n]+)\|\|/g, '<span class="bc-spoiler">$1</span>');
  s = s.replace(/(^|\s)\*([^*\n]+)\*/g, '$1<em>$2</em>');
  return s;
}
