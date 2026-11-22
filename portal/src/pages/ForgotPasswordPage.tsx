// "Esqueci a senha" (Spec 40/45). Em produção o backend responde sempre 204
// (anti-enumeração) — a UI mostra mensagem neutra de sucesso e volta ao login.

import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../apiClient';
import { ErrorBanner, Field } from '../components/ui';

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [sent, setSent] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await api.post<void>('/api/v1/merchant-auth/forgot-password', { email }, { auth: false });
      setSent(true);
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  if (sent) {
    return (
      <div className="auth-screen">
        <div className="auth-card">
          <h1 className="auth-title">FoxCash</h1>
          <p className="auth-subtitle">Se o e-mail existir, enviaremos um link de redefinição.</p>
          <Link to="/login" className="btn btn-primary">
            Voltar ao login
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-screen">
      <form className="auth-card" onSubmit={handleSubmit}>
        <h1 className="auth-title">FoxCash</h1>
        <p className="auth-subtitle">Recuperação de senha</p>
        <ErrorBanner error={error} />
        <Field
          label="E-mail cadastrado"
          type="email"
          required
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Enviando…' : 'Enviar link'}
        </button>
        <div className="auth-links">
          <Link to="/login">Voltar ao login</Link>
        </div>
      </form>
    </div>
  );
}
