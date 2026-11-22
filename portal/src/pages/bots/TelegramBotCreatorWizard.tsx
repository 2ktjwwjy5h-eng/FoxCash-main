// Wizard "Criador de Bot Telegram" (Spec 77) — fluxo guiado do zero até o bot
// vendendo no FoxCash, com verificação em tempo real em cada etapa:
//
//   intro → botfather(token) → validar(getMe) → setup(opcional) → register → done
//
// Backend: NENHUMA rota nova no fluxo principal (Specs 74/76). Aditivos mínimos
// documentados: POST /api/v1/portal/bots/validate (getMe sem criar registro —
// mostra nome/@ antes de continuar) e POST /api/v1/portal/bots/setup (foto/
// descrição/descrição curta via Bot API — stateless). O registro final reusa o
// POST /api/v1/portal/bots da Spec 74 (getMe + setWebhook + secret_token).
//
// Regras de consistência (critério 6): os passos de validação/setup são
// opcionais e stateless no servidor; SÓ o passo register cria o bot. Se o
// lojista voltar após registrar, o register NÃO é reexecutado (idempotência no
// cliente — flag `registered`).
//
// Spec 73: banners de sucesso/erro em toda ação de escrita (sem unhandled
// rejection), Spinner no loading, Field com validação clara, foco/aria nos
// passos.

import { useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ErrorBanner, Field, SuccessBanner } from '../../components/ui';
import { api } from '../../apiClient';

type Step = 'intro' | 'botfather' | 'validar' | 'setup' | 'register' | 'done';

const TOKEN_REGEX = /^\d+:[A-Za-z0-9_-]{30,}$/;

const STEPS: { key: Step; label: string }[] = [
  { key: 'intro', label: 'Introdução' },
  { key: 'botfather', label: 'Criar no BotFather' },
  { key: 'validar', label: 'Validar token' },
  { key: 'setup', label: 'Configuração' },
  { key: 'register', label: 'Registro' },
  { key: 'done', label: 'Conclusão' },
];

interface BotIdentity {
  displayName: string;
  username: string | null;
}

/** Lê um arquivo de imagem como base64 (sem o prefixo data:). */
function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || '');
      resolve(dataUrl.split(',')[1] || '');
    };
    reader.onerror = () => reject(new Error('Falha ao ler o arquivo de imagem'));
    reader.readAsDataURL(file);
  });
}

