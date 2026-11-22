// Preview em tempo real do broadcast (Spec 80/D3): bolha simulada da
// plataforma selecionada renderizando texto formatado, mídia e botões
// exatamente como o envio. As tags dinâmicas aparecem como exemplo
// (João, @joao, data atual). O markup é renderizado com segurança
// (markup.ts — o texto é escapado antes dos tokens conhecidos).

import type { BotPlatform, BroadcastMode, ButtonGrid, StoreBot, UploadedMedia } from './types';
import { renderMarkup, resolveTags } from './markup';
import { MODE_LABELS } from './useBroadcastForm';

const MEDIA_LABEL: Record<UploadedMedia['mediaType'], string> = {
  IMAGE: 'Imagem',
  VIDEO: 'Vídeo',
  AUDIO: 'Áudio',
};

function renderButtons(buttons: ButtonGrid) {
  return buttons.map((row, ri) => (
    <div key={ri} className="bc-buttons-preview-row">
      {row.map((b, bi) => (
        <span key={bi} className="tg-inline-btn">
          {b.label.trim() || (b.type === 'link' ? b.url || 'Link' : b.callback || 'Callback')}
        </span>
      ))}
    </div>
  ));
}

function renderDiscordButtons(buttons: ButtonGrid) {
  return buttons.map((row, ri) => (
    <div key={ri} className="dc-action-row">
      {row.map((b, bi) => (
        <span key={bi} className="dc-action-btn">
          {b.label.trim() || (b.type === 'link' ? b.url || 'Link' : b.callback || 'Callback')}
        </span>
      ))}
    </div>
  ));
}

export function BroadcastPreview({
  platform,
  bot,
  targetTitle,
  content,
  media,
  buttons,
  mode,
}: {
  platform: BotPlatform | null;
  bot: StoreBot | null;
  targetTitle: string | null;
  content: string;
  media: UploadedMedia | null;
  buttons: ButtonGrid;
  mode: BroadcastMode;
}) {
  const html = renderMarkup(resolveTags(content), platform || 'TELEGRAM');
  const mediaNode = media ? (
    <div className="bc-preview-media">
      {media.mediaUrl ? (
        <img className="bc-preview-img" src={media.mediaUrl} alt="Mídia anexada" />
      ) : (
        <span className="bc-preview-file">{MEDIA_LABEL[media.mediaType]}</span>
      )}
    </div>
  ) : null;

  return (
    // D5: SEM aria-live no container mutável (o preview re-renderiza a cada
    // tecla/upload/edição — anunciar o texto completo seria flood). O anúncio
    // discreto fica no rodapé (Modo: X — muda raramente): aria-live="polite"
    // anunciando só o estado, nunca o conteúdo.
    <section className="card bc-preview" aria-label="Prévia da transmissão">
      <h2 className="card-title">Prévia</h2>
      <div className="bc-preview-body">
        {platform === 'DISCORD' ? (
          <div className="dc-message">
            <div className="dc-avatar">{bot?.displayName?.[0] || 'B'}</div>
            <div className="dc-body">
              <span className="dc-author">{bot?.displayName || 'Bot'}</span>
              {targetTitle && <span className="muted">→ {targetTitle}</span>}
              {mediaNode}
              {content && (
                <div className="dc-text" dangerouslySetInnerHTML={{ __html: html }} />
              )}
              {buttons.length > 0 && <div className="dc-actions">{renderDiscordButtons(buttons)}</div>}
            </div>
          </div>
        ) : (
          <div className="tg-bubble">
            <div className="tg-bubble-name">{bot?.displayName || 'Bot'}</div>
            {targetTitle && <div className="tg-bubble-channel muted">{targetTitle}</div>}
            {mediaNode}
            {content && (
              <div className="tg-bubble-text" dangerouslySetInnerHTML={{ __html: html }} />
            )}
            {buttons.length > 0 && <div className="bc-buttons-preview">{renderButtons(buttons)}</div>}
          </div>
        )}
        <div className="bc-preview-foot muted" aria-live="polite">
          Modo: {MODE_LABELS[mode]}
        </div>
      </div>
    </section>
  );
}
