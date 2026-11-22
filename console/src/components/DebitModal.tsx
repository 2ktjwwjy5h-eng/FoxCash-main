// Modal "Debitar saldo" (Spec 100 D5 + Spec 98): débito administrativo do
// wallet do lojista com motivo obrigatório e **step-up TOTP no cliente**
// (Spec 72 — 403 step_up_required → prompt → repete a ação). Sucesso → banner
// com o novo saldo. O backend valida saldo negativo permitido (Spec 98).

import { useId, useState } from 'react';
import { api, ApiError } from '../apiClient';
import { ErrorBanner, formatMoney } from './ui';
import { useDialogFocus } from '../hooks/useDialogFocus';
import { useStepUp } from '../hooks/useStepUp';
import type { DebitResult } from '../types';

export interface DebitModalProps {
  merchantId: string;
  merchantName: string;
  currentBalance: string | number;
  onClose: () => void;
  onSuccess: (result: DebitResult) => void;
}

export default function DebitModal({ merchantId, merchantName, currentBalance, onClose, onSuccess }: DebitModalProps) {
  const titleId = useId();
  const stepUp = useStepUp();
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const dialogRef = useDialogFocus({ open: true, onClose, initialFocus: 'first' });

  const submit = async () => {
    const value = Number(amount);
    if (!value || value <= 0) {
      setError(new ApiError(400, { code: 'invalid_argument', message: 'Informe um valor maior que zero.' }));
      return;
    }
    if (!reason.trim()) {
      setError(new ApiError(400, { code: 'reason_required', message: 'Informe o motivo do débito.' }));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await stepUp.run(() =>
        api.post<DebitResult>(`/api/v1/master/merchants/${merchantId}/config/debit`, {
          amount: value,
          reason: reason.trim(),
        }),
      );
      onSuccess(result);
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
          Debitar saldo de {merchantName}
        </h2>
        <p className="muted" style={{ margin: 0 }}>
          Saldo atual: <strong>{formatMoney(currentBalance)}</strong> — o débito é registrado no ledger
          (ADMIN_DEBIT) e auditado (Spec 98).
        </p>
        <label className="field">
          <span className="field-label">Valor (R$)</span>
          <input
            className="input"
            type="number"
            min="0"
            step="0.01"
            placeholder="0,00"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        <label className="field">
          <span className="field-label">Motivo (obrigatório)</span>
          <input
            className="input"
            placeholder="Ex.: cobrança indevida"
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
            {busy ? 'Processando…' : 'Debitar saldo'}
          </button>
        </div>
        {stepUp.promptElement}
      </div>
    </div>
  );
}
