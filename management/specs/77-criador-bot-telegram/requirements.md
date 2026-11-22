---
name: Criador de Bot Telegram (wizard)
status: audited
references:
  - "management/vision.md#V55"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/76-painel-bots/requirements.md"
---

# Spec 77 — Criador de Bot Telegram (wizard)

## O que entrega

Um **wizard passo a passo no portal** que leva o lojista do zero até o bot do
Telegram vendendo no FoxCash — guiando a criação do bot na própria
plataforma, com verificação em tempo real em cada etapa. O usuário decidiu
(2026-08-12): **um criador de bot para Telegram e outro para Discord** (Specs
77 e 78).

Etapas do wizard:

1. **Introdução** — o que é um bot Telegram e o que ele vende na loja (Spec
   48/52); pré-requisitos (ter loja ativa).
2. **Criar o bot no BotFather** — instruções passo a passo (iniciar conversa
   com @BotFather, `/newbot`, nome + username) com link direto
   (`https://t.me/BotFather`); campo para colar o **token**.
3. **Validação do token** — verificação **em tempo real** via `getMe` (Spec
   74): mostra nome/username do bot e habilita "Continuar".
4. **Configuração inicial** (opcional) — foto de perfil e descrição/bio,
   enviados via API do Telegram (`setMyPhoto`/`setMyDescription`/
   `setMyShortDescription`) — chamadas da Spec 74.
5. **Registro no FoxCash** — `POST /api/v1/portal/bots` (Spec 74): token
   criptografado, `setWebhook` com `secret_token`, status `ACTIVE`.
6. **Conclusão** — resumo (nome, @, status ONLINE) + navegação para a tela
   "Meus Bots" (Spec 76) e para a loja.

Backend: **nenhuma rota nova** além das Specs 74/76 — o wizard é UI + as
chamadas de integração já especificadas. Se faltar algum endpoint de edição
(foto/descrição), é aditivo mínimo documentado no task (nunca silencioso).

## Contexto (por que agora)

O sistema externo analisado (PRD do irmão) pedia "conectar: usuário insere o
BOT_TOKEN; o backend valida via getMe e registra o webhook" — mas como um
campo solto, sem orientação. A Spec 74 já entrega a validação e o registro; o
criador transforma isso num fluxo guiado que **reduz erro de configuração** e
**educa o lojista** — decisão do usuário: um criador por plataforma.

## Critérios de aceite

1. **Wizard acessível do portal** (rota `/bots/creator/telegram` ou modal em
   `BotsPage` — Spec 76): entrada clara ("Criar bot Telegram").
2. **Instruções do BotFather**: passo a passo correto (nome + username
   terminando em `bot`), link para `t.me/BotFather`, campo de token com
   validação de formato (padrão `\d+:[A-Za-z0-9_-]{30,}`).
3. **Validação em tempo real**: ao colar o token, chama o backend (Spec 74 —
   `getMe`) e exibe **nome e @ do bot** antes de continuar; erro → mensagem
   clara (token inválido/expirado).
4. **Configuração opcional**: upload de foto e descrição/bio com chamadas da
   Spec 74 (`setMyPhoto`/`setMyDescription`/`setMyShortDescription`); cada
   passo com sucesso/erro visível (padrão Spec 73).
5. **Registro final**: cria o registro `store_bots` (Spec 74) com webhook
   registrado e `secret_token`; status `ACTIVE` (ou `ERROR` com mensagem
   visível).
6. **Conclusão**: resumo + link para "Meus Bots" (Spec 76); se o lojista
   sair no meio, o estado fica consistente (passos de edição são opcionais;
   só o registro final cria o bot).
7. **Reuso/consistência**: mesmos componentes UX da Spec 73 (banners, modais,
   `Field`); sem duplicar lógica de integração (tudo via Spec 74).
8. **Testes**: E2E do wizard com Telegram mockado (getMe + setWebhook);
   `npm run build` (tsc) limpo; sem regressão na suíte E2E existente.

## Dependências

- **Spec 74** (registro + integração Telegram) — validação `getMe`,
  `setWebhook` com `secret_token`, edição de foto/descrição/comandos.
- **Spec 76** (painel) — ponto de entrada e tela de destino ("Meus Bots").
- **Spec 73** (UX) — componentes e padrões.
- **Spec 48/52** — contexto do que o bot vende (loja).

## Fora de escopo

- **Criador de Bot Discord** → Spec 78.
- **Edição pós-criação avançada** (comandos complexos, menus) → Spec 74/76.
- **Telegram via long-polling** — só webhook (decisão da Spec 74).
