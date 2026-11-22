---
name: Criador de Bot Telegram (wizard)
status: audited
---

# Spec 77 — Task: Criador de Bot Telegram (wizard)

## F0 — Wizard (critérios 1–4)

- [x] T0.1: `portal/src/pages/bots/TelegramBotCreatorWizard.tsx` + rota
      `/bots/creator/telegram` (D2/D3) — máquina de etapas
      `intro → botfather → validar → setup → register → done`.
- [x] T0.2: `BotFatherStep` — instruções + link `https://t.me/BotFather` +
      campo token com regex `\d+:[A-Za-z0-9_-]{30,}` (D2).
- [x] T0.3: Validação em tempo real — reusa a integração da Spec 74 (ou
      `POST /api/v1/portal/bots/validate` como aditivo mínimo documentado);
      mostra nome/@ do bot antes de continuar; erro claro (D4).
- [x] T0.4: `OptionalSetupStep` — foto (base64) + descrição/bio via Spec 74
      (`setMyPhoto`/`setMyDescription`/`setMyShortDescription`); banners de
      sucesso/erro (D4).
- [x] T0.5: Entrada no portal: link "Criar bot Telegram" na `BotsPage`
      (Spec 76) + CTA no empty state.

## F1 — Registro + conclusão (critérios 5–6)

- [x] T1.1: `RegisterStep` — `POST /api/v1/portal/bots` (Spec 74) chamado
      **uma única vez**; loading/erro (`ERROR` visível)/sucesso (D5).
- [x] T1.2: `DoneStep` — resumo (nome, @, ONLINE) + link "Meus Bots"
      (Spec 76); botão voltar **não** recria registro (idempotência no
      cliente: token já registrado → navega para a tela).

## F2 — Fechamento (critérios 7–8)

- [x] T2.1: E2E do wizard com Telegram mockado (getMe → nome/@; setWebhook;
      setMyDescription) — valida `POST /portal/bots` 1× e voltar não duplica.
- [x] T2.2: `npm run build` (tsc) limpo + suíte E2E existente sem regressão.
- [x] T2.3: `/spec-verify 77` e depois `/spec-audit 77` (critérios 1–8).
