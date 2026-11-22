// "Ver Shops" — modal de stores do lojista (Spec 99 D5): lista a identidade
// das lojas (Spec 48) via espelho read-only da Spec 97 (`GET
// /api/v1/master/merchants/{id}/stores`). Sem `social_links` (URLs sensíveis);
// vazio → empty state (Spec 73). Modal acessível (`useDialogFocus`).

import { useId } from 'react';
import { api } from '../apiClient';
import { Card, ErrorBanner, Spinner, formatDate, useAsync } from '../components/ui';
import { useDialogFocus } from '../hooks/useDialogFocus';
import type { MerchantStore } from '../types';

export interface StoresModalProps {
  merchantId: string;
  merchantName: string;
  onClose: () => void;
}

export default function StoresModal({ merchantId, merchantName, onClose }: StoresModalProps) {
  const titleId = useId();
  const { data, error, loading } = useAsync(
    () => api.get<MerchantStore[]>(`/api/v1/master/merchants/${merchantId}/stores`),
    [merchantId],
  );
  const dialogRef = useDialogFocus({ open: true, onClose, initialFocus: 'first' });

  return (
    <div
      className="modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="modal" ref={dialogRef} style={{ maxWidth: 560 }}>
        <h2 id={titleId} style={{ margin: 0, fontSize: 18 }}>
          Shops de {merchantName}
        </h2>
        {loading && <Spinner />}
        <ErrorBanner error={error} />
        {data && (
          <div className="stores-list">
            {data.length === 0 && <p className="muted">Nenhuma loja cadastrada.</p>}
            {data.map((s: MerchantStore) => (
              <Card key={s.id} title={`${s.emoji ? `${s.emoji} ` : ''}${s.name}`}>
                <ul className="plain-list">
                  <li>
                    <span>Título</span> <strong>{s.title || '—'}</strong>
                  </li>
                  <li>
                    <span>Cor</span> <strong>{s.color || '—'}</strong>
                  </li>
                  <li>
                    <span>Descrição</span> <strong>{s.description || '—'}</strong>
                  </li>
                  <li>
                    <span>Criada em</span> <strong>{formatDate(s.createdAt)}</strong>
                  </li>
                  <li>
                    <span>Atualizada em</span> <strong>{formatDate(s.updatedAt)}</strong>
                  </li>
                </ul>
              </Card>
            ))}
          </div>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}
