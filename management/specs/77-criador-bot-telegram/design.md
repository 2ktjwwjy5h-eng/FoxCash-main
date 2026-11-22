---
name: Criador de Bot Telegram (wizard)
status: audited
---

# Spec 77 — Design: Criador de Bot Telegram (wizard)

## D1 — Conceito

Wizard **client-side no portal** sobre as APIs da Spec 74 — nenhuma rota de
backend nova no fluxo principal. Cada etapa é um estado do componente
`TelegramBotCreatorWizard`; as ações de rede são as chamadas da Spec 74:

- Validação do token: `POST /api/v1/portal/bots` (Spec 74) já integra (getMe +
  setWebhook) — a "validação" do wizard reusa o mesmo caminho (ou um passo de
  pré-validação aditivo mínimo, documentado no task).
- Configuração opcional: endpoints de edição da Spec 74 (`setMyPhoto`,
  `setMyDescription`, `setMyShortDescription`).
- Registro final: o próprio `POST /api/v1/portal/bots`.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Criador por plataforma | Telegram = Spec 77; Discord = Spec 78 |
| Passo a passo no painel | wizard em etapas com verificação em tempo real |
| Configuração inicial opcional | foto/descrição são passos opcionais; só o registro cria o bot |
| Nada de backend novo silencioso | gaps viram aditivo mínimo documentado no task |

## D2 — Componentes (portal)

- `TelegramBotCreatorWizard` — máquina de etapas (D3), barra de progresso.
- `BotFatherStep` — instruções + link `https://t.me/BotFather` + campo token
  (regex `\d+:[A-Za-z0-9_-]{30,}`).
- `TokenValidationStep` — chamada de validação (Spec 74), mostra nome/@ do
  bot, habilita "Continuar".
- `OptionalSetupStep` — foto (input file → base64) + descrição/bio (chamadas
  da Spec 74 com banner de sucesso/erro).
- `RegisterStep` — `POST /api/v1/portal/bots` (Spec 74); loading, sucesso,
  erro (status `ERROR` visível).
- `DoneStep` — resumo + link para `BotsPage` (Spec 76).

## D3 — Fluxo de etapas

```
intro → botfather(token) → validar(token→getMe) → setup(opcional)
      → register(POST /portal/bots) → done
```

- Voltar permite refazer a partir da etapa anterior (token já validado não é
  reenviado).
- Sair no meio: **nada é criado** até o passo `register` — os passos de
  validação/setup são stateless no servidor (a Spec 74 só cria o registro no
  `POST`).

## D4 — Integração com a Spec 74

- **Validação** reusa o caminho de integração da Spec 74 (D5.2: getMe +
  setWebhook + secret_token). Se o UX exigir validar **antes** de registrar
  (mostrar nome/@ sem criar), adicionar `POST /api/v1/portal/bots/validate`
  (aditivo mínimo — valida token via `getMe` e retorna nome/@, sem gravar;
  documentar no task).
- **Configuração** (foto/descrição): endpoints da Spec 74
  (`setMyPhoto`/`setMyDescription`/`setMyShortDescription`) — se não existirem
  como rota, expor como aditivo mínimo no `PortalBotController`.

## D5 — UX (padrão Spec 73)

- Banners `SuccessBanner`/`ErrorBanner`; `Spinner` no loading; `Field` +
  validação de token com mensagem clara; foco/aria nos passos; estado de
  erro de rede com retry manual.

## D6 — Testes

- **E2E**: wizard completo com Telegram mockado (getMe → nome/@; setWebhook;
  setMyDescription) — validar que o `POST /portal/bots` é chamado uma única
  vez; botão voltar não recria registro.
- Validação de formato do token (regex) sem chamada de rede.
- `npm run build` (tsc) limpo; suíte E2E existente sem regressão.
