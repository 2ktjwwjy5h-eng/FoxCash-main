// @ts-check
const { test, expect } = require('@playwright/test');
const http = require('http');

/**
 * Broadcast — editor e preview (Spec 80) — E2E BROWSER da SPA do portal:
 *
 * Requer a app com FOXCASH_PORTAL_SERVE_FRONTEND=true e o Telegram MOCKADO
 * (FOXCASH_TELEGRAM_API_BASE_URL=http://localhost:<mockPort> — mesma env do job
 * e2e do CI) para o registro do bot e a resolução de chats (getUpdates).
 *
 * Cobertura (critérios de aceite da Spec 80 — requirements.md):
 *   1. Lista /broadcasts: empty state + CTA "Nova transmissão".
 *   2. Editor: seleção de bot → carrega chats → seleção de destino.
 *   3. Toolbar insere o markup nativo da plataforma (Telegram HTML).
 *   4. Tags dinâmicas inseridas no cursor.
 *   5. Preview em tempo real reage a texto/mídia/botões (bolha Telegram).
 *   6. Modos mudam os campos (Agendada → datetime; Repost → intervalo).
 *   7. Validação bloqueia envio inválido (botão desabilitado).
 *   8. Envio → POST /api/v1/portal/broadcasts → banner + volta à lista.
 *   9. Cancelar (ConfirmDialog danger) remove a transmissão PENDING.
 *  10. Duplicar abre o editor pré-preenchido (idempotência no cliente).
 */
