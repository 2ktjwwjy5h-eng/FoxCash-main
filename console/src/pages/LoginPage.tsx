// Login do Master Console (Spec 71; Specs 13/44). Campo TOTP opcional — com MFA
// ativo a senha não basta (401 uniforme, sem oráculo — a página mostra erro
// genérico "credenciais inválidas"). Sucesso → token em sessionStorage (chave
// própria do console) → GET /master/me → dashboard (ou o destino original).

import { useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';
import { ErrorBanner, Field } from '../components/ui';
import { ApiError } from '../apiClient';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  const from = (location.state as { from?: string } | null)?.from || '/console/dashboard';

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(username, password, totpCode.trim() || undefined);
      navigate(from, { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-screen">
      <form className="auth-card" onSubmit={handleSubmit}>
        <h1 className="auth-title">FoxCash</h1>
        <p className="auth-subtitle">Master Console</p>
        <ErrorBanner error={error instanceof ApiError ? error.message : error} />
        <Field
          label="Usuário"
          type="text"
          required
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <Field
          label="Senha"
          type="password"
          required
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Field
          label="Código TOTP (se o MFA estiver ativo)"
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          placeholder="6 dígitos"
          value={totpCode}
          onChange={(e) => setTotpCode(e.target.value)}
        />
        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
    </div>
  );
}
