---
name: Criador de Bot Discord (wizard)
status: audited
---

# Spec 78 — Design: Criador de Bot Discord (wizard)

## D1 — Conceito

Wizard **client-side no portal** sobre as APIs da Spec 74 — nenhuma rota de
backend nova no fluxo principal. Cada etapa é um estado do componente
`DiscordBotCreatorWizard`; as ações de rede são as chamadas da Spec 74:

- Validação da conexão: gerenciador de conexão da Spec 74 (D6) — conecta com
  reconexão/backoff e testa presença (ou endpoint de validação aditivo
  mínimo, documentado no task).
- Configuração opcional: `PATCH /users/@me` (avatar/descrição) e
  `PUT /applications/{id}/commands` (slash commands) via Spec 74.
- Registro final: `POST /api/v1/portal/bots`.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Criador por plataforma | Discord = Spec 78; Telegram = Spec 77 |
| Passo a passo no painel | wizard em etapas com verificação em tempo real |
| Convite ao servidor no final | link OAuth2 (`scope=bot&permissions=...`) na conclusão |
| Nada de backend novo silencioso | gaps viram aditivo mínimo documentado no task |

## D2 — Componentes (portal)

- `DiscordBotCreatorWizard` — máquina de etapas (D3), barra de progresso.
- `DevPortalStep` — instruções + link
  `https://discord.com/developers/applications` + checklist (token, ID,
  intents).
- `CredentialsStep` — campos token + Application ID, validação de formato.
- `ConnectionStep` — validação da conexão (Spec 74): username do bot + badge
  de status; erro claro.
- `OptionalSetupStep` — avatar (input file → base64) + descrição (`PATCH
  /users/@me`) + comandos slash (`PUT /applications/{id}/commands`) — chamadas
  da Spec 74 com banners.
- `RegisterStep` — `POST /api/v1/portal/bots` (Spec 74); loading/sucesso/erro.
- `DoneStep` — resumo + link "Meus Bots" (Spec 76) + **link de convite**
  (OAuth2: `https://discord.com/api/oauth2/authorize?client_id={appId}&scope=bot&permissions={perm}`).

## D3 — Fluxo de etapas

```
intro → devportal → credenciais(token+appId) → validar(conexão)
      → setup(opcional) → register(POST /portal/bots) → done(convite)
```

- Voltar refaz a partir da etapa anterior (credenciais não reenviadas após
  validação).
- Sair no meio: **nada é criado** até o passo `register` (validação/setup são
  stateless no servidor; a Spec 74 só cria no `POST`).

## D4 — Integração com a Spec 74

- **Validação** reusa o gerenciador de conexão (D6). Se o UX exigir validar
  **antes** de registrar (username sem criar registro), adicionar `POST
  /api/v1/portal/bots/validate` (aditivo mínimo — testa conexão e retorna
  username; documentar no task).
- **Configuração**: endpoints da Spec 74 para avatar/descrição
  (`PATCH /users/@me`) e slash commands (`PUT /applications/{id}/commands`) —
  expor como rota no `PortalBotController` se ainda não existir (aditivo
  mínimo).
- **Convite**: gerado no cliente com `application_id` retornado pelo
  registro; permissões default documentadas (nível mínimo para o bot vender:
  envio de mensagens/embeds; permissões por papel são pós-criação — Spec 76).

## D5 — UX (padrão Spec 73)

- Banners `SuccessBanner`/`ErrorBanner`; `Spinner` no loading; `Field` com
  validação de token/ID; foco/aria nos passos; erro de rede com retry manual.
- Checklist do Developer Portal com confirmação visual (checkboxes) antes de
  habilitar "Continuar".

## D6 — Testes

- **E2E**: wizard completo com Discord mockado (conexão → username;
  `PATCH /users/@me`; `PUT /applications/{id}/commands`) — `POST
  /portal/bots` 1×; voltar não duplica.
- Validação de formato (token/Application ID) sem rede.
- `npm run build` (tsc) limpo; suíte E2E existente sem regressão.