test.describe.serial('Broadcast editor/preview (Spec 80)', () => {
  const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);
  const ts = Date.now();
  const email = `broadcast-editor-${ts}@example.com`;
  const password = 'Senha-Broadcast-123';
  const MERCHANT_NAME = 'Loja Broadcast E2E';
  const CDN_CHAT_ID = -1001234567890;

  /** @type {import('http').Server | null} */
  let telegramServer = null;

  test.beforeAll(async () => {
    // Mock de contrato da Bot API do Telegram (Spec 74/79): getMe (identidade),
    // setWebhook → ok; getUpdates devolve 1 chat (canal/grupo onde o bot é
    // admin — origem do destino dinâmico do broadcast); sendPhoto/sendVideo/
    // sendAudio devolvem file_id (CDN do broadcast — Spec 79 D7).
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          res.end(JSON.stringify({
            ok: true,
            result: { id: 123, is_bot: true, first_name: 'Bot Broadcast', username: 'botbroadcast', can_join_groups: true },
          }));
        } else if (req.url.endsWith('/getUpdates')) {
          // 1 chat não-privado (canal) — o destino do broadcast (D5).
          res.end(JSON.stringify({
            ok: true,
            result: [
              { update_id: 1, message: { message_id: 1, chat: { id: CDN_CHAT_ID, type: 'channel', title: 'Canal de Vendas' } } },
            ],
          }));
        } else if (req.url.endsWith('/sendPhoto') || req.url.endsWith('/sendVideo') || req.url.endsWith('/sendAudio')) {
          res.end(JSON.stringify({
            ok: true,
            result: { photo: [{ file_id: `file-${req.url.split('/').pop()}-1` }], file_id: `file-${req.url.split('/').pop()}-1` },
          }));
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    await new Promise((resolve) => telegramServer.listen(TELEGRAM_MOCK_PORT, resolve));

    // Lojista para o portal (cadastro público — API; o login é no browser).
    const register = await fetch(`${process.env.API_BASE_URL || 'http://localhost:8080'}/api/v1/merchants`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: MERCHANT_NAME, email, password }),
    });
    expect(register.status).toBe(201);

    // Bot Telegram ativo (registro via API com o mock — getMe + setWebhook).
    const login = await fetch(`${process.env.API_BASE_URL || 'http://localhost:8080'}/api/v1/merchant-auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
    expect(login.status).toBe(200);
    const { token } = await login.json();
    const created = await fetch(`${process.env.API_BASE_URL || 'http://localhost:8080'}/api/v1/portal/bots`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ platform: 'telegram', token: '123456:ABC-Broadcast-token', displayName: 'Bot Broadcast' }),
    });
    expect(created.status).toBe(201);
  });

  test.afterAll(async () => {
    await new Promise((resolve) => telegramServer && telegramServer.close(resolve));
  });

  test('1. Lista vazia → editor: bot → chats → markup → preview → envio → lista com o envio', async ({ page }) => {
    // --- Login --------------------------------------------------------------
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    // --- Lista /broadcasts: empty state + CTA -------------------------------
    await page.getByRole('link', { name: 'Postagens' }).click();
    await page.waitForURL('**/broadcasts');
    await expect(page.locator('h1')).toHaveText('Transmissões');
    await expect(page.locator('text=Nenhuma transmissão ainda.')).toBeVisible();

    // --- Editor: seleção de bot carrega os chats (destino dinâmico) ---------
    // (2 links "Nova transmissão" no empty state: toolbar + CTA → escopa ao
    // toolbar para evitar strict mode.)
    await page.locator('.bots-toolbar').getByRole('link', { name: 'Nova transmissão' }).click();
    await page.waitForURL('**/broadcasts/new');
    await expect(page.locator('h1')).toHaveText('Nova transmissão');

    // Sem bot: o botão de envio fica desabilitado (validação do critério 8).
    await expect(page.getByRole('button', { name: 'Enviar agora' })).toBeDisabled();

    await page.getByLabel('Bot').selectOption({ label: 'Bot Broadcast · Telegram' });
    // A plataforma do bot determina o preview (bolha Telegram por default).
    await expect(page.locator('.bc-preview')).toContainText('Prévia');
    await expect(page.locator('.tg-bubble')).toBeVisible();

    // Chats carregados do mock (getUpdates → canal).
    const destino = page.getByLabel('Destino');
    await expect(destino).toBeEnabled();
    await destino.selectOption({ label: 'Canal de Vendas (channel)' });

    // --- Toolbar: markup nativo da plataforma (Telegram HTML) ---------------
    const textarea = page.locator('textarea.bc-content');
    await textarea.fill('Olá');
    // Seleciona "Olá" e aplica negrito via toolbar.
    await textarea.selectText();
    await page.locator('.bc-toolbar button[title="Negrito"]').click();
    // O markup HTML do Telegram é inserido no texto.
    await expect(textarea).toHaveValue('<b>Olá</b>');

    // --- Tags dinâmicas (critério 3) -----------------------------------------
    await textarea.click();
    await page.locator('.bc-tagbar button[title="Primeiro nome do destinatário"]').click();
    await expect(textarea).toHaveValue(/<b>Olá<\/b>\{primeiro_nome\}/);

    // --- Preview em tempo real (critério 7) ----------------------------------
    // Texto formatado (negrito → <strong>) + tag resolvida com exemplo.
    const previewText = page.locator('.tg-bubble-text');
    await expect(previewText).toContainText('Olá');
    await expect(previewText).toContainText('João'); // {primeiro_nome} → exemplo
    await expect(previewText.locator('strong')).toHaveText('Olá');

    // --- Botões inline (critério 5): preview mostra os botões -----------------
    await page.getByRole('button', { name: '+ Linha' }).click();
    const btnLabel = page.getByLabel('Rótulo do botão 1.1');
    await btnLabel.fill('Ver oferta');
    await page.getByLabel('URL do botão 1.1').fill('https://exemplo.com/oferta');
    await expect(page.locator('.tg-inline-btn')).toContainText('Ver oferta');

    // --- Upload de mídia (critério 4): preview do anexo ------------------------
    // O upload usa POST /api/v1/portal/broadcasts/media (Telegram CDN mock →
    // sendPhoto devolve file_id); o anexo aparece no preview e na linha da mídia.
    await page.locator('input[type="file"]').setInputFiles({
      name: 'banner.png',
      mimeType: 'image/png',
      buffer: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
        'base64',
      ),
    });
    await expect(page.locator('text=Imagem anexada')).toBeVisible();
    await expect(page.locator('.bc-preview')).toContainText('Imagem');

    // --- Modos mudam os campos (critério 6) -----------------------------------
    // Agendada → campo datetime-local aparece; Agora → some.
    await page.getByRole('button', { name: 'Agendada' }).click();
    await expect(page.locator('input[type="datetime-local"]')).toBeVisible();
    await page.getByRole('button', { name: 'Agora' }).click();
    await expect(page.locator('input[type="datetime-local"]')).toHaveCount(0);
    // Repost → select de intervalo + campo de horas (mín. 1).
    await page.getByRole('button', { name: 'Repost' }).click();
    await expect(page.getByLabel('Intervalo')).toBeVisible();
    await page.getByRole('button', { name: 'Agora' }).click();

    // --- Envio (critério 8): POST → banner → volta à lista --------------------
    await page.getByRole('button', { name: 'Enviar agora' }).click();
    await page.waitForURL('**/broadcasts');
    await expect(page.locator('[role="status"]')).toContainText('Transmissão criada com sucesso.');
    // A transmissão aparece na lista com badges de modo/status + destino.
    const item = page.locator('.dest-item').first();
    await expect(item).toContainText('Bot Broadcast');
    await expect(item).toContainText('Canal de Vendas');
    await expect(item).toContainText('Agora');
    await expect(item).toContainText('Pendente');
  });

  test('2. Cancelar com confirmação (ConfirmDialog danger) remove a transmissão', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    await page.getByRole('link', { name: 'Postagens' }).click();
    await page.waitForURL('**/broadcasts');

    // Cancela (só PENDING mostra o botão Cancelar — critério 9).
    await page.getByRole('button', { name: 'Cancelar' }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: 'Cancelar transmissão' })).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: 'Cancelar transmissão' }).click();
    await expect(page.locator('[role="status"]')).toContainText('Transmissão cancelada.');
    await expect(page.locator('text=Nenhuma transmissão ainda.')).toBeVisible();
  });

  test('3. Duplicar pré-preenche o editor (idempotência no cliente)', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');

    await page.getByRole('link', { name: 'Postagens' }).click();
    await page.waitForURL('**/broadcasts');

    // Cria uma transmissão nova para duplicar (valida o preenchimento).
    await page.locator('.bots-toolbar').getByRole('link', { name: 'Nova transmissão' }).click();
    await page.waitForURL('**/broadcasts/new');
    await page.getByLabel('Bot').selectOption({ label: 'Bot Broadcast · Telegram' });
    const destino = page.getByLabel('Destino');
    await expect(destino).toBeEnabled();
    await destino.selectOption({ label: 'Canal de Vendas (channel)' });
    await page.locator('textarea.bc-content').fill('Mensagem para duplicar');
    await page.getByRole('button', { name: 'Enviar agora' }).click();
    await page.waitForURL('**/broadcasts');

    // Duplicar abre o editor pré-preenchido (rota /:id/edit).
    await page.getByRole('link', { name: 'Duplicar' }).click();
    await page.waitForURL('**/broadcasts/*/edit');
    await expect(page.locator('h1')).toHaveText('Duplicar transmissão');
    // Conteúdo pré-preenchido + bot selecionado (sem duplicar o envio — o
    // botão é "Enviar agora" e a transmissão SÓ é criada com a ação do usuário).
    await expect(page.locator('textarea.bc-content')).toHaveValue('Mensagem para duplicar');
    await expect(page.getByLabel('Bot')).toHaveValue(/[0-9a-f-]{36}/);
    // A lista ainda tem exatamente 1 transmissão (nada foi duplicado).
    await page.goto('/broadcasts');
    await expect(page.locator('.dest-item')).toHaveCount(1);
  });
});