export default function TelegramBotCreatorWizard() {
  const [step, setStep] = useState<Step>('intro');
  const [token, setToken] = useState('');
  const [identity, setIdentity] = useState<BotIdentity | null>(null);
  const [photoBase64, setPhotoBase64] = useState<string | null>(null);
  const [photoName, setPhotoName] = useState('');
  const [description, setDescription] = useState('');
  const [shortDescription, setShortDescription] = useState('');
  const [setupApplied, setSetupApplied] = useState(false);
  const [registered, setRegistered] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const tokenValid = TOKEN_REGEX.test(token.trim());

  /**
   * Navega entre passos — nunca re-executa register (idempotência no cliente).
   * `keepFeedback` preserva o banner de sucesso ao avançar para a próxima etapa
   * (Spec 77: o toast de "Configuração aplicada" deve continuar visível na
   * etapa de registro — o E2E depende disso).
   */
  const go = (next: Step, keepFeedback = false) => {
    setError(null);
    if (!keepFeedback) setFeedback(null);
    setStep(next);
    window.scrollTo({ top: 0 });
  };

  const runAction = async (
    fn: () => Promise<unknown>,
    successMessage: string,
  ): Promise<boolean> => {
    setBusy(true);
    setError(null);
    setFeedback(null);
    try {
      await fn();
      setFeedback(successMessage);
      return true;
    } catch (err) {
      setError(err);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const handleValidate = () =>
    runAction(
      () => api.post<BotIdentity>('/api/v1/portal/bots/validate', { token: token.trim() }).then(setIdentity),
      'Token válido — bot encontrado no Telegram.',
    ).then((ok) => ok && go('validar'));

  const handleSetup = () =>
    runAction(
      () =>
        api.post('/api/v1/portal/bots/setup', {
          token: token.trim(),
          description: description.trim() || undefined,
          shortDescription: shortDescription.trim() || undefined,
          photoBase64: photoBase64 || undefined,
        }),
      'Configuração aplicada no Telegram.',
    ).then((ok) => {
      if (ok) {
        setSetupApplied(true);
        go('register', true);
      }
    });

  const handleRegister = () =>
    runAction(
      () =>
        api.post('/api/v1/portal/bots', {
          platform: 'TELEGRAM',
          token: token.trim(),
          displayName: identity?.displayName || undefined,
        }),
      'Bot registrado e no ar!',
    ).then((ok) => {
      if (ok) {
        setRegistered(true);
        go('done', true);
      }
    });

  const handlePhoto = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const base64 = await fileToBase64(file);
      setPhotoBase64(base64);
      setPhotoName(file.name);
      setError(null);
    } catch (err) {
      setError(err);
    }
  };

  const stepIndex = STEPS.findIndex((s) => s.key === step);

  return (
    <div className="page">
      <h1>Criar bot Telegram</h1>
      <p className="muted">
        Assistente passo a passo para colocar seu bot de vendas no ar — do
        BotFather até o FoxCash.
      </p>

      <ol className="wizard-progress" aria-label="Progresso do assistente">
        {STEPS.map((s, i) => (
          <li
            key={s.key}
            aria-current={i === stepIndex ? 'step' : undefined}
            className={i < stepIndex ? 'wizard-progress-done' : ''}
          >
            <span className="wizard-progress-num">{i + 1}</span>
            {s.label}
          </li>
        ))}
      </ol>

      <SuccessBanner message={feedback} />
      <ErrorBanner error={error} />

      {step === 'intro' && <IntroStep onStart={() => go('botfather')} />}

      {step === 'botfather' && (
        <BotFatherStep
          token={token}
          tokenValid={tokenValid}
          onTokenChange={setToken}
          busy={busy}
          onValidate={() => void handleValidate()}
          onBack={() => go('intro')}
        />
      )}

      {step === 'validar' && (
        <ValidationStep
          identity={identity}
          onBack={() => go('botfather')}
          onContinue={() => go('setup')}
        />
      )}

      {step === 'setup' && (
        <SetupStep
          photoBase64={photoBase64}
          photoName={photoName}
          description={description}
          shortDescription={shortDescription}
          busy={busy}
          onPhotoChange={(e) => void handlePhoto(e)}
          onDescriptionChange={setDescription}
          onShortDescriptionChange={setShortDescription}
          onApply={() => void handleSetup()}
          onSkip={() => go('register')}
          onBack={() => go('validar')}
        />
      )}

      {step === 'register' && (
        <RegisterStep
          identity={identity}
          tokenValid={tokenValid}
          busy={busy}
          registered={registered}
          onRegister={() => void handleRegister()}
          onBack={() => (setupApplied ? go('setup') : go('validar'))}
        />
      )}

      {step === 'done' && (
        <DoneStep identity={identity} username={identity?.username || ''} />
      )}
    </div>
  );
}

function IntroStep({ onStart }: { onStart: () => void }) {
  return (
    <section className="card">
      <h2 className="card-title">O que é um bot de vendas?</h2>
      <p>
        Um bot do Telegram recebe o cliente, apresenta sua loja e cobra o Pix —
        tudo automaticamente. Ele usa o mesmo catálogo e os mesmos planos da
        sua loja no FoxCash.
      </p>
      <h3 className="card-title">Pré-requisitos</h3>
      <ul className="wizard-list">
        <li>Uma loja ativa no FoxCash.</li>
        <li>
          Acesso ao <a href="https://t.me/BotFather" target="_blank" rel="noreferrer">@BotFather</a> do
          Telegram (no passo seguinte criamos o bot lá).
        </li>
        <li>O token do bot criado no BotFather.</li>
      </ul>
      <div className="wizard-actions">
        <button type="button" className="btn btn-primary" onClick={onStart}>
          Começar
        </button>
      </div>
    </section>
  );
}

