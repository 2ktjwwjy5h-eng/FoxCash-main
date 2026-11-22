// Autogestão de MFA (Spec 71; Spec 44 — sempre o próprio perfil): ativar em 2
// passos (activate → confirm), desativar (senha + TOTP/recovery), regenerar
// recovery codes. Secrets (manualEntryKey / recovery codes) exibidos UMA vez.
// `me.mfaActive` alimenta o estado da tela.

import { useState, type FormEvent } from 'react';
import { useAuth } from '../auth';
import { api } from '../apiClient';
import { Card, ErrorBanner, Field, SuccessBanner, copyToClipboard } from '../components/ui';

interface ActivateResult {
  otpauthUri: string;
  manualEntryKey: string;
}

interface ConfirmResult {
  recoveryCodes: string[];
}

export default function SecurityPage() {
  const { me, refreshMe } = useAuth();
  const [step, setStep] = useState<'idle' | 'activated' | 'confirmed'>('idle');
  const [activateResult, setActivateResult] = useState<ActivateResult | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copiedCodes, setCopiedCodes] = useState(false);

  const copyCodes = async () => {
    const ok = await copyToClipboard(recoveryCodes.join('\n'));
    setCopiedCodes(ok);
    window.setTimeout(() => setCopiedCodes(false), 2000);
  };

  const resetFeedback = () => {
    setError(null);
    setSuccess(null);
  };

  const handleActivate = async () => {
    resetFeedback();
    setBusy(true);
    try {
      const result = await api.post<ActivateResult>('/api/v1/master/mfa/activate');
      setActivateResult(result);
      setStep('activated');
      setCode('');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const handleConfirm = async (e: FormEvent) => {
    e.preventDefault();
    resetFeedback();
    setBusy(true);
    try {
      const result = await api.post<ConfirmResult>('/api/v1/master/mfa/confirm', { code });
      setRecoveryCodes(result.recoveryCodes);
      setStep('confirmed');
      setCode('');
      await refreshMe();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const handleDeactivate = async (e: FormEvent) => {
    e.preventDefault();
    resetFeedback();
    setBusy(true);
    try {
      await api.post<void>('/api/v1/master/mfa/deactivate', { code, password });
      setSuccess('MFA desativado.');
      setCode('');
      setPassword('');
      setStep('idle');
      setActivateResult(null);
      setRecoveryCodes([]);
      await refreshMe();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const handleRegenerate = async (e: FormEvent) => {
    e.preventDefault();
    resetFeedback();
    setBusy(true);
    try {
      const result = await api.post<ConfirmResult>('/api/v1/master/mfa/recovery-codes', { code });
      setRecoveryCodes(result.recoveryCodes);
      setSuccess('Recovery codes regenerados (exibidos 1x).');
      setCode('');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <h1>Segurança (MFA)</h1>
      <ErrorBanner error={error} />
      <SuccessBanner message={success} />

      {!me?.mfaActive && (
        <Card title="MFA não ativado">
          <p>
            Ative a autenticação em 2 fatores (TOTP — RFC 6238) para proteger o acesso ao console. As ações
            sensíveis exigem step-up (Spec 72); ativar o MFA já é recomendado.
          </p>
          <button type="button" className="btn btn-primary" onClick={handleActivate} disabled={busy}>
            {busy ? 'Gerando…' : 'Ativar MFA'}
          </button>

          {step === 'activated' && activateResult && (
            <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
              <p className="muted">Passo 1/2 — escaneie o QR no app autenticador ou insira a chave manual (exibida 1x):</p>
              <div className="secret-box">{activateResult.manualEntryKey}</div>
              <p className="muted">URI do QR (para apps que aceitam link):</p>
              <div className="secret-box">{activateResult.otpauthUri}</div>
              <form className="form" onSubmit={handleConfirm}>
                <Field
                  label="Código TOTP (do app autenticador)"
                  type="text"
                  inputMode="numeric"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  required
                />
                <button type="submit" className="btn btn-primary" disabled={busy}>
                  {busy ? 'Confirmando…' : 'Confirmar e ativar'}
                </button>
              </form>
            </div>
          )}

          {step === 'confirmed' && (
            <div style={{ marginTop: 12 }}>
              <p className="muted">Passo 2/2 — recovery codes (exibidos 1x — guarde em local seguro):</p>
              <ul className="plain-list">
                {recoveryCodes.map((c) => (
                  <li key={c} className="secret-box" style={{ marginBottom: 4 }}>
                    {c}
                  </li>
                ))}
              </ul>
              <button type="button" className="btn btn-small" style={{ marginTop: 8 }} onClick={() => void copyCodes()}>
                {copiedCodes ? 'Copiado!' : 'Copiar todos'}
              </button>
              <p className="muted">MFA ativado. Recarregue a página ou navegue para confirmar.</p>
            </div>
          )}
        </Card>
      )}

      {me?.mfaActive && (
        <>
          <Card title="MFA ativo">
            <p className="muted">A autenticação em 2 fatores está ativa para este perfil.</p>
            <form className="form" onSubmit={handleDeactivate} style={{ maxWidth: 400 }}>
              <Field
                label="Código TOTP (ou recovery code)"
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
              />
              <Field
                label="Senha atual"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                autoComplete="current-password"
              />
              <button type="submit" className="btn btn-danger" disabled={busy}>
                {busy ? 'Desativando…' : 'Desativar MFA'}
              </button>
            </form>
          </Card>

          <Card title="Regenerar recovery codes">
            <form className="form" onSubmit={handleRegenerate} style={{ maxWidth: 400 }}>
              <Field
                label="Código TOTP (ou recovery code)"
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
              />
              <button type="submit" className="btn" disabled={busy}>
                {busy ? 'Regenerando…' : 'Regenerar recovery codes'}
              </button>
            </form>
            {recoveryCodes.length > 0 && (
              <div style={{ marginTop: 12 }}>
                <p className="muted">Novos recovery codes (1x):</p>
                <ul className="plain-list">
                  {recoveryCodes.map((c) => (
                    <li key={c} className="secret-box" style={{ marginBottom: 4 }}>
                      {c}
                    </li>
                  ))}
                </ul>
                <button type="button" className="btn btn-small" style={{ marginTop: 8 }} onClick={() => void copyCodes()}>
                  {copiedCodes ? 'Copiado!' : 'Copiar todos'}
                </button>
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
