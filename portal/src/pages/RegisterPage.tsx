// Cadastro de lojista no portal (Spec 39 — cadastro com nome/e-mail/senha).
//
// Chama o endpoint público POST /api/v1/merchants (RegisterMerchantUseCase):
// validação no backend (nome ≤120, e-mail ≤255, senha 8–72, e-mail único → 409)
// + Turnstile (Spec 63, Fase C) + fingerprint (Spec 141). A API key é
// devolvida UMA única vez no cadastro — a tela a exibe com botão de copiar e
// avisa que não será exibida de novo (o lojista pode regenerar em Credenciais).
//
// O botão de envio só habilita com os termos de uso aceitos (politicas-uso.md);
// com o Turnstile ativo, também fica desabilitado ("Verificando anti-bot…") até
// o token do widget chegar (mesma trava do LoginPage). O token é consumido a
// cada tentativa e regenerado (reset) — o mesmo token nunca é reenviado.

import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ErrorBanner, Field, copyToClipboard } from '../components/ui';
import { ApiError, api } from '../apiClient';
import { getFingerprintHash } from '../fingerprint';
import { useDialogFocus } from '../hooks/useDialogFocus';
import { renderMarkdown } from '../terms';
// Conteúdo canônico dos termos (docs/politicas-uso.md) embutido no bundle via
// `?raw` — o modal mostra o MESMO documento do repositório (fonte única).
import termsMarkdown from '../../../docs/politicas-uso.md?raw';

/** Resposta do POST /api/v1/merchants — a apiKey só existe aqui (uma vez). */
interface MerchantRegistered {
  id: string;
  name: string;
  email: string;
  apiKey: string;
}

/** Carrega o script do Turnstile uma única vez (mesmo padrão do LoginPage). */
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

