// Gerador de cobrança PIX (Spec 23) — cobrança avulsa + Gift PIX com
// QR Code/Copia e Cola (POST /api/v1/portal/charges e /charges/gift).
// Spec 73: fix do loading dos presets (era <ErrorBanner error={undefined}/> →
// nada visível), "Copiar" com feedback "Copiado!", empty state dos presets e
// mensagem de valor específica.

import { useState, type FormEvent } from 'react';
import { Card, ErrorBanner, Field, Spinner, copyToClipboard, formatMoney, useAsync } from '../components/ui';
import { api } from '../apiClient';

interface PixCharge {
  transactionId: string;
  externalReference: string;
  pixQrCode: string;
  pixCopyPaste: string;
  status: string;
  createdAt: string;
  amount?: number;
}
interface GiftPreset {
  id: string;
  label: string;
  value: number;
  active: boolean;
}

export default function ChargePage() {
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [created, setCreated] = useState<PixCharge | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const [copied, setCopied] = useState(false);

  const presets = useAsync<GiftPreset[]>(() => api.get('/api/v1/portal/charges/gift-presets'), []);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const value = Number(amount);
      if (!Number.isFinite(value) || value <= 0) {
        throw new Error('Informe um valor maior que zero');
      }
      // Spec 28: idempotencyKey obrigatória e única por lojista — nova a cada
      // submissão (evita cobrança duplicada em retry do cliente).
      const res = await api.post<PixCharge>('/api/v1/portal/charges', {
        amount: value,
        description,
        idempotencyKey: crypto.randomUUID(),
      });
      setCreated(res);
      setCopied(false);
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  const handleGift = async (presetId: string) => {
    setError(null);
    setSubmitting(true);
    try {
      const res = await api.post<PixCharge>('/api/v1/portal/charges/gift', {
        presetId,
        idempotencyKey: crypto.randomUUID(),
      });
      setCreated(res);
      setCopied(false);
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  const handleCopy = async () => {
    if (!created) return;
    const ok = await copyToClipboard(created.pixCopyPaste);
    setCopied(ok);
    window.setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="page">
      <h1>Cobrança PIX</h1>

      <div className="grid-2">
        <Card title="Cobrança avulsa">
          <form className="form" onSubmit={handleSubmit}>
            <ErrorBanner error={error} />
            <Field
              label="Valor (R$)"
              type="number"
              step="0.01"
              min="0.01"
              required
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
            <Field
              label="Descrição"
              type="text"
              maxLength={200}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? 'Gerando…' : 'Gerar cobrança'}
            </button>
          </form>
        </Card>

        <Card title="Gift PIX">
          {presets.loading ? (
            <Spinner />
          ) : presets.error ? (
            <ErrorBanner error={presets.error} />
          ) : (
            <ul className="plain-list">
              {(presets.data || []).map((p) => (
                <li key={p.id}>
                  <span>
                    {p.label} — {formatMoney(p.value)}
                  </span>
                  <button
                    type="button"
                    className="btn btn-small"
                    disabled={submitting || !p.active}
                    onClick={() => handleGift(p.id)}
                  >
                    Gerar
                  </button>
                </li>
              ))}
              {(presets.data || []).length === 0 && <li className="muted">Nenhum preset disponível.</li>}
            </ul>
          )}
        </Card>
      </div>

      {created && (
        <Card title="Cobrança gerada">
          <div className="charge-result">
            <div className="qr-box">
              {created.pixQrCode ? (
                <img src={created.pixQrCode} alt="QR Code PIX" className="qr-image" />
              ) : (
                <div className="muted">QR Code indisponível</div>
              )}
            </div>
            <div className="charge-details">
              <p>
                <strong>Status:</strong> <span className="tag">{created.status}</span>
              </p>
              <p>
                <strong>Referência:</strong> {created.externalReference}
              </p>
              <label className="field-label" htmlFor="copy-paste">
                Copia e Cola
              </label>
              <textarea id="copy-paste" className="input" readOnly rows={3} value={created.pixCopyPaste} />
              <button type="button" className="btn" onClick={() => void handleCopy()}>
                {copied ? 'Copiado!' : 'Copiar'}
              </button>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}
