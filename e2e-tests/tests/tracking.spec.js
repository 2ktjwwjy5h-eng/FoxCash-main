// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');

/**
 * Tracking (Specs 81/82) — jornada E2E completa:
 *
 *   BACKEND (Spec 81):
 *     1. Setup: lojista → loja → plano → bot Telegram ACTIVE (mock getMe/
 *        setWebhook) → **perfil de tracking ACTIVE** (Facebook+TikTok+UTMify+
 *        Otimizey) vinculado ao bot.
 *     2. Funil: LEAD (webhook Telegram com /start + UTM → captura + dispatch)
 *        → CHECKOUT (purchase com botId) → PAID (webhook pix) — cada evento
 *        novo publica TRACKING_DISPATCH no outbox; o dispatcher (poll ~10s)
 *        envia aos 4 PROVEDORES MOCKADOS e grava event_logs OK.
 *     3. Asserções: bot_leads.utm_source capturada; event_logs 3× OK
 *        (LEAD/INITIATE_CHECKOUT/PURCHASE); os 4 mocks receberam os POSTs
 *        (CAPI com external_id SHA-256, TikTok com Access-Token, webhooks com
 *        Bearer); sync por serviço ok.
 *
 *   PAINEL (Spec 82, browser):
 *     4. /tracking: cards de resumo, card do perfil (badges de serviço, chips
 *        do bot, linha "3 ok · 0 erros"); criar perfil pela UI (badges acendem
 *        só os configurados).
 *     5. /tracking: editar com token mascarado (vazio não sobrescreve —
 *        verificado via API com o token do login), sincronizar (resultado por
 *        serviço), log paginado, excluir com confirmação.
 *
 * Requisitos: app rodando (default http://localhost:8080) com PostgreSQL do
 * profile dev e as envs dos mocks:
 *   FOXCASH_TELEGRAM_API_BASE_URL=http://localhost:9001
 *   FOXCASH_TRACKING_CAPI_BASE_URL=http://localhost:9003
 *   FOXCASH_TRACKING_TIKTOK_BASE_URL=http://localhost:9004/open_api/v1.3
 *   FOXCASH_TRACKING_UTMIFY_BASE_URL=http://localhost:9005
 *   FOXCASH_TRACKING_OTIMIZEY_BASE_URL=http://localhost:9006
 *   FOXCASH_PORTAL_SERVE_FRONTEND=true
 */
