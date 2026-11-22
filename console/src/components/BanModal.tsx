// Modal "Banir conta" (Spec 100 D5 + Spec 98): banimento IRREVERSÍVEL
// (BANNED — bloqueia API, portal, saques e cobranças) com motivo obrigatório
// e **step-up TOTP no cliente** (Spec 72). Sucesso → banner + status BANNED.

import { useId, useState } from 'react';
import { api, ApiError } from '../apiClient';
import { ErrorBanner } from './ui';
import { useDialogFocus } from '../hooks/useDialogFocus';
import { useStepUp } from '../hooks/useStepUp';

export interface BanModalProps {
  merchantId: string;
  merchantName: string;
  onClose: () => void;
  onSuccess: () => void;
}

export default function BanModal({ merchantId, merchantName, onClose, onSuccess }: BanModalProps) {
  const titleId = useId();
  const stepUp = useStepUp();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const dialogRef = useDialogFocus({ open: true, onClose, initialFocus: 'first' });

  const submit = async () => {
    if (!reason.trim()) {
      setError(new ApiError(400, { code: 'reason_required', message: 'Informe o motivo do banimento.' }));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await stepUp.run(() =>
        api.post<void>(`/api/v1/master/merchants/${merchantId}/ban`, { reason: reason.trim() }),
      );
      onSuccess();
      onClose();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <div className="modal" ref={dialogRef}>
        <h2 id={titleId} style={{ margin: 0, fontSize: 18 }}>
          Banir conta de {merchantName}
        </h2>
        <p className="muted" style={{ margin: 0 }}>
          Ação <strong>irreversível</strong>: bloqueia a API, o portal, saques e cobranças do lojista
          (status BANNED). Exige reautenticação (TOTP).
        </p>
        <label className="field">
          <span className="field-label">Motivo (obrigatório)</span>
          <input
            className="input"
            placeholder="Ex.: fraude comprovada"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <ErrorBanner error={error} />
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancelar
          </button>
          <button type="button" className="btn btn-danger" onClick={submit} disabled={busy}>
            {busy ? 'Processando…' : 'Banir conta'}
          </button>
        </div>
        {stepUp.promptElement}
      </div>
    </div>
  );
}
