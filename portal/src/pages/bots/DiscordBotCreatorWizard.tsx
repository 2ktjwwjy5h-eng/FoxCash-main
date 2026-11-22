// Wizard "Criador de Bot Discord" (Spec 78) — fluxo guiado do zero até o bot
// vendendo no FoxCash, com verificação em tempo real em cada etapa:
//
//   intro → devportal(token/appId) → credenciais → validar(getMe) →
//   setup(opcional) → register → done(convite OAuth2)
//
// Backend: aditivos mínimos documentados — POST /api/v1/portal/bots/validate
// (getMe sem criar registro — mostra username antes de continuar) e
// POST /api/v1/portal/bots/setup (avatar/descrição via PATCH /users/@me +
// comandos slash via PUT /applications/{id}/commands — stateless), ambos com
// `platform: "DISCORD"`. O registro final reusa o POST /api/v1/portal/bots da
// Spec 74 (getMe + comandos slash + applicationId).
//
// Regras de consistência (critério 6): os passos de validação/setup são
// opcionais e stateless no servidor; SÓ o passo register cria o bot. Se o
// lojista voltar após registrar, o register NÃO é reexecutado (idempotência no
// cliente — flag `registered`). O DoneStep oferece o link de convite OAuth2
// (scope=bot&permissions=...) para adicionar o bot ao servidor.
//
// Spec 73: banners de sucesso/erro em toda ação de escrita (sem unhandled
// rejection), Spinner no loading, Field com validação clara, foco/aria nos
// passos.

import { useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ErrorBanner, Field, SuccessBanner } from '../../components/ui';
import { api } from '../../apiClient';

type Step = 'intro' | 'devportal' | 'credenciais' | 'validar' | 'setup' | 'register' | 'done';

// Token do Discord: 3 partes separadas por ponto (base64url) — formato
// típico de bot (id.timestamp.secret).
const TOKEN_REGEX = /^[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}$/;
// Application ID do Discord: snowflake (18-20 dígitos).
const APP_ID_REGEX = /^\d{17,20}$/;

// Permissões mínimas para o bot vender (Spec 78/D4): enviar mensagens (2048) +
// embeds (16384). Permissões por papel são pós-criação (Spec 76).
const INVITE_PERMISSIONS = 2048 + 16384;

const STEPS: { key: Step; label: string }[] = [
  { key: 'intro', label: 'Introdução' },
  { key: 'devportal', label: 'Developer Portal' },
  { key: 'credenciais', label: 'Credenciais' },
  { key: 'validar', label: 'Validar conexão' },
  { key: 'setup', label: 'Configuração' },
  { key: 'register', label: 'Registro' },
  { key: 'done', label: 'Conclusão' },
];

interface BotIdentity {
  displayName: string;
  username: string | null;
}

