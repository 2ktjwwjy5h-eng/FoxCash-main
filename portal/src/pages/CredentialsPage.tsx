// Credenciais API do lojista (Spec 24) — listar/criar/revogar chaves com
// escopos via /api/v1/portal/credentials.
// Spec 73: "Revogar" (irreversível) abre ConfirmDialog antes de executar;
// feedback de sucesso/erro em TODAS as ações; botão "Copiar" na chave gerada;
// Spinner no carregamento.

import { useState, type FormEvent } from 'react';
import { Card, ErrorBanner, Field, Spinner, SuccessBanner, copyToClipboard, useAsync } from '../components/ui';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { api } from '../apiClient';

interface ApiKey {
  id: string;
  label: string;
  prefix: string;
  status: string;
  scopes: string[];
  lastUsedAt?: string | null;
}

const SCOPE_OPTIONS = ['transactions', 'withdrawals', 'billing', 'storefront', 'deliveries'];

export default function CredentialsPage() {
  const { data, error, loading, reload } = useAsync<ApiKey[]>(() => api.get('/api/v1/portal/credentials'), []);
  const [label, setLabel] = useState('');
  const [scopes, setScopes] = useState<string[]>(['transactions']);
  const [createdKey, setCreatedKey] = useState<string | null>(null);
  const [formError, setFormError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<ApiKey | null>(null);
  const [copied, setCopied] = useState(false);

  const toggleScope = (s: string) => {
    setScopes((prev) => (prev.includes(s) ? prev.filter((x) => x !== s) : [...prev, s]));
  };

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setFeedback(null);
    setSubmitting(true);
    try {
      const res = await api.post<{ id: string; secret?: string }>('/api/v1/portal/credentials', { label, scopes });
      setCreatedKey(res.secret ?? null);
      setCopied(false);
      setLabel('');
      reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setSubmitting(false);
    }
  };

  const runRevoke = async () => {
    if (!revokeTarget) return;
    setRevoking(true);
    setActionError(null);
    setFeedback(null);
    try {
      await api.post(`/api/v1/portal/credentials/${revokeTarget.id}/revoke`);
      setFeedback(`Chave ${revokeTarget.label || revokeTarget.prefix} revogada.`);
      setRevokeTarget(null);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setRevoking(false);
    }
  };

  const handleCopy = async () => {
    if (!createdKey) return;
    const ok = await copyToClipboard(createdKey);
    setCopied(ok);
    window.setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="page">
      <h1>Credenciais API</h1>
      <SuccessBanner message={feedback} />
      <ErrorBanner error={actionError} />
      <div className="grid-2">
        <Card title="Nova chave">
          <form className="form" onSubmit={handleCreate}>
            <ErrorBanner error={formError} />
            <Field
              label="Rótulo"
              required
              maxLength={100}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
            />
            <div className="field">
              <span className="field-label">Escopos</span>
              <div className="checkbox-row">
                {SCOPE_OPTIONS.map((s) => (
                  <label key={s} className="checkbox-label">
                    <input type="checkbox" checked={scopes.includes(s)} onChange={() => toggleScope(s)} />
                    {s}
                  </label>
                ))}
              </div>
            </div>
            <button type="submit" className="btn btn-primary" disabled={submitting || scopes.length === 0}>
              {submitting ? 'Gerando…' : 'Gerar chave'}
            </button>
          </form>
          {createdKey && (
            <div className="alert alert-success">
              <p>Chave gerada — copie agora, ela não será exibida novamente:</p>
              <code className="key-value">{createdKey}</code>
              <button type="button" className="btn btn-small" style={{ marginTop: 8 }} onClick={() => void handleCopy()}>
                {copied ? 'Copiado!' : 'Copiar'}
              </button>
            </div>
          )}
        </Card>

        <Card title="Chaves existentes">
          {loading ? (
            <Spinner />
          ) : error ? (
            <ErrorBanner error={error} />
          ) : (
            <ul className="plain-list">
              {(data || []).map((key) => (
                <li key={key.id} className="dest-item">
                  <div>
                    <div>
                      <strong>{key.label || key.prefix}</strong> <span className="tag">{key.status}</span>
                    </div>
                    <div className="muted">
                      {key.prefix} · {(key.scopes || []).join(', ')}
                      {key.lastUsedAt ? ` · último uso ${new Date(key.lastUsedAt).toLocaleString('pt-BR')}` : ''}
                    </div>
                  </div>
                  {key.status !== 'REVOKED' && (
                    <button
                      type="button"
                      className="btn btn-small btn-danger"
                      onClick={() => {
                        setActionError(null);
                        setRevokeTarget(key);
                      }}
                    >
                      Revogar
                    </button>
                  )}
                </li>
              ))}
              {(data || []).length === 0 && <li className="muted">Nenhuma chave cadastrada.</li>}
            </ul>
          )}
        </Card>
      </div>

      <ConfirmDialog
        open={revokeTarget !== null}
        title="Revogar chave"
        message={`Revogar a chave ${revokeTarget?.label || revokeTarget?.prefix || ''}? As integrações que a usam deixarão de funcionar. Ação irreversível.`}
        confirmLabel="Revogar"
        busy={revoking}
        error={actionError}
        onConfirm={() => void runRevoke()}
        onCancel={() => {
          setRevokeTarget(null);
          setActionError(null);
        }}
      />
    </div>
  );
}
