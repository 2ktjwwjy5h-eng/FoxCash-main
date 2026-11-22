// Gestão de perfis master (Spec 72, fatia 2 — D4.4; Specs 41/69): página
// admin-only (master.profiles.manage — o backend decide). Listagem é leitura
// (admin sem step-up); criar/ativar/desativar são ações sensíveis com step-up
// TOTP no cliente. Senha temporária exibida UMA vez.

import { useState } from 'react';
import { api } from '../apiClient';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Card, ErrorBanner, Spinner, SuccessBanner, copyToClipboard, formatDate, useAsync } from '../components/ui';
import { useStepUp } from '../hooks/useStepUp';
import type { MasterCreatedProfile, MasterProfile } from '../types';

interface CreateForm {
  username: string;
  name: string;
}

export default function ProfilesPage() {
  const stepUp = useStepUp();
  const { data, error, loading, reload } = useAsync(() => api.get<MasterProfile[]>('/api/v1/master/profiles'), []);

  const [createForm, setCreateForm] = useState<CreateForm>({ username: '', name: '' });
  const [createBusy, setCreateBusy] = useState(false);
  const [createError, setCreateError] = useState<unknown>(null);
  const [created, setCreated] = useState<MasterCreatedProfile | null>(null);

  const [deactivateTarget, setDeactivateTarget] = useState<MasterProfile | null>(null);
  const [activateTarget, setActivateTarget] = useState<MasterProfile | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const createValid = createForm.username.trim() && createForm.name.trim();

  const runCreate = async () => {
    setCreateBusy(true);
    setCreateError(null);
    setFeedback(null);
    try {
      const profile = await stepUp.run(() =>
        api.post<MasterCreatedProfile>('/api/v1/master/profiles', {
          username: createForm.username.trim(),
          name: createForm.name.trim(),
        }),
      );
      setCreated(profile);
      setCreateForm({ username: '', name: '' });
      reload();
    } catch (err) {
      setCreateError(err);
    } finally {
      setCreateBusy(false);
    }
  };

  const runToggle = async (profile: MasterProfile, action: 'activate' | 'deactivate') => {
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      await stepUp.run(() => api.post<void>(`/api/v1/master/profiles/${profile.id}/${action}`));
      setFeedback(`Perfil ${profile.username} ${action === 'activate' ? 'ativado' : 'desativado'}.`);
      setActivateTarget(null);
      setDeactivateTarget(null);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <h1>Perfis master</h1>
      <SuccessBanner message={feedback} />

      <Card title="Criar perfil">
        <p className="muted">A senha é gerada pelo sistema e exibida UMA vez. Ação sensível — exige reautenticação (TOTP).</p>
        <div className="grid-2">
          <label className="field">
            <span className="field-label">Usuário (login)</span>
            <input
              className="input"
              value={createForm.username}
              onChange={(e) => setCreateForm((f) => ({ ...f, username: e.target.value }))}
              placeholder="ex.: operador2"
            />
          </label>
          <label className="field">
            <span className="field-label">Nome</span>
            <input
              className="input"
              value={createForm.name}
              onChange={(e) => setCreateForm((f) => ({ ...f, name: e.target.value }))}
              placeholder="ex.: Operador 2"
            />
          </label>
        </div>
        <div className="modal-actions" style={{ marginTop: 8 }}>
          <button type="button" className="btn btn-primary" disabled={!createValid || createBusy} onClick={runCreate}>
            {createBusy ? 'Criando…' : 'Criar perfil'}
          </button>
        </div>
        <ErrorBanner error={createError} />
      </Card>

      <Card title="Perfis existentes">
        {loading && <Spinner />}
        <ErrorBanner error={error} />
        {data && (
          <table className="table">
            <thead>
              <tr>
                <th>Usuário</th>
                <th>Nome</th>
                <th>Status</th>
                <th>Admin</th>
                <th>Criado em</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {data.map((p: MasterProfile) => (
                <tr key={p.id}>
                  <td>{p.username}</td>
                  <td>{p.name}</td>
                  <td>
                    <span className="tag">{p.active ? 'ATIVO' : 'INATIVO'}</span>
                  </td>
                  <td>{p.canManageMasters ? 'Sim' : 'Não'}</td>
                  <td>{formatDate(p.createdAt)}</td>
                  <td>
                    {p.active ? (
                      <button
                        type="button"
                        className="btn btn-small btn-danger"
                        onClick={() => {
                          setActionError(null);
                          setDeactivateTarget(p);
                        }}
                      >
                        Desativar
                      </button>
                    ) : (
                      <button
                        type="button"
                        className="btn btn-small"
                        onClick={() => {
                          setActionError(null);
                          setActivateTarget(p);
                        }}
                      >
                        Ativar
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {data.length === 0 && (
                <tr>
                  <td colSpan={6} className="muted">
                    Nenhum perfil criado ainda.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        )}
      </Card>

      <ConfirmDialog
        open={deactivateTarget !== null}
        title="Desativar perfil"
        message={`Desativar o perfil ${deactivateTarget?.username}? Ele perderá o acesso ao console. Ação sensível — exige reautenticação (TOTP).`}
        confirmLabel="Desativar"
        busy={busy}
        error={actionError}
        onConfirm={() => deactivateTarget && runToggle(deactivateTarget, 'deactivate')}
        onCancel={() => {
          setDeactivateTarget(null);
          setActionError(null);
        }}
      />
      <ConfirmDialog
        open={activateTarget !== null}
        title="Ativar perfil"
        message={`Ativar o perfil ${activateTarget?.username}? Ele voltará a acessar o console. Ação sensível — exige reautenticação (TOTP).`}
        confirmLabel="Ativar"
        tone="primary"
        busy={busy}
        error={actionError}
        onConfirm={() => activateTarget && runToggle(activateTarget, 'activate')}
        onCancel={() => {
          setActivateTarget(null);
          setActionError(null);
        }}
      />

      <ConfirmDialog
        open={created !== null}
        title="Perfil criado"
        message={`Perfil ${created?.username} criado. Anote a senha temporária abaixo — ela NÃO será exibida novamente.`}
        confirmLabel="Já copiei"
        tone="primary"
        busy={false}
        error={null}
        onConfirm={() => setCreated(null)}
        onCancel={() => setCreated(null)}
      >
        <div className="secret-box" style={{ margin: '8px 0' }}>
          {created?.temporaryPassword}
        </div>
        <button
          type="button"
          className="btn btn-small"
          disabled={!created}
          onClick={async () => {
            if (!created) return;
            const ok = await copyToClipboard(created.temporaryPassword);
            setCopied(ok);
            window.setTimeout(() => setCopied(false), 2000);
          }}
        >
          {copied ? 'Copiado!' : 'Copiar'}
        </button>
      </ConfirmDialog>

      {stepUp.promptElement}
    </div>
  );
}
