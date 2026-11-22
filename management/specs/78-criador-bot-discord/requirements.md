---
name: Criador de Bot Discord (wizard)
status: audited
references:
  - "management/vision.md#V55"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/76-painel-bots/requirements.md"
---

# Spec 78 — Criador de Bot Discord (wizard)

## O que entrega

Um **wizard passo a passo no portal** que leva o lojista do zero até o bot do
Discord vendendo no FoxCash — guiando a criação do bot no **Discord
Developer Portal**, com verificação em tempo real em cada etapa. Decisão do
usuário (2026-08-12): **um criador de bot para Telegram e outro para
Discord** (Specs 77 e 78).

Etapas do wizard:

1. **Introdução** — o que é um bot Discord e o que ele vende na loja (Spec
   48/52); pré-requisitos (loja ativa, conta no Discord).
2. **Criar a aplicação no Developer Portal** — instruções passo a passo
   (discord.com/developers/applications → New Application → Bot → token +
   Application ID), com link direto e checklist (copiar token, copiar ID,
   ativar `MESSAGE CONTENT INTENT` quando aplicável).
3. **Credenciais** — campos para **token** e **Application ID**; validação
   de formato; habilita "Continuar".
4. **Validação da conexão** — o backend (Spec 74, gerenciador de conexão)
   conecta no gateway com reconexão/backoff e testa presença; mostra o
   username do bot; erro → mensagem clara (token inválido/sem permissão).
5. **Configuração inicial** (opcional) — avatar e descrição, via API do
   Discord (`PATCH /users/@me` com imagem base64) e comandos slash
   (`PUT /applications/{id}/commands`) — chamadas da Spec 74.
6. **Registro no FoxCash** — `POST /api/v1/portal/bots` (Spec 74): token
   criptografado, conexão ativa, status `ACTIVE`.
7. **Conclusão** — resumo (username, status ONLINE) + navegação para "Meus
   Bots" (Spec 76) e para o convite do bot no servidor (link de OAuth2).

Backend: **nenhuma rota nova** além das Specs 74/76 — o wizard é UI + as
chamadas de integração já especificadas. Gaps (ex.: validação pré-registro,
edição de avatar/comandos como rota) são aditivos mínimos documentados no
task (nunca silenciosos).

## Contexto (por que agora)

O sistema externo analisado (PRD do irmão) pedia "conectar: usuário insere o
Token do Bot e ID do Application; o backend inicializa a conexão via
discord.js" — mas com pool em memória sem reconexão (fragilidade que a Spec
74 resolve com reconexão/backoff). O criador transforma a conexão num fluxo
**guiado e verificável**, com o convite do bot ao servidor no final —
decisão do usuário: um criador por plataforma.

## Critérios de aceite

1. **Wizard acessível do portal** (rota `/bots/creator/discord` ou modal em
   `BotsPage` — Spec 76): entrada clara ("Criar bot Discord").
2. **Instruções do Developer Portal**: passo a passo correto (New
   Application → Bot → Reset Token → copy; Application ID na página geral),
   link direto (`https://discord.com/developers/applications`), nota sobre
   intents necessárias.
3. **Validação em tempo real**: token + Application ID validados pelo backend
   (Spec 74 — conexão no gateway): mostra **username do bot** antes de
   continuar; erro → mensagem clara (token inválido/sem permissão).
4. **Configuração opcional**: avatar (base64) e descrição via API do Discord
   (`PATCH /users/@me`); comandos slash (`PUT /applications/{id}/commands`) —
   chamadas da Spec 74, com sucesso/erro visível (padrão Spec 73).
5. **Registro final**: cria o registro `store_bots` (Spec 74) com conexão
   ativa; status `ACTIVE` (ou `ERROR` com mensagem visível).
6. **Conclusão**: resumo + link "Meus Bots" (Spec 76) + link de **convite do
   bot ao servidor** (OAuth2 `scope=bot&permissions=...`) para o lojista
   adicionar o bot; sair no meio não cria registro (só o passo `register`
   grava).
7. **Reuso/consistência**: componentes UX da Spec 73 (banners, modais,
   `Field`); sem duplicar lógica de integração (tudo via Spec 74).
8. **Testes**: E2E do wizard com Discord mockado (conexão + `PATCH /users/@me`
   + slash commands); `npm run build` (tsc) limpo; sem regressão na suíte E2E
   existente.

## Dependências

- **Spec 74** (registro + integração Discord) — gerenciador de conexão com
  reconexão/backoff, edição de avatar/descrição/comandos slash.
- **Spec 76** (painel) — ponto de entrada e tela de destino ("Meus Bots").
- **Spec 73** (UX) — componentes e padrões.
- **Spec 48/52** — contexto do que o bot vende (loja).

## Fora de escopo

- **Criador de Bot Telegram** → Spec 77.
- **Edição pós-criação avançada** (comandos complexos, permissões por
  servidor) → Spec 74/76.
- **Suporte multi-servidor/scale-out do gateway** — decisão stateless da Spec
  52/74 (1 conexão por bot; reconexão/backoff).
