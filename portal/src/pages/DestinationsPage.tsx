// Destinos de saque (Spec 31) — CRUD de chaves Pix/bancários. A API devolve
// APENAS o valor MASCARADO (campo `mask`); o valor em claro NUNCA chega ao DOM
// (requisito do E2E: sem valor em claro no DOM).
// Spec 73: feedback de sucesso/erro em TODAS as ações (create/toggle/updateLabel
// — antes sem try/catch → unhandled rejection silencioso) e Spinner no loading.

import { useState, type FormEvent } from 'react';
import { Card, ErrorBanner, Field, Spinner, SuccessBanner, useAsync } from '../components/ui';
import { api } from '../apiClient';

interface Destination {
  id: string;
  type: string;
  label: string;
  mask: string;
  status: string;
}

const TYPE_OPTIONS = ['PIX_CPF', 'PIX_CNPJ', 'PIX_PHONE', 'PIX_EVP', 'BANK_ACCOUNT'];

export default function DestinationsPage() {
  const { data, error, loading, reload } = useAsync<Destination[]>(() => api.get('/api/v1/portal/destinations'), []);
  const [type, setType] = useState('PIX_CPF');
  const [value, setValue] = useState('');
  const [label, setLabel] = useState('');
  const [formError, setFormError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setFeedback(null);
    setSubmitting(true);
    try {
      await api.post('/api/v1/portal/destinations', { type, value, label });
      setValue('');
      setLabel('');
      setFeedback('Destino cadastrado.');
      reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setSubmitting(false);
    }
  };

  const toggleActive = async (dest: Destination) => {
    const action = dest.status === 'ACTIVE' ? 'deactivate' : 'activate';
    setActionError(null);
    setFeedback(null);
    try {
      await api.post(`/api/v1/portal/destinations/${dest.id}/${action}`);
      setFeedback(dest.status === 'ACTIVE' ? 'Destino desativado.' : 'Destino ativado.');
      reload();
    } catch (err) {
      setActionError(err);
    }
  };

  const updateLabel = async (dest: Destination, newLabel: string) => {
    setActionError(null);
    setFeedback(null);
    try {
      await api.patch(`/api/v1/portal/destinations/${dest.id}`, { label: newLabel });
      setFeedback('Rótulo atualizado.');
      reload();
    } catch (err) {
      setActionError(err);
    }
  };

  return (
    <div className="page">
      <h1>Destinos de saque</h1>
      <SuccessBanner message={feedback} />
      <ErrorBanner error={actionError} />
      <div className="grid-2">
        <Card title="Novo destino">
          <form className="form" onSubmit={handleCreate}>
            <ErrorBanner error={formError} />
            <label className="field">
              <span className="field-label">Tipo</span>
              <select className="input" value={type} onChange={(e) => setType(e.target.value)}>
                {TYPE_OPTIONS.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </label>
            <Field
              label="Chave / dados bancários"
              required
              maxLength={100}
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
            <Field label="Rótulo" maxLength={100} value={label} onChange={(e) => setLabel(e.target.value)} />
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? 'Salvando…' : 'Cadastrar'}
            </button>
          </form>
        </Card>

        <Card title="Destinos cadastrados">
          {loading ? (
            <Spinner />
          ) : error ? (
            <ErrorBanner error={error} />
          ) : (
            <ul className="plain-list">
              {(data || []).map((dest) => (
                <li key={dest.id} className="dest-item">
                  <div>
                    <div>
                      <strong>{dest.label || dest.type}</strong> <span className="tag">{dest.status}</span>
                    </div>
                    {/* Somente o MÁSCARA — o valor em claro nunca é renderizado */}
                    <div className="muted">{dest.mask}</div>
                  </div>
                  <div className="dest-actions">
                    <input
                      className="input input-small"
                      defaultValue={dest.label || ''}
                      placeholder="Rótulo"
                      onBlur={(e) => {
                        if (e.target.value && e.target.value !== dest.label) void updateLabel(dest, e.target.value);
                      }}
                    />
                    <button
                      type="button"
                      className="btn btn-small"
                      onClick={() => void toggleActive(dest)}
                    >
                      {dest.status === 'ACTIVE' ? 'Desativar' : 'Ativar'}
                    </button>
                  </div>
                </li>
              ))}
              {(data || []).length === 0 && <li className="muted">Nenhum destino cadastrado.</li>}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}