export default function RegisterPage() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [acceptedTerms, setAcceptedTerms] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const [copied, setCopied] = useState(false);
  const [registered, setRegistered] = useState<MerchantRegistered | null>(null);
  const [turnstileToken, setTurnstileToken] = useState('');
  // True enquanto o widget está ativo e o token ainda não chegou (segura o botão).
  const [turnstilePending, setTurnstilePending] = useState(false);
  const turnstileRef = useRef<HTMLDivElement>(null);
  // Id do widget (retornado por render()) — usado no reset p/ regenerar o token.
  const widgetIdRef = useRef<string | null>(null);
  // Timer de refresh: token Turnstile expira em ~5 min → regenera perto dos 4.
  const refreshTimerRef = useRef<number | null>(null);

  // Modal de termos (Spec 143): abre na MESMA tela — sem navegar para fora.
  // `useDialogFocus` cuida do focus trap + Escape + restauração do foco.
  const [termsOpen, setTermsOpen] = useState(false);
  const termsDialogRef = useDialogFocus({ open: termsOpen, onClose: () => setTermsOpen(false) });
  // Renderiza o markdown UMA vez (documento estático embutido no bundle).
  const termsHtml = useMemo(() => renderMarkdown(termsMarkdown), []);

  // Widget invisível do Turnstile — só quando o backend expõe a site key.
  useEffect(() => {
    let cancelled = false;
    fetch('/api/v1/portal/turnstile-config', { headers: { Accept: 'application/json' } })
      .then((res) => (res.ok ? res.json() : {}))
      .then((cfg: { siteKey?: string }) => {
        if (cancelled || !cfg?.siteKey) return;
        // Widget ativo: segura o botão até o token chegar (o backend rejeita
        // cadastro sem token — TurnstileVerifier). Mesma trava do LoginPage.
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

  // Widget ativo sem token resolvido ainda → botão bloqueado com aviso.
  const turnstileWaiting = turnstilePending && !turnstileToken;

  // Regenera o token ~4 min após a última emissão (o token Turnstile expira em
  // ~5 min). Sem isso, uma página deixada aberta enviaria token vencido →
  // siteverify responde invalid-input-response e o cadastro cai (turnstile_failed).
  const scheduleTokenRefresh = () => {
    if (refreshTimerRef.current != null) window.clearTimeout(refreshTimerRef.current);
    refreshTimerRef.current = window.setTimeout(() => {
      setTurnstileToken('');
      setTurnstilePending(true);
      const id = widgetIdRef.current;
      if (id) window.turnstile?.reset(id);
    }, 4 * 60 * 1000);
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!acceptedTerms) {
      setError('Você precisa aceitar os termos de uso e condições para se cadastrar.');
      return;
    }
    // Consome o token: o MESMO token não pode ser reenviado (siteverify rejeita
    // duplicata). Capturamos antes de zerar; após a tentativa o widget é
    // resetado e um token novo chega via callback (botão volta a "Verificando…").
    const token = turnstileToken;
    setTurnstileToken('');
    setSubmitting(true);
    try {
      const fingerprintHash = await getFingerprintHash();
      // Rota pública — sem Bearer ({ auth: false }); 409 = e-mail duplicado.
      const created = await api.post<MerchantRegistered>(
        '/api/v1/merchants',
        {
          name,
          email,
          password,
          turnstileToken: token || undefined,
          fingerprintHash: fingerprintHash || undefined,
        },
        { auth: false },
      );
      setRegistered(created);
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

  const handleCopy = async () => {
    if (registered && (await copyToClipboard(registered.apiKey))) {
      setCopied(true);
    }
  };

  // Tela de sucesso: API key exibida UMA vez (não é armazenada pelo servidor).
  if (registered) {
    return (
      <div className="auth-screen">
        <div className="auth-card">
          <h1 className="auth-title">FoxCash</h1>
          <p className="auth-subtitle">Conta criada — guarde sua chave de API</p>
          <p>
            Bem-vindo(a), <strong>{registered.name}</strong>! Sua chave de API é exibida{' '}
            <strong>apenas agora</strong> — depois desta tela ela não poderá ser consultada
            (gere uma nova em <em>Credenciais</em> se precisar).
          </p>
          <div className="key-value">{registered.apiKey}</div>
          <div className="checkbox-row" style={{ margin: '10px 0' }}>
            <button type="button" className="btn" onClick={handleCopy}>
              {copied ? 'Copiada ✓' : 'Copiar chave'}
            </button>
          </div>
          <button type="button" className="btn btn-primary" onClick={() => navigate('/login')}>
            Fazer login
          </button>
          <div className="auth-links">
            <Link to="/login">Voltar para o login</Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-screen">
      <form className="auth-card" onSubmit={handleSubmit}>
        <h1 className="auth-title">FoxCash</h1>
        <p className="auth-subtitle">Crie sua conta de lojista</p>
        <ErrorBanner error={error instanceof ApiError ? error.message : error} />
        <Field
          label="Nome"
          type="text"
          required
          autoComplete="name"
          maxLength={120}
          placeholder="Seu nome ou razão social"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <Field
          label="E-mail"
          type="email"
          required
          autoComplete="username"
          maxLength={255}
          placeholder="voce@exemplo.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Field
          label="Senha"
          type="password"
          required
          autoComplete="new-password"
          minLength={8}
          maxLength={72}
          placeholder="Mínimo de 8 caracteres"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <label className="checkbox-row" style={{ margin: '10px 0' }}>
          <input
            type="checkbox"
            checked={acceptedTerms}
            onChange={(e) => setAcceptedTerms(e.target.checked)}
            required
          />
          <span className="checkbox-label">
            Li e concordo com os{' '}
            {/* Button dentro do <label>: preventDefault/stopPropagation para NÃO
                disparar o toggle do checkbox ao abrir o modal. */}
            <button
              type="button"
              className="terms-link"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                setTermsOpen(true);
              }}
            >
              termos de uso e condições
            </button>
          </span>
        </label>
        {/* Widget invisible do Turnstile (Spec 63, Fase C) — só com site key. */}
        <div ref={turnstileRef} />
        <button type="submit" className="btn btn-primary" disabled={submitting || !acceptedTerms || turnstileWaiting}>
          {submitting ? 'Criando conta…' : turnstileWaiting ? 'Verificando anti-bot…' : 'Criar conta'}
        </button>
        <div className="auth-links">
          <Link to="/login">Já tenho conta — entrar</Link>
        </div>
      </form>

      {/* Modal de termos (Spec 143) — conteúdo do docs/politicas-uso.md renderizado
          na própria tela de cadastro (sem sair do site). */}
      {termsOpen && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="terms-title">
          <div className="modal modal-wide" ref={termsDialogRef}>
            <h2 id="terms-title" style={{ margin: 0, fontSize: 18 }}>
              Termos de uso e condições
            </h2>
            <div className="terms-body" dangerouslySetInnerHTML={{ __html: termsHtml }} />
            <div className="modal-actions">
              <button type="button" className="btn btn-primary" onClick={() => setTermsOpen(false)}>
                Entendi
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