function BotFatherStep({
  token,
  tokenValid,
  onTokenChange,
  busy,
  onValidate,
  onBack,
}: {
  token: string;
  tokenValid: boolean;
  onTokenChange: (value: string) => void;
  busy: boolean;
  onValidate: () => void;
  onBack: () => void;
}) {
  return (
    <section className="card">
      <h2 className="card-title">Criar o bot no BotFather</h2>
      <ol className="wizard-list">
        <li>
          Abra o{' '}
          <a href="https://t.me/BotFather" target="_blank" rel="noreferrer">
            @BotFather
          </a>{' '}
          no Telegram e envie <code>/newbot</code>.
        </li>
        <li>Informe o nome do bot (ex.: “Bot de Vendas da Minha Loja”).</li>
        <li>
          Informe o username — deve <strong>terminar em “bot”</strong> (ex.:{' '}
          <code>minhaloja_bot</code>).
        </li>
        <li>
          Copie o <strong>token</strong> que o BotFather enviar (formato{' '}
          <code>123456789:AAAA...</code>) e cole abaixo.
        </li>
      </ol>

      <form
        className="form"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          if (tokenValid) onValidate();
        }}
      >
        <Field
          label="Token do bot (do BotFather)"
          required
          type="password"
          autoComplete="off"
          value={token}
          onChange={(e) => onTokenChange(e.target.value)}
          aria-describedby="token-hint"
        />
        <p id="token-hint" className="muted">
          {token.length > 0 && !tokenValid
            ? 'Formato inválido — deve ser como 123456789:AAAbbbCCC... (números:letras).'
            : 'Cole o token exatamente como o BotFather enviou.'}
        </p>
        <div className="wizard-actions">
          <button type="button" className="btn" onClick={onBack} disabled={busy}>
            Voltar
          </button>
          <button type="submit" className="btn btn-primary" disabled={!tokenValid || busy}>
            {busy ? 'Validando…' : 'Validar token'}
          </button>
        </div>
      </form>
    </section>
  );
}

function ValidationStep({
  identity,
  onBack,
  onContinue,
}: {
  identity: BotIdentity | null;
  onBack: () => void;
  onContinue: () => void;
}) {
  if (!identity) {
    return (
      <section className="card">
        <p>Token ainda não validado. Volte e cole o token do BotFather.</p>
        <div className="wizard-actions">
          <button type="button" className="btn" onClick={onBack}>
            Voltar
          </button>
        </div>
      </section>
    );
  }
  return (
    <section className="card">
      <h2 className="card-title">Bot encontrado!</h2>
      <div className="bot-identity-preview">
        <span className="bot-avatar" aria-hidden="true">
          TG
        </span>
        <div>
          <strong>{identity.displayName}</strong>
          <span className="muted">@{identity.username || 'sem @'}</span>
        </div>
      </div>
      <p>
        Este é o bot conectado ao token. Confira se é o bot correto antes de
        continuar.
      </p>
      <div className="wizard-actions">
        <button type="button" className="btn" onClick={onBack}>
          Trocar token
        </button>
        <button type="button" className="btn btn-primary" onClick={onContinue}>
          Continuar
        </button>
      </div>
    </section>
  );
}

