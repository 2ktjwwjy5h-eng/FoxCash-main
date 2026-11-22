// Upload de mídia do broadcast (Spec 80/D3): valida tipo/tamanho no cliente
// (imagem <=10MB; vídeo/áudio <=50MB — espelho da Spec 79/D7) e envia via
// POST /api/v1/portal/broadcasts/media (multipart). O apiClient só serializa
// JSON — aqui o fetch usa FormData + Bearer manualmente.

import { useRef, useState } from 'react';
import { ApiError, getToken } from '../../apiClient';
import { ErrorBanner } from '../../components/ui';
import type { BroadcastMediaType, UploadedMedia } from './types';

const MAX_IMAGE = 10 * 1024 * 1024;
const MAX_VIDEO_AUDIO = 50 * 1024 * 1024;

const MEDIA_LABEL: Record<BroadcastMediaType, string> = {
  IMAGE: 'Imagem',
  VIDEO: 'Vídeo',
  AUDIO: 'Áudio',
};

function mediaTypeFor(file: File): BroadcastMediaType | null {
  if (file.type.startsWith('image/')) return 'IMAGE';
  if (file.type.startsWith('video/')) return 'VIDEO';
  if (file.type.startsWith('audio/')) return 'AUDIO';
  return null;
}

export function MediaUploader({
  botId,
  media,
  onUploaded,
  onRemoved,
}: {
  botId: string;
  media: UploadedMedia | null;
  onUploaded: (media: UploadedMedia) => void;
  onRemoved: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<unknown>(null);
  const [uploading, setUploading] = useState(false);

  const handleFile = async (file: File | undefined) => {
    if (!file) return;
    setError(null);
    if (!botId) {
      setError(new Error('Selecione o bot antes de enviar a mídia.'));
      return;
    }
    const type = mediaTypeFor(file);
    if (!type) {
      setError(new Error('Tipo de arquivo não suportado: envie imagem, vídeo ou áudio.'));
      return;
    }
    const max = type === 'IMAGE' ? MAX_IMAGE : MAX_VIDEO_AUDIO;
    if (file.size > max) {
      const mb = type === 'IMAGE' ? 10 : 50;
      setError(
        new Error(`Arquivo excede o limite de ${mb}MB para ${MEDIA_LABEL[type].toLowerCase()}.`),
      );
      return;
    }
    setUploading(true);
    try {
      const form = new FormData();
      form.append('storeBotId', botId);
      form.append('mediaType', type);
      form.append('file', file);
      const token = getToken();
      const res = await fetch('/api/v1/portal/broadcasts/media', {
        method: 'POST',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: form,
      });
      if (!res.ok) {
        let body: { code?: string; message?: string } = {};
        try {
          body = (await res.json()) as { code?: string; message?: string };
        } catch {
          /* corpo vazio */
        }
        throw new ApiError(res.status, body);
      }
      const uploaded = (await res.json()) as UploadedMedia;
      onUploaded(uploaded);
    } catch (err) {
      setError(err);
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="bc-media">
      <div className="bc-media-row">
        <span className="field-label">Mídia</span>
        <input
          ref={inputRef}
          type="file"
          accept="image/*,video/*,audio/*"
          aria-label="Mídia"
          className="bc-file-input"
          onChange={(e) => void handleFile(e.target.files?.[0])}
        />
        <button
          type="button"
          className="btn btn-small"
          disabled={uploading || !botId}
          onClick={() => inputRef.current?.click()}
        >
          {uploading ? 'Enviando…' : media ? 'Trocar mídia' : 'Enviar mídia'}
        </button>
        {media && (
          <>
            <span className="muted">
              {MEDIA_LABEL[media.mediaType]} anexada
              {media.mediaFileId ? ` · file_id ${media.mediaFileId}` : ''}
            </span>
            <button type="button" className="btn btn-small btn-danger" onClick={onRemoved}>
              Remover
            </button>
          </>
        )}
      </div>
      <div className="muted">Imagem até 10MB · Vídeo/áudio até 50MB</div>
      <ErrorBanner error={error} />
    </div>
  );
}
