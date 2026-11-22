// Redefinição de senha via token one-time (Spec 40/42) — link do e-mail aponta
// para /reset-password?token=... (Spec 45).

import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../apiClient';
import { ErrorBanner, Field } from '../components/ui';

export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') || '';
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (newPassword !== confirm) {
      setError(new Error('As senhas não conferem'));
      return;
    }
    if (!token) {
      setError(new Error('Token de redefinição ausente na URL'));
      return;
    }
    setSubmitting(true);
    try {
      await api.post<void>('/api/v1/merchant-auth/reset-password', { token, newPassword }, { auth: false });
      setDone(true);
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  if (done) {
    return (
      <div className="auth-screen">
        <div className="auth-card">
          <h1 className="auth-title">FoxCash</h1>
          <p className="auth-subtitle">Senha redefinida com sucesso.</p>
          <Link to="/login" className="btn btn-primary">
            Ir para o login
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-screen">
      <form className="auth-card" onSubmit={handleSubmit}>
        <h1 className="auth-title">FoxCash</h1>
        <p className="auth-subtitle">Nova senha</p>
        <ErrorBanner error={error} />
        <Field
          label="Nova senha"
          type="password"
          required
          autoComplete="new-password"
          minLength={8}
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
        />
        <Field
          label="Confirmar nova senha"
          type="password"
          required
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Salvando…' : 'Redefinir senha'}
        </button>
        <div className="auth-links">
          <Link to="/login">Voltar ao login</Link>
        </div>
      </form>
    </div>
  );
}