test.describe('Tracking E2E (Specs 81/82) — funil → provedores mockados → painel', () => {
  test.describe.configure({ mode: 'serial' });

  const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);
  const CAPI_MOCK_PORT = Number(process.env.FOXCASH_TRACKING_CAPI_MOCK_PORT || 9003);
  const TIKTOK_MOCK_PORT = Number(process.env.FOXCASH_TRACKING_TIKTOK_MOCK_PORT || 9004);
  const UTMIFY_MOCK_PORT = Number(process.env.FOXCASH_TRACKING_UTMIFY_MOCK_PORT || 9005);
  const OTIMIZEY_MOCK_PORT = Number(process.env.FOXCASH_TRACKING_OTIMIZEY_MOCK_PORT || 9006);
  const PLAN_PRICE = 49.9;

  /** @type {import('http').Server[]} */
  const servers = [];
  const telegramRequests = [];
  const capiRequests = [];
  const tiktokRequests = [];
  const utmifyRequests = [];
  const otimizeyRequests = [];

  function dbClient() {
    return new Client({
      host: process.env.DB_HOST || 'localhost',
      port: Number(process.env.DB_PORT || 5432),
      user: process.env.DB_USERNAME || 'nyxcash',
      password: process.env.DB_PASSWORD || 'nyxcash',
      database: process.env.DB_NAME || 'nyxcash',
    });
  }

  async function dbQuery(text, params) {
    const client = dbClient();
    await client.connect();
    try {
      const { rows } = await client.query(text, params);
      return rows;
    } finally {
      await client.end();
    }
  }

  /** Poll do event_logs até o perfil ter os `expectedTypes` OK (outbox é assíncrono). */
  async function waitForEventLogs(profileId, expectedTypes) {
    const deadline = Date.now() + 60_000;
    let rows = [];
    while (Date.now() < deadline) {
      rows = await dbQuery(
        `SELECT event_type, status FROM event_logs
          WHERE profile_id = $1 ORDER BY created_at`, [profileId]);
      const types = rows.filter((r) => r.status === 'OK').map((r) => r.event_type).sort();
      if (JSON.stringify(types) === JSON.stringify([...expectedTypes].sort())) {
        return rows;
      }
      await new Promise((r) => setTimeout(r, 1_000));
    }
    throw new Error(`event_logs não chegou a [${expectedTypes}] OK em 60s: ${JSON.stringify(rows)}`);
  }

  /** Mock simples: registra o request e responde 200. */
  function startMock(port, sink, responder) {
    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        sink.push({ method: req.method, url: req.url, headers: req.headers, body });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (responder) {
          responder(req, res, body);
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    servers.push(server);
    return new Promise((resolve) => server.listen(port, resolve));
  }

  /** @type {{portalHeaders: Record<string,string>, apiKey: string, botId: string, planId: string, webhookSecret: string, webhookToken: string, profileId: string, transactionId: string, externalReference: string, email: string, password: string}} */
  const state = {
    portalHeaders: {}, apiKey: '', botId: '', planId: '', webhookSecret: '', webhookToken: '',
    profileId: '', transactionId: '', externalReference: '', email: '', password: '',
  };

  test.beforeAll(async () => {
    // Telegram (getMe/setWebhook — registro do bot) + 4 provedores de tracking.
    await startMock(TELEGRAM_MOCK_PORT, telegramRequests, (req, res) => {
      if (req.url.endsWith('/getMe')) {
        res.end(JSON.stringify({ ok: true, result: { id: 456, is_bot: true, first_name: 'Bot Tracking', username: 'bottracking' } }));
      } else {
        res.end(JSON.stringify({ ok: true, result: true }));
      }
    });
    // CAPI: POST /{version}/{pixel}/events (dispatch) e GET /{version}/{pixel} (sync).
    await startMock(CAPI_MOCK_PORT, capiRequests, (req, res) => {
      if (req.method === 'GET') {
        res.end(JSON.stringify({ id: '123', name: 'Pixel Mock' }));
      } else {
        res.end(JSON.stringify({ data: [{ event_name: 'test' }] }));
      }
    });
    // TikTok: POST /open_api/v1.3/pixel/track/ (dispatch e sync test_event_code).
    await startMock(TIKTOK_MOCK_PORT, tiktokRequests, (req, res) => {
      res.end(JSON.stringify({ code: 0, message: 'OK' }));
    });
    // UTMify/Otimizey: POST /event (dispatch) e POST /ping (sync).
    await startMock(UTMIFY_MOCK_PORT, utmifyRequests);
    await startMock(OTIMIZEY_MOCK_PORT, otimizeyRequests);
  });

  test.afterAll(async () => {
    await Promise.all(servers.map((s) => new Promise((r) => s.close(r))));
  });

  test('1. Setup: lojista + loja + plano + bot + perfil de tracking ACTIVE', async ({ request }) => {
    const email = `tracking.e2e.${Date.now()}@example.com`;
    const password = 'Senha-Tracking-123';
    state.email = email;
    state.password = password;

    const register = await request.post('/api/v1/merchants', { data: { name: 'Loja Tracking E2E', email, password } });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    state.apiKey = merchant.apiKey;

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password } });
    expect(login.status()).toBe(200);
    state.portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    const store = await request.post('/api/v1/portal/store', {
      headers: state.portalHeaders,
      data: { name: 'Loja Tracking', description: 'E2E', color: '#E74C3C', emoji: '📊', title: 'Compre!', footer: 'Obrigado!' },
    });
    expect(store.status()).toBe(201);

    const plan = await request.post('/api/v1/portal/plans', {
      headers: state.portalHeaders,
      data: { name: 'VIP Tracking', price: PLAN_PRICE, description: 'Plano', featured: true },
    });
    expect(plan.status()).toBe(201);
    state.planId = (await plan.json()).id;
    await request.post(`/api/v1/portal/plans/${state.planId}/activate`, { headers: state.portalHeaders });

    const created = await request.post('/api/v1/portal/bots', {
      headers: state.portalHeaders,
      data: { platform: 'telegram', token: '123456:ABC-Tracking-token', displayName: 'Bot Tracking' },
    });
    expect(created.status()).toBe(201);
    const bot = await created.json();
    expect(bot.status).toBe('ACTIVE');
    state.botId = bot.id;

    const setWebhook = telegramRequests.find((r) => r.url.endsWith('/setWebhook'));
    expect(setWebhook, 'setWebhook deve ter sido chamado').toBeTruthy();
    const urlMatch = setWebhook.body.match(/https?:\/\/[^/]+\/webhooks\/telegram\/([^"\/]+)\/([^"]+)/);
    expect(urlMatch, 'URL do webhook com id/secret').toBeTruthy();
    expect(urlMatch[1]).toBe(state.botId);
    state.webhookSecret = urlMatch[2];
    // Auditoria 2026-08-18: o header usa o secret_token INDEPENDENTE (corpo do
    // setWebhook), não o segredo da URL.
    state.webhookToken = JSON.parse(setWebhook.body).secret_token;

    // Perfil de tracking com os 4 provedores + vínculo do bot.
    const profile = await request.post('/api/v1/portal/tracking', {
      headers: state.portalHeaders,
      data: {
        name: 'Pixel Modelo E2E',
        facebookPixelId: '123456789012345',
        facebookAccessToken: 'EAA-token-fb',
        tiktokPixelId: 'TK123',
        tiktokAccessToken: 'tiktok-token',
        utmifyToken: 'utmify-token',
        otimizeyToken: 'otimizey-token',
        botIds: [state.botId],
      },
    });
    expect(profile.status()).toBe(201);
    const p = await profile.json();
    expect(p.services.sort()).toEqual(['facebook', 'otimizey', 'tiktok', 'utmify']);
    expect(p.botIds).toContain(state.botId);
    expect(p.facebookTokenMask).toMatch(/^\*+/); // mascarado
    expect(p.facebookTokenMask).not.toContain('EAA'); // nunca em claro
    state.profileId = p.id;
  });

  test('2. Funil: lead (UTM) → checkout → paid → event_logs OK nos 4 provedores', async ({ request }) => {
    test.setTimeout(75_000); // o outbox drena em ciclos de ~10s (poll do dispatcher)
    // 2.1 LEAD via /start com UTM → lead criado com utm_source + dispatch.
    const update = {
      update_id: 300,
      message: { message_id: 1, from: { id: 888, first_name: 'Comprador' }, chat: { id: 888, type: 'private' }, text: '/start utm_source=fb&utm_campaign=promo' },
    };
    const sent = await request.post(`/webhooks/telegram/${state.botId}/${state.webhookSecret}`, {
      headers: { 'X-Telegram-Bot-Api-Secret-Token': state.webhookToken },
      data: update,
    });
    expect(sent.status()).toBe(204);

    const leads = await dbQuery('SELECT utm_source, utm_campaign FROM bot_leads WHERE bot_id = $1', [state.botId]);
    expect(leads).toHaveLength(1);
    expect(leads[0].utm_source).toBe('fb'); // UTM capturada (Spec 81/D4)
    expect(leads[0].utm_campaign).toBe('promo');

    // 2.2 CHECKOUT — purchase com botId.
    const idempotencyKey = `tracking-purchase-${Date.now()}`;
    const purchase = await request.post(`/api/v1/foxcash/plans/${state.planId}/purchase`, {
      headers: { 'X-Api-Key': state.apiKey },
      data: {
        platform: 'TELEGRAM', platformUserId: '888', buyerId: 'buyer-tracking-1',
        expirationSeconds: 3600, idempotencyKey, termsAccepted: true, botId: state.botId,
      },
    });
    expect(purchase.status()).toBe(201);
    const cob = await purchase.json();
    state.transactionId = cob.transactionId;
    state.externalReference = cob.externalReference;

    // 2.3 PAID — webhook pix (valor exato do plano).
    const paid = await request.post('/webhooks/pix', {
      data: { eventId: 'evt-tracking-1', externalReference: state.externalReference, status: 'PAID', settledAt: new Date().toISOString(), amount: PLAN_PRICE },
    });
    expect(paid.status()).toBe(204);

    // 2.4 O dispatcher (poll ~10s) envia aos provedores e grava event_logs.
    const logs = await waitForEventLogs(state.profileId, ['LEAD', 'INITIATE_CHECKOUT', 'PURCHASE']);
    expect(logs.map((l) => l.event_type).sort()).toEqual(['INITIATE_CHECKOUT', 'LEAD', 'PURCHASE']);

    // 2.5 Os 4 mocks receberam os disparos com o payload correto.
    const capiPosts = capiRequests.filter((r) => r.url.includes('/events') && r.method === 'POST');
    expect(capiPosts).toHaveLength(3);
    const capiEvent = JSON.parse(capiPosts[0].body);
    expect(capiEvent.data[0].event_name).toBe('LEAD');
    expect(capiEvent.data[0].user_data.external_id).toMatch(/^[0-9a-f]{64}$/); // SHA-256 do ID opaco
    expect(capiPosts[0].headers.authorization).toBe('Bearer EAA-token-fb');

    const tiktokPosts = tiktokRequests.filter((r) => r.url.endsWith('/pixel/track/'));
    expect(tiktokPosts).toHaveLength(3);
    const tiktokEvent = JSON.parse(tiktokPosts[0].body);
    expect(tiktokEvent.event).toBe('LEAD');
    expect(tiktokPosts[0].headers['access-token']).toBe('tiktok-token');
    expect(tiktokEvent.context.ad.utm_source).toBe('fb'); // UTMs no contexto

    const utmifyPosts = utmifyRequests.filter((r) => r.url.endsWith('/event'));
    expect(utmifyPosts).toHaveLength(3);
    const utmifyEvent = JSON.parse(utmifyPosts[0].body);
    expect(utmifyEvent.utm_source).toBe('fb');
    expect(utmifyPosts[0].headers.authorization).toBe('Bearer utmify-token');

    expect(otimizeyRequests.filter((r) => r.url.endsWith('/event'))).toHaveLength(3);
  });

  test('3. Resumo via API + sync por serviço (mocks ok)', async ({ request }) => {
    const detail = await request.get(`/api/v1/portal/tracking/${state.profileId}`, { headers: state.portalHeaders });
    expect(detail.status()).toBe(200);
    const d = await detail.json();
    expect(d.okEvents).toBe(3);
    expect(d.errorEvents).toBe(0);

    const sync = await request.post(`/api/v1/portal/tracking/${state.profileId}/sync`, { headers: state.portalHeaders });
    expect(sync.status()).toBe(200);
    const results = await sync.json();
    expect(results).toHaveLength(4);
    for (const r of results) expect(r.ok).toBe(true);
  });

  /** Login no browser + navega até /tracking (cada teste tem contexto limpo). */
  async function gotoTracking(page) {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(state.email);
    await page.getByLabel('Senha').fill(state.password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');
    await page.getByRole('link', { name: 'Trackeamento' }).click();
    await page.waitForURL('**/tracking');
  }

  test('4. Painel: resumo + card (badges/chips/log) + criar perfil pela UI', async ({ page }) => {
    await gotoTracking(page);

    // Cards de resumo (perfis ativos = 1, fluxos = 1, eventos ok = 3).
    await expect(page.locator('.summary-value').nth(0)).toHaveText('1');
    await expect(page.locator('.summary-value').nth(1)).toHaveText('1');
    await expect(page.locator('.summary-value').nth(2)).toHaveText('3');

    // Card do perfil: nome, badges de serviço, chip do bot, linha "3 ok · 0 erros".
    const originalCard = page.locator('.trk-card', { hasText: 'Pixel Modelo E2E' });
    await expect(originalCard).toBeVisible();
    for (const badge of ['Pixel', 'TikTok', 'UTMify', 'Otimizey']) {
      // Cada badge é um span próprio — o locator precisa filtrar por texto
      // (um locator '.tag-primary' com múltiplos matches viola strict mode).
      await expect(originalCard.locator('.tag-primary', { hasText: badge })).toHaveCount(1);
    }
    await expect(originalCard.locator('.chip')).toContainText('Bot Tracking');
    await expect(originalCard.locator('.trk-logline')).toContainText('3 ok · 0 erros');

    // Criar perfil pela UI (modal) — badges acendem só o configurado.
    // exact: o ProfileSelector (Spec 123) tem "+ criar perfil" na sidebar —
    // o nome sem + faria strict mode violation.
    await page.getByRole('button', { name: 'Criar perfil', exact: true }).click();
    await page.getByLabel('Nome (obrigatório)').fill('Pixel TikTok UI');
    await page.getByLabel('Pixel ID').fill('999');
    await page.getByLabel('Token de acesso').first().fill('token-ui');
    // Spec 124 (filtro rígido): com um perfil ativo, o portal só lista perfis de
    // tracking cujos bots pertencem ao bot-set do perfil — vincula o bot no
    // modal para o perfil novo aparecer na lista escopada.
    await page.getByRole('button', { name: 'Bot Tracking', exact: true }).click();
    await page.getByRole('button', { name: 'Salvar' }).click();
    await expect(page.locator('.alert-success')).toContainText('Perfil criado');
    const uiCard = page.locator('.trk-card', { hasText: 'Pixel TikTok UI' });
    await expect(uiCard.locator('.tag-primary')).toContainText('Pixel');
    await expect(uiCard.locator('.tag-primary')).not.toContainText('TikTok'); // só o configurado
  });

  test('5. Painel: editar (token mascarado) + sincronizar + log + excluir', async ({ page, request }) => {
    await gotoTracking(page);
    const uiCard = page.locator('.trk-card', { hasText: 'Pixel TikTok UI' });

    // Editar — token vazio com placeholder mascarado → vazio NÃO sobrescreve.
    await uiCard.getByRole('button', { name: 'Editar' }).click();
    await expect(page.getByLabel('Token de acesso').first()).toHaveValue('');
    await expect(page.getByLabel('Token de acesso').first()).toHaveAttribute('placeholder', /manter atual/);
    await page.getByLabel('Nome (obrigatório)').fill('Pixel TikTok Editado');
    await page.getByRole('button', { name: 'Salvar' }).click();
    await expect(page.locator('.alert-success')).toContainText('Perfil atualizado');

    // Verificação via API (login próprio — o browser não expõe o token fora do sessionStorage):
    // o token original foi PRESERVADO (mask != vazio) e NÃO é o valor digitado.
    const login = await request.post('/api/v1/merchant-auth/login', { data: { email: state.email, password: state.password } });
    expect(login.status()).toBe(200);
    const headers = { Authorization: `Bearer ${(await login.json()).token}` };
    const list = await (await request.get('/api/v1/portal/tracking', { headers })).json();
    const edited = list.find((p) => p.name === 'Pixel TikTok Editado');
    expect(edited, 'perfil editado presente').toBeTruthy();
    expect(edited.facebookTokenMask).toMatch(/^\*+/);
    expect(edited.facebookTokenMask).not.toContain('token-ui'); // vazio não sobrescreveu

    // Sincronizar → resultado por serviço com ok.
    await page.locator('.trk-card', { hasText: 'Pixel TikTok Editado' }).getByRole('button', { name: 'Sincronizar' }).click();
    await expect(page.locator('.modal')).toContainText('Sincronizar');
    await expect(page.locator('.modal').locator('.tag-success')).toContainText('Pixel');
    await page.getByRole('button', { name: 'Fechar' }).click();

    // Log do perfil original (3 eventos) — badges OK.
    await page.locator('.trk-card', { hasText: 'Pixel Modelo E2E' }).getByRole('button', { name: 'Log' }).click();
    await expect(page.locator('.modal')).toContainText('Log de eventos');
    await expect(page.locator('.modal').locator('.tag-success')).toHaveCount(3);
    await expect(page.locator('.modal')).toContainText('LEAD');
    await page.getByRole('button', { name: 'Fechar' }).click();

    // Excluir com confirmação (danger).
    await page.locator('.trk-card', { hasText: 'Pixel TikTok Editado' }).getByRole('button', { name: 'Excluir' }).click();
    await expect(page.locator('.modal')).toContainText('Excluir perfil');
    // Botão de confirmar do modal — os cards também têm "Excluir" (strict mode).
    await page.getByLabel('Excluir perfil').getByRole('button', { name: 'Excluir' }).click();
    await expect(page.locator('.alert-success')).toContainText('Perfil excluído');
    await expect(page.locator('.trk-card', { hasText: 'Pixel TikTok Editado' })).toHaveCount(0);
  });

  test('6. Ações: duplicar (confirmação) + ativar/desativar (toggle)', async ({ page }) => {
    await gotoTracking(page);

    // Duplicar o perfil original com confirmação (primary) — a cópia herda os
    // serviços configurados e o vínculo do bot.
    // hasText 'Pixel Modelo E2E' casaria TAMBÉM com a cópia "(copia)" — o card
    // original é resolvido pelo nome EXATO (o <strong> do card).
    const originalCard = page.locator('.trk-card').filter({ has: page.getByText('Pixel Modelo E2E', { exact: true }) });
    await expect(originalCard).toBeVisible();
    await originalCard.getByRole('button', { name: 'Duplicar' }).click();
    await expect(page.locator('.modal')).toContainText('Duplicar perfil');
    await page.getByLabel('Duplicar perfil').getByRole('button', { name: 'Duplicar' }).click();
    await expect(page.locator('.alert-success')).toContainText('Perfil duplicado');

    const copyCard = page.locator('.trk-card', { hasText: 'Pixel Modelo E2E (copia)' });
    await expect(copyCard).toBeVisible();
    for (const badge of ['Pixel', 'TikTok', 'UTMify', 'Otimizey']) {
      await expect(copyCard.locator('.tag-primary', { hasText: badge })).toHaveCount(1);
    }
    await expect(copyCard.locator('.chip')).toContainText('Bot Tracking');

    // Desativar o original → badge Inativo.
    await originalCard.getByRole('button', { name: 'Desativar' }).click();
    await expect(page.locator('.alert-success')).toContainText('Perfil desativado');
    // Badge de STATUS (`.trk-card-title .tag`) — o card também tem 4 badges de
    // serviço (`.tag-primary`), que não devem entrar na asserção (strict mode).
    await expect(originalCard.locator('.trk-card-title .tag')).toContainText('Inativo');

    // Reativar → badge Ativo de volta.
    await originalCard.getByRole('button', { name: 'Ativar' }).click();
    await expect(page.locator('.alert-success')).toContainText('Perfil ativado');
    await expect(originalCard.locator('.trk-card-title .tag')).toContainText('Ativo');

    // Limpeza: excluir a cópia (com confirmação) para não vazar para o resumo.
    await copyCard.getByRole('button', { name: 'Excluir' }).click();
    await expect(page.locator('.modal')).toContainText('Excluir perfil');
    await page.getByLabel('Excluir perfil').getByRole('button', { name: 'Excluir' }).click();
    await expect(page.locator('.alert-success')).toContainText('Perfil excluído');
    await expect(page.locator('.trk-card', { hasText: 'Pixel Modelo E2E (copia)' })).toHaveCount(0);
  });
});