interface RegisteredBot {
  applicationId?: string | null;
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

export default function DiscordBotCreatorWizard() {
  const [step, setStep] = useState<Step>('intro');
  const [token, setToken] = useState('');
  const [applicationId, setApplicationId] = useState('');
  const [identity, setIdentity] = useState<BotIdentity | null>(null);
  const [avatarBase64, setAvatarBase64] = useState<string | null>(null);
  const [avatarName, setAvatarName] = useState('');
  const [description, setDescription] = useState('');
  const [setupApplied, setSetupApplied] = useState(false);
  const [registeredBot, setRegisteredBot] = useState<RegisteredBot | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const tokenValid = TOKEN_REGEX.test(token.trim());
  const appIdValid = APP_ID_REGEX.test(applicationId.trim());
  const credentialsValid = tokenValid && appIdValid;

  /**
   * Navega entre passos — nunca re-executa register (idempotência no cliente).
   * `keepFeedback` preserva o banner de sucesso ao avançar para a próxima etapa
   * (Spec 78: o toast de "Configuração aplicada" deve continuar visível na
   * etapa de registro — mesmo comportamento da Spec 77).
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
      () =>
        api
          .post<BotIdentity>('/api/v1/portal/bots/validate', {
            platform: 'DISCORD',
            token: token.trim(),
          })
          .then(setIdentity),
      'Conexão validada — bot encontrado no Discord.',
    ).then((ok) => ok && go('validar', true));

  const handleSetup = () =>
    runAction(
      () =>
        api.post('/api/v1/portal/bots/setup', {
          platform: 'DISCORD',
          token: token.trim(),
          applicationId: applicationId.trim(),
          description: description.trim() || undefined,
          avatarBase64: avatarBase64 || undefined,
        }),
      'Configuração aplicada no Discord.',
    ).then((ok) => {
      if (ok) {
        setSetupApplied(true);
        go('register', true);
      }
    });

  const handleRegister = () =>
    runAction(
      () =>
        api
          .post<RegisteredBot>('/api/v1/portal/bots', {
            platform: 'DISCORD',
            token: token.trim(),
            applicationId: applicationId.trim(),
            displayName: identity?.displayName || undefined,
          })
          .then(setRegisteredBot),
      'Bot registrado e no ar!',
    ).then((ok) => {
      if (ok) {
        go('done', true);
      }
    });

  const handleAvatar = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const base64 = await fileToBase64(file);
      setAvatarBase64(base64);
      setAvatarName(file.name);
      setError(null);
    } catch (err) {
      setError(err);
    }
  };

  const stepIndex = STEPS.findIndex((s) => s.key === step);

  return (
    <div className="page">
      <h1>Criar bot Discord</h1>
      <p className="muted">
        Assistente passo a passo para colocar seu bot de vendas no ar — do
        Developer Portal do Discord até o FoxCash.
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

      {step === 'intro' && <IntroStep onStart={() => go('devportal')} />}

      {step === 'devportal' && (
        <DevPortalStep onContinue={() => go('credenciais')} />
      )}

      {step === 'credenciais' && (
        <CredentialsStep
          token={token}
          applicationId={applicationId}
          tokenValid={tokenValid}
          appIdValid={appIdValid}
          onTokenChange={setToken}
          onApplicationIdChange={setApplicationId}
          onContinue={() => void handleValidate()}
          busy={busy}
          onBack={() => go('devportal')}
        />
      )}

      {step === 'validar' && (
        <ValidationStep
          identity={identity}
          onBack={() => go('credenciais')}
          onContinue={() => go('setup')}
        />
      )}

      {step === 'setup' && (
        <SetupStep
          avatarBase64={avatarBase64}
          avatarName={avatarName}
          description={description}
          busy={busy}
          onAvatarChange={(e) => void handleAvatar(e)}
          onDescriptionChange={setDescription}
          onApply={() => void handleSetup()}
          onSkip={() => go('register')}
          onBack={() => go('validar')}
        />
      )}

      {step === 'register' && (
        <RegisterStep
          identity={identity}
          credentialsValid={credentialsValid}
          busy={busy}
          registered={registeredBot !== null}
          onRegister={() => void handleRegister()}
          onBack={() => (setupApplied ? go('setup') : go('validar'))}
        />
      )}

      {step === 'done' && (
        <DoneStep
          identity={identity}
          applicationId={
            registeredBot?.applicationId || applicationId.trim() || ''
          }
        />
      )}
    </div>
  );
}

function IntroStep({ onStart }: { onStart: () => void }) {
  return (
    <section className="card">
      <h2 className="card-title">O que é um bot de vendas?</h2>
      <p>
        Um bot do Discord recebe o cliente no seu servidor, apresenta sua loja e
        cobra o Pix — tudo automaticamente. Ele usa o mesmo catálogo e os mesmos
        planos da sua loja no FoxCash.
      </p>
      <h3 className="card-title">Pré-requisitos</h3>
      <ul className="wizard-list">
        <li>Uma loja ativa no FoxCash.</li>
        <li>
          Uma conta no Discord e acesso ao{' '}
          <a
            href="https://discord.com/developers/applications"
            target="_blank"
            rel="noreferrer"
          >
            Developer Portal
          </a>
          .
        </li>
        <li>O token do bot e o Application ID (criamos no próximo passo).</li>
      </ul>
      <div className="wizard-actions">
        <button type="button" className="btn btn-primary" onClick={onStart}>
          Começar
        </button>
      </div>
    </section>
  );
}

function DevPortalStep({ onContinue }: { onContinue: () => void }) {
  return (
    <section className="card">
      <h2 className="card-title">Criar a aplicação no Developer Portal</h2>
      <ol className="wizard-list">
        <li>
          Abra o{' '}
          <a
            href="https://discord.com/developers/applications"
            target="_blank"
            rel="noreferrer"
          >
            Discord Developer Portal
          </a>{' '}
          e clique em <strong>New Application</strong>.
        </li>
        <li>Dê um nome para a aplicação e confirme.</li>
        <li>
          No menu à esquerda, abra <strong>Bot</strong> e clique em{' '}
          <strong>Reset Token</strong> (ou Copy) — copie o token.
        </li>
        <li>
          Na página <strong>General Information</strong>, copie o{' '}
          <strong>Application ID</strong> (número de ~18 dígitos).
        </li>
        <li>
          Se o bot for responder comandos, ative o{' '}
          <strong>MESSAGE CONTENT INTENT</strong> na aba Bot (quando aplicável).
        </li>
      </ol>
      <div className="wizard-actions">
        <button type="button" className="btn btn-primary" onClick={onContinue}>
          Já copiei o token e o ID
        </button>
      </div>
    </section>
  );
}

function CredentialsStep({
  token,
  applicationId,
  tokenValid,
  appIdValid,
  onTokenChange,
  onApplicationIdChange,
  onContinue,
  busy,
  onBack,
}: {
  token: string;
  applicationId: string;
  tokenValid: boolean;
  appIdValid: boolean;
  onTokenChange: (value: string) => void;
  onApplicationIdChange: (value: string) => void;
  onContinue: () => void;
  busy: boolean;
  onBack: () => void;
}) {
  return (
    <section className="card">
      <h2 className="card-title">Credenciais do bot</h2>
      <p className="muted">
        Cole o token e o Application ID copiados no passo anterior. A validação
        de formato é local (sem rede) — a conexão real acontece no próximo passo.
      </p>

      <form
        className="form"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          if (tokenValid && appIdValid) onContinue();
        }}
      >
        <Field
          label="Token do bot (do Developer Portal)"
          required
          type="password"
          autoComplete="off"
          value={token}
          onChange={(e) => onTokenChange(e.target.value)}
          aria-describedby="token-hint"
        />
        <p id="token-hint" className="muted">
          {token.length > 0 && !tokenValid
            ? 'Formato inválido — deve ter 3 partes separadas por ponto (ex.: MTAy...abc.def).'
            : 'Cole o token exatamente como o Developer Portal gerou.'}
        </p>

        <Field
          label="Application ID"
          required
          type="text"
          inputMode="numeric"
          autoComplete="off"
          value={applicationId}
          onChange={(e) => onApplicationIdChange(e.target.value)}
          aria-describedby="appid-hint"
        />
        <p id="appid-hint" className="muted">
          {applicationId.length > 0 && !appIdValid
            ? 'Application ID inválido — deve ser o número (17–20 dígitos) da aba General Information.'
            : 'O número da aba General Information do Developer Portal.'}
        </p>

        <div className="wizard-actions">
          <button type="button" className="btn" onClick={onBack} disabled={busy}>
            Voltar
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={!tokenValid || !appIdValid || busy}
          >
            {busy ? 'Validando…' : 'Validar conexão'}
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
        <p>
          Conexão ainda não validada. Volte e confira o token e o Application ID.
        </p>
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
          DC
        </span>
        <div>
          <strong>{identity.displayName}</strong>
          <span className="muted">@{identity.username || 'sem username'}</span>
        </div>
        <span className="tag tag-success">CONECTADO</span>
      </div>
      <p>
        A conexão com o Discord foi validada. Confira se é o bot correto antes
        de continuar.
      </p>
      <div className="wizard-actions">
        <button type="button" className="btn" onClick={onBack}>
          Trocar credenciais
        </button>
        <button type="button" className="btn btn-primary" onClick={onContinue}>
          Continuar
        </button>
      </div>
    </section>
  );
}

function SetupStep({
  avatarBase64,
  avatarName,
  description,
  busy,
  onAvatarChange,
  onDescriptionChange,
  onApply,
  onSkip,
  onBack,
}: {
  avatarBase64: string | null;
  avatarName: string;
  description: string;
  busy: boolean;
  onAvatarChange: (e: ChangeEvent<HTMLInputElement>) => void;
  onDescriptionChange: (value: string) => void;
  onApply: () => void;
  onSkip: () => void;
  onBack: () => void;
}) {
  return (
    <section className="card">
      <h2 className="card-title">Configuração inicial (opcional)</h2>
      <p className="muted">
        Ajuste o avatar e a descrição do bot antes de registrar. Se preferir,
        pule esta etapa — o bot funciona sem ela.
      </p>

      <div className="form">
        <span className="field-label">Avatar do bot</span>
        <input
          className="input"
          type="file"
          accept="image/jpeg,image/png"
          onChange={onAvatarChange}
          aria-label="Avatar do bot"
        />
        {avatarBase64 ? (
          <p className="muted">
            Avatar selecionado: <strong>{avatarName}</strong>
          </p>
        ) : (
          <p className="muted">Nenhum avatar selecionado (opcional).</p>
        )}

        <label className="field">
          <span className="field-label">Descrição ("Sobre mim")</span>
          <textarea
            className="input"
            rows={3}
            maxLength={190}
            value={description}
            onChange={(e) => onDescriptionChange(e.target.value)}
            placeholder="O que o bot faz? (ex.: venda de planos e produtos)"
          />
        </label>

        <p className="muted">
          Os comandos slash <code>/comprar</code> e <code>/menu</code> são
          registrados automaticamente no registro final.
        </p>
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
  credentialsValid,
  busy,
  registered,
  onRegister,
  onBack,
}: {
  identity: BotIdentity | null;
  credentialsValid: boolean;
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
          para a conclusão para convidar o bot ao seu servidor.
        </p>
      ) : (
        <>
          <p>
            O FoxCash vai registrar a conexão com o Discord, ativar o bot{' '}
            <strong>{identity?.displayName || 'do Discord'}</strong> e configurar
            os comandos slash.
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
            disabled={!credentialsValid || busy}
            onClick={onRegister}
          >
            {busy ? 'Registrando…' : 'Registrar bot'}
          </button>
        )}
      </div>
    </section>
  );
}

function DoneStep({
  identity,
  applicationId,
}: {
  identity: BotIdentity | null;
  applicationId: string;
}) {
  const inviteUrl = applicationId
    ? `https://discord.com/api/oauth2/authorize?client_id=${applicationId}&scope=bot&permissions=${INVITE_PERMISSIONS}`
    : null;
  return (
    <section className="card">
      <h2 className="card-title">Bot no ar!</h2>
      <div className="bot-identity-preview">
        <span className="bot-avatar" aria-hidden="true">
          DC
        </span>
        <div>
          <strong>{identity?.displayName || 'Bot do Discord'}</strong>
          <span className="muted">@{identity?.username || 'sem username'}</span>
        </div>
        <span className="tag tag-success">ONLINE</span>
      </div>
      <p>
        Seu bot está registrado. Agora é só convidá-lo para o seu servidor do
        Discord — o link abaixo já vem com as permissões mínimas para vender.
      </p>
      {inviteUrl && (
        <div className="wizard-actions">
          <a
            className="btn btn-primary"
            href={inviteUrl}
            target="_blank"
            rel="noreferrer"
          >
            Convidar bot para o servidor
          </a>
        </div>
      )}
      <div className="wizard-actions">
        <Link className="btn" to="/bots">
          Ver Meus Bots
        </Link>
      </div>
    </section>
  );
}