function SetupStep({
  photoBase64,
  photoName,
  description,
  shortDescription,
  busy,
  onPhotoChange,
  onDescriptionChange,
  onShortDescriptionChange,
  onApply,
  onSkip,
  onBack,
}: {
  photoBase64: string | null;
  photoName: string;
  description: string;
  shortDescription: string;
  busy: boolean;
  onPhotoChange: (e: ChangeEvent<HTMLInputElement>) => void;
  onDescriptionChange: (value: string) => void;
  onShortDescriptionChange: (value: string) => void;
  onApply: () => void;
  onSkip: () => void;
  onBack: () => void;
}) {
  return (
    <section className="card">
      <h2 className="card-title">Configuração inicial (opcional)</h2>
      <p className="muted">
        Ajuste a aparência do bot antes de registrar. Se preferir, pule esta
        etapa — o bot funciona sem ela.
      </p>

      <div className="form">
        <span className="field-label">Foto de perfil</span>
        <input
          className="input"
          type="file"
          accept="image/jpeg,image/png"
          onChange={onPhotoChange}
          aria-label="Foto de perfil do bot"
        />
        {photoBase64 ? (
          <p className="muted">
            Foto selecionada: <strong>{photoName}</strong>
          </p>
        ) : (
          <p className="muted">Nenhuma foto selecionada (opcional).</p>
        )}

        <label className="field">
          <span className="field-label">Descrição do bot</span>
          <textarea
            className="input"
            rows={3}
            maxLength={512}
            value={description}
            onChange={(e) => onDescriptionChange(e.target.value)}
            placeholder="O que o bot faz? (ex.: venda de planos e produtos)"
          />
        </label>

        <label className="field">
          <span className="field-label">Descrição curta</span>
          <textarea
            className="input"
            rows={2}
            maxLength={140}
            value={shortDescription}
            onChange={(e) => onShortDescriptionChange(e.target.value)}
            placeholder="Resumo em uma linha (ex.: “Venda pelo Telegram!”)"
          />
        </label>
      </div>

      <div className="wizard-actions">
        <button type="button" className="btn" onClick={onBack} disabled={busy}>
          Voltar
        </button>
        <button type="button" className="btn" onClick={onSkip} disabled={busy}>
          Pular etapa
        </button>
        <button type="button" className="btn btn-primary" onClick={onApply} disabled={busy}>
          {busy ? 'Aplicando…' : 'Aplicar e continuar'}
        </button>
      </div>
    </section>
  );
}

function RegisterStep({
  identity,
  tokenValid,
  busy,
  registered,
  onRegister,
  onBack,
}: {
  identity: BotIdentity | null;
  tokenValid: boolean;
  busy: boolean;
  registered: boolean;
  onRegister: () => void;
  onBack: () => void;
}) {
  return (
    <section className="card">
      <h2 className="card-title">Registrar no FoxCash</h2>
      {registered ? (
        <p>
          Este bot já foi registrado. Nenhuma chamada adicional será feita — vá
          para “Meus Bots” para gerenciá-lo.
        </p>
      ) : (
        <>
          <p>
            O FoxCash vai registrar o webhook com um segredo único, criptografar
            o token e ativar o bot{' '}
            <strong>{identity?.displayName || 'do Telegram'}</strong>.
          </p>
          <p className="muted">
            O registro é a única etapa que cria o bot — validar e configurar
            acima não grava nada.
          </p>
        </>
      )}
      <div className="wizard-actions">
        <button type="button" className="btn" onClick={onBack} disabled={busy}>
          Voltar
        </button>
        {!registered && (
          <button
            type="button"
            className="btn btn-primary"
            disabled={!tokenValid || busy}
            onClick={onRegister}
          >
            {busy ? 'Registrando…' : 'Registrar bot'}
          </button>
        )}
        <Link className="btn" to="/bots">
          Meus Bots
        </Link>
      </div>
    </section>
  );
}

function DoneStep({ identity, username }: { identity: BotIdentity | null; username: string }) {
  return (
    <section className="card">
      <h2 className="card-title">Bot no ar!</h2>
      <div className="bot-identity-preview">
        <span className="bot-avatar" aria-hidden="true">
          TG
        </span>
        <div>
          <strong>{identity?.displayName || 'Bot do Telegram'}</strong>
          <span className="muted">@{username || 'sem @'}</span>
        </div>
        <span className="tag tag-success">ONLINE</span>
      </div>
      <p>
        Seu bot está registrado e recebendo mensagens. Agora é só divulgar o
        <code> @{username || 'username_do_bot'}</code> para os clientes.
      </p>
      <div className="wizard-actions">
        <Link className="btn btn-primary" to="/bots">
          Ver Meus Bots
        </Link>
      </div>
    </section>
  );
}
