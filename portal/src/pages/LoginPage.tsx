// Login do portal (Spec 46). 401 aqui = credenciais inválidas (a rota é pública
// no interceptor). Sucesso → guarda token em sessionStorage e navega ao
// dashboard (ou ao destino original salvo pelo RequireAuth).
//
// Spec 63 (Fase C): quando o backend expõe uma site key de Turnstile, monta o
// widget (invisible) e envia o token no body do login. Sem site key configurada
// o widget não renderiza e o login segue o fluxo antigo (feature off).
// Com o widget ativo o botão fica desabilitado ("Verificando anti-bot…") até o
// token chegar — elimina a corrida de clicar antes do desafio resolver. O token
// é consumido a cada tentativa e regenerado (reset) — o mesmo token nunca é
// reenviado (siteverify rejeita duplicata e tokens vencidos em ~5 min).

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';
import { ErrorBanner, Field } from '../components/ui';
import { ApiError } from '../apiClient';
import { getFingerprintHash } from '../fingerprint';

/** Carrega o script do Turnstile uma única vez (render explícito). */
function loadTurnstileScript(onReady: () => void): void {
  const existing = document.getElementById('turnstile-script') as HTMLScriptElement | null;
  if (existing) {
    if (window.turnstile) {
      onReady();
    } else {
      existing.addEventListener('load', onReady, { once: true });
    }
    return;
  }
  const script = document.createElement('script');
  script.id = 'turnstile-script';
  script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
  script.async = true;
  script.defer = true;
  script.addEventListener('load', onReady, { once: true });
  document.head.appendChild(script);
}

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const [turnstileToken, setTurnstileToken] = useState('');
  // True enquanto o widget está ativo e o token ainda não chegou (segura o botão).
  const [turnstilePending, setTurnstilePending] = useState(false);
  const turnstileRef = useRef<HTMLDivElement>(null);
  // Id do widget (retornado por render()) — usado no reset p/ regenerar o token.
  const widgetIdRef = useRef<string | null>(null);
  // Timer de refresh: token Turnstile expira em ~5 min → regenera perto dos 4.
  const refreshTimerRef = useRef<number | null>(null);

  const from = (location.state as { from?: string } | null)?.from || '/dashboard';

  // Widget ativo sem token resolvido ainda → botão bloqueado com aviso.
  const turnstileWaiting = turnstilePending && !turnstileToken;

  // Regenera o token ~4 min após a última emissão (o token Turnstile expira em
  // ~5 min). Sem isso, uma página deixada aberta enviaria token vencido →
  // siteverify responde invalid-input-response e o login cai (turnstile_failed).
  const scheduleTokenRefresh = () => {
    if (refreshTimerRef.current != null) window.clearTimeout(refreshTimerRef.current);
    refreshTimerRef.current = window.setTimeout(() => {
      setTurnstileToken('');
      setTurnstilePending(true);
      const id = widgetIdRef.current;
      if (id) window.turnstile?.reset(id);
    }, 4 * 60 * 1000);
  };

  // Busca a site key pública e monta o widget (só quando o backend a expõe).
  useEffect(() => {
    let cancelled = false;
    fetch('/api/v1/portal/turnstile-config', { headers: { Accept: 'application/json' } })
      .then((res) => (res.ok ? res.json() : {}))
      .then((cfg: { siteKey?: string }) => {
        if (cancelled || !cfg?.siteKey) return;
        // Widget ativo: segura o botão até o token chegar (o backend rejeita
        // login sem token — TurnstileVerifier). Sem essa trava, clicar em
        // "Entrar" antes do desafio resolver (~1–2s) causa o erro de Turnstile.
        setTurnstilePending(true);
        loadTurnstileScript(() => {
          if (cancelled) return;
          if (window.turnstile && turnstileRef.current) {
            try {
              const widgetId = window.turnstile.render(turnstileRef.current, {
                sitekey: cfg.siteKey as string,
                callback: (token: string) => {
                  setTurnstileToken(token);
                  setTurnstilePending(false);
                  scheduleTokenRefresh();
                },
                'error-callback': () => {
                  // Desafio falhou/rede bloqueada: libera o botão — o backend
                  // responde com a mensagem de Turnstile se o token faltar.
                  setTurnstilePending(false);
                },
              });
              widgetIdRef.current = widgetId;
              return;
            } catch {
              /* widget indisponível → cai no fallback abaixo */
            }
          }
          // Sem widget (script bloqueado, ref ausente) → fluxo antigo.
          setTurnstilePending(false);
        });
      })
      .catch(() => {
        /* sem site key/disponível → widget não renderiza (feature off) */
      });
    return () => {
      cancelled = true;
      if (refreshTimerRef.current != null) window.clearTimeout(refreshTimerRef.current);
    };
  }, []);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    // Consome o token: o MESMO token não pode ser reenviado (siteverify rejeita
    // duplicata). Capturamos antes de zerar; após a tentativa o widget é
    // resetado e um token novo chega via callback (botão volta a "Verificando…").
    const token = turnstileToken;
    setTurnstileToken('');
    setSubmitting(true);
    try {
      // Token só é enviado quando o widget está ativo (site key presente).
      // Spec 94: totpCode opcional — enviado só quando preenchido.
      // Spec 141: fingerprint do navegador — best-effort ('' → backend ignora).
      const fingerprintHash = await getFingerprintHash();
      await login(email, password, totpCode.trim() || undefined, token || undefined, fingerprintHash || undefined);
      navigate(from, { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
      // Regenera o token para uma próxima tentativa (se o widget estiver ativo).
      const id = widgetIdRef.current;
      if (id) {
        setTurnstilePending(true);
        window.turnstile?.reset(id);
      }
    }
  };

  return (
    <div className="auth-screen">
      <form className="auth-card" onSubmit={handleSubmit}>
        <h1 className="auth-title">FoxCash</h1>
        <p className="auth-subtitle">Portal do Lojista</p>
        <ErrorBanner error={error instanceof ApiError ? error.message : error} />
        <Field
          label="E-mail"
          type="email"
          required
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
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
          value={totpCode}
          onChange={(e) => setTotpCode(e.target.value)}
        />
        {/* Widget invisible do Turnstile (Spec 63, Fase C) — só com site key. */}
        <div ref={turnstileRef} />
        <button type="submit" className="btn btn-primary" disabled={submitting || turnstileWaiting}>
          {submitting ? 'Entrando…' : turnstileWaiting ? 'Verificando anti-bot…' : 'Entrar'}
        </button>
        <div className="auth-links">
          <Link to="/forgot-password">Esqueci a senha</Link>
          <Link to="/register">Criar conta</Link>
        </div>
      </form>
    </div>
  );
}
