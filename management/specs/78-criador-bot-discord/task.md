---
name: Criador de Bot Discord (wizard)
status: audited
---

# Spec 78 — Task: Criador de Bot Discord (wizard)

## F0 — Wizard (critérios 1–4)

- [x] T0.1: `portal/src/pages/bots/DiscordBotCreatorWizard.tsx` + rota
      `/bots/creator/discord` (D2/D3) — máquina de etapas
      `intro → devportal → credenciais → validar → setup → register → done`.
- [x] T0.2: `DevPortalStep` — instruções + link
      `https://discord.com/developers/applications` + checklist (token, ID,
      intents) com confirmação visual (D2).
- [x] T0.3: `CredentialsStep` — campos token + Application ID, validação de
      formato (sem rede).
- [x] T0.4: Validação em tempo real — reusa o gerenciador de conexão da
      Spec 74 (ou `POST /api/v1/portal/bots/validate` como aditivo mínimo
      documentado); mostra username do bot; erro claro (D4).
- [x] T0.5: `OptionalSetupStep` — avatar (base64) + descrição
      (`PATCH /users/@me`) e comandos slash
      (`PUT /applications/{id}/commands`) via Spec 74; banners (D4).
- [x] T0.6: Entrada no portal: link "Criar bot Discord" na `BotsPage`
      (Spec 76) + CTA no empty state.

## F1 — Registro + conclusão (critérios 5–6)

- [x] T1.1: `RegisterStep` — `POST /api/v1/portal/bots` (Spec 74) chamado
      **uma única vez**; loading/erro (`ERROR` visível)/sucesso (D5).
- [x] T1.2: `DoneStep` — resumo (username, ONLINE) + link "Meus Bots"
      (Spec 76) + **link de convite OAuth2**
      (`scope=bot&permissions=...` com `application_id` do registro);
      voltar não recria registro.

## F2 — Fechamento (critérios 7–8)

- [x] T2.1: E2E do wizard com Discord mockado (conexão → username;
      `PATCH /users/@me`; `PUT /applications/{id}/commands`) — valida
      `POST /portal/bots` 1× e voltar não duplica.
- [x] T2.2: `npm run build` (tsc) limpo + suíte E2E existente sem regressão
      (2026-08-13: `mvn test` 1504/0/0 + E2E completo 139/139, incluindo os
      2 novos do wizard Discord — `bots-creator-discord.spec.js`).
- [x] T2.3: `/spec-audit 78` (2026-08-13) — **8/8 critérios OK, 0 achados
      abertos**; `mvn test` 1504/0/0 + E2E completo 139/139 (2 novos do wizard
      Discord) + build tsc/vite limpo + console íntegro.
