// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');

/**
 * Funil por bot (Spec 75) — jornada E2E completa com o Telegram MOCKADO (a app
 * deve rodar com FOXCASH_TELEGRAM_API_BASE_URL=http://localhost:<mockPort>):
 *
 *   1. Setup: lojista → loja → plano ativo → bot Telegram ACTIVE (mock getMe/
 *      setWebhook; segredo extraído da URL registrada — nunca no banco/resposta).
 *   2. LEAD:    POST /webhooks/telegram/{id}/{secret} (update com from.id) →
 *               bot_leads (1 linha); replay do mesmo update → continua 1 (UNIQUE
 *               bot_id + platform_user_id — Spec 75/T2).
 *   3. CHECKOUT: POST /api/v1/foxcash/plans/{id}/purchase com botId →
 *               bot_funnel_events CHECKOUT_STARTED; replay da MESMA
 *               idempotencyKey → MESMA transação e continua 1 evento.
 *   4. PAID:    POST /webhooks/pix PAID → transação APPROVED +
 *               bot_funnel_events PAID; replay do webhook → continua 1 evento.
 *   5. MÉTRICAS: GET /api/v1/portal/bots/{id}/metrics → leads 1, vendas 1,
 *               receita = preço do plano, conversao 1.0 (fonte oficial: só
 *               transações APPROVED — os eventos do funil nunca somam dinheiro).
 *   6. MASTER:  GET /api/v1/master/bots/metrics → espelho read-only com o nome
 *               do lojista (RBAC Spec 69) e os MESMOS agregados.
 *
 * Requisitos: app rodando (default http://localhost:8080), PostgreSQL acessível
 * (mesmas credenciais do application.yml), perfil dev (PSP/payout mock) e o
 * Telegram mock na porta 9001 (mesma convenção do bots-registro.spec.js).
 */
test.describe('Funil de bot (Spec 75) — lead → checkout → paid → métricas', () => {
  test.describe.configure({ mode: 'serial' });

  const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);
  const PLAN_PRICE = 49.9;

  /** @type {Array<{method: string, url: string, body: string}>} */
  const telegramRequests = [];
  /** @type {import('http').Server | null} */
  let telegramServer = null;

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

  /** @type {{merchantId: string, apiKey: string, portalHeaders: Record<string,string>, botId: string, planId: string, webhookSecret: string, webhookToken: string, transactionId: string, externalReference: string, storeId: string}} */
  const state = {
    merchantId: '', apiKey: '', portalHeaders: {}, botId: '', planId: '',
    webhookSecret: '', webhookToken: '', transactionId: '', externalReference: '', storeId: '',
  };

  test.beforeAll(async () => {
    // Mock de contrato da Bot API do Telegram (mesmo padrão do bots-registro):
    // getMe devolve a identidade; setWebhook/deleteWebhook → ok (a URL com o
    // segredo fica registrada aqui — o único lugar onde o secret em claro existe).
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        telegramRequests.push({ method: req.method, url: req.url, body });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          res.end(JSON.stringify({
            ok: true,
            result: { id: 123, is_bot: true, first_name: 'Bot Funil', username: 'botfunil', can_join_groups: true },
          }));
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    await new Promise((resolve) => telegramServer.listen(TELEGRAM_MOCK_PORT, resolve));
  });

  test.afterAll(async () => {
    await new Promise((resolve) => telegramServer && telegramServer.close(resolve));
  });

  test('1. Setup: lojista + loja + plano ativo + bot Telegram ACTIVE', async ({ request }) => {
    // 1.1 Lojista próprio + login no portal (cenário auto-suficiente).
    const email = `funil.e2e.${Date.now()}@example.com`;
    const password = 'Senha-Funil-123';
    const register = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Funil E2E', email, password },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    state.merchantId = merchant.id;
    state.apiKey = merchant.apiKey;

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password } });
    expect(login.status()).toBe(200);
    state.portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 1.2 Loja (necessária para o purchase — PlanPurchaseService resolve o store).
    const store = await request.post('/api/v1/portal/store', {
      headers: state.portalHeaders,
      data: {
        name: 'Loja Funil', description: 'Funil E2E', color: '#3498DB',
        emoji: '🎯', title: 'Escolha seu plano!', footer: 'Obrigado!',
      },
    });
    expect(store.status()).toBe(201);
    state.storeId = (await store.json()).id;

    // 1.3 Plano ativo (preço fixo — usado na asserção de receita das métricas).
    const plan = await request.post('/api/v1/portal/plans', {
      headers: state.portalHeaders,
      data: { name: 'VIP Funil', price: PLAN_PRICE, description: 'Plano do funil', featured: true },
    });
    expect(plan.status()).toBe(201);
    state.planId = (await plan.json()).id;
    const activate = await request.post(`/api/v1/portal/plans/${state.planId}/activate`, {
      headers: state.portalHeaders,
    });
    expect(activate.status()).toBe(204);

    // 1.4 Registro do bot Telegram (token fake — o mock valida) → ACTIVE.
    const created = await request.post('/api/v1/portal/bots', {
      headers: state.portalHeaders,
      data: { platform: 'telegram', token: '123456:ABC-Funil-token', displayName: 'Bot Funil' },
    });
    expect(created.status()).toBe(201);
    const bot = await created.json();
    expect(bot.platform).toBe('TELEGRAM');
    expect(bot.status).toBe('ACTIVE');
    expect(bot.username).toBe('botfunil');
    expect(bot.token).toBeUndefined();
    state.botId = bot.id;

    // 1.5 O mock recebeu getMe + setWebhook com URL contendo id/segredo.
    const setWebhook = telegramRequests.find((r) => r.url.endsWith('/setWebhook'));
    expect(setWebhook, 'setWebhook deve ter sido chamado').toBeTruthy();
    expect(telegramRequests.some((r) => r.url.endsWith('/getMe'))).toBe(true);
    const urlMatch = setWebhook.body.match(/https?:\/\/[^/]+\/webhooks\/telegram\/([^"\/]+)\/([^"]+)/);
    expect(urlMatch, 'URL do webhook com id/secret').toBeTruthy();
    expect(urlMatch[1]).toBe(state.botId);
    state.webhookSecret = urlMatch[2];
    // Auditoria 2026-08-18: o header usa o secret_token INDEPENDENTE (corpo do
    // setWebhook), não o segredo da URL.
    state.webhookToken = JSON.parse(setWebhook.body).secret_token;
  });

  test('2. Lead via webhook do Telegram — registra 1x (replay não duplica)', async ({ request }) => {
    // 2.1 Update com usuário (from.id = 777) → 204; bot_leads ganha a linha.
    const update = {
      update_id: 200,
      message: { message_id: 1, from: { id: 777, first_name: 'Comprador' }, chat: { id: 777, type: 'private' }, text: 'quero comprar' },
    };
    const sent = await request.post(`/webhooks/telegram/${state.botId}/${state.webhookSecret}`, {
      headers: { 'X-Telegram-Bot-Api-Secret-Token': state.webhookToken },
      data: update,
    });
    expect(sent.status()).toBe(204);

    let leads = await dbQuery(
      'SELECT platform_user_id FROM bot_leads WHERE bot_id = $1', [state.botId]);
    expect(leads).toHaveLength(1);
    expect(leads[0].platform_user_id).toBe('777');

    // 2.2 Replay do MESMO update (update duplicado do Telegram) → no-op.
    const replay = await request.post(`/webhooks/telegram/${state.botId}/${state.webhookSecret}`, {
      headers: { 'X-Telegram-Bot-Api-Secret-Token': state.webhookToken },
      data: update,
    });
    expect(replay.status()).toBe(204);

    leads = await dbQuery('SELECT platform_user_id FROM bot_leads WHERE bot_id = $1', [state.botId]);
    expect(leads).toHaveLength(1); // UNIQUE (bot_id, platform_user_id) — nunca duplica
  });

  test('3. Checkout — purchase com botId grava CHECKOUT_STARTED (replay da chave não duplica)', async ({ request }) => {
    // 3.1 Compra via API key com o MESMO usuário do lead (funil por bot+usuário).
    const idempotencyKey = `funil-purchase-${Date.now()}`;
    const purchase = await request.post(`/api/v1/foxcash/plans/${state.planId}/purchase`, {
      headers: { 'X-Api-Key': state.apiKey },
      data: {
        platform: 'TELEGRAM',
        platformUserId: '777',
        buyerId: 'buyer-funil-1',
        expirationSeconds: 3600,
        idempotencyKey,
        termsAccepted: true,
        botId: state.botId,
      },
    });
    expect(purchase.status()).toBe(201);
    const cob = await purchase.json();
    expect(cob.status).toBe('PENDING');
    expect(cob.planId).toBe(state.planId);
    state.transactionId = cob.transactionId;
    state.externalReference = cob.externalReference;

    // 3.2 Evento CHECKOUT_STARTED gravado com a transação (funil append-only).
    let events = await dbQuery(
      `SELECT event, transaction_id FROM bot_funnel_events
        WHERE bot_id = $1 AND platform_user_id = '777' ORDER BY occurred_at`,
      [state.botId]);
    expect(events).toHaveLength(1);
    expect(events[0].event).toBe('CHECKOUT_STARTED');
    expect(events[0].transaction_id).toBe(state.transactionId);

    // 3.3 Replay do purchase com a MESMA idempotencyKey → MESMA transação e o
    // evento NÃO é re-criado (Spec 75/T1.2 — idempotente por construção).
    const replay = await request.post(`/api/v1/foxcash/plans/${state.planId}/purchase`, {
      headers: { 'X-Api-Key': state.apiKey },
      data: {
        platform: 'TELEGRAM',
        platformUserId: '777',
        idempotencyKey,
        botId: state.botId,
      },
    });
    expect(replay.status()).toBe(201);
    expect((await replay.json()).transactionId).toBe(state.transactionId);

    events = await dbQuery(
      `SELECT event FROM bot_funnel_events
        WHERE bot_id = $1 AND platform_user_id = '777'`,
      [state.botId]);
    expect(events).toHaveLength(1);
    expect(events[0].event).toBe('CHECKOUT_STARTED');
  });

  test('4. Paid — webhook PAID aprova e grava PAID (replay do webhook não duplica)', async ({ request }) => {
    // 4.1 Pagamento via webhook do PSP (Spec 23) — valor EXATO do plano.
    const paid = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-funil-1',
        externalReference: state.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: PLAN_PRICE,
      },
    });
    expect(paid.status()).toBe(204);

    // 4.2 Transação APPROVED + evento PAID na MESMA transação do webhook.
    const tx = await (await request.get(`/api/v1/transactions/${state.transactionId}`, {
      headers: { 'X-Api-Key': state.apiKey },
    })).json();
    expect(tx.status).toBe('APPROVED');
    expect(tx.paidAt).toBeTruthy();

    let events = await dbQuery(
      `SELECT event, transaction_id FROM bot_funnel_events
        WHERE bot_id = $1 AND transaction_id = $2 ORDER BY occurred_at`,
      [state.botId, state.transactionId]);
    expect(events).toHaveLength(2); // CHECKOUT_STARTED + PAID
    expect(events.map((e) => e.event)).toEqual(['CHECKOUT_STARTED', 'PAID']);

    // 4.3 Replay do webhook PAID (re-entrega do PSP) → 204 sem efeitos: status
    // já APPROVED → no-op (Spec 18) — NENHUM segundo evento PAID.
    const replay = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-funil-1-replay',
        externalReference: state.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: PLAN_PRICE,
      },
    });
    expect(replay.status()).toBe(204);

    events = await dbQuery(
      `SELECT event FROM bot_funnel_events
        WHERE bot_id = $1 AND transaction_id = $2`,
      [state.botId, state.transactionId]);
    expect(events).toHaveLength(2);
    expect(events.filter((e) => e.event === 'PAID')).toHaveLength(1);
  });

  test('5. Métricas do portal — leads 1, vendas 1, receita = preço, conversao 1.0', async ({ request }) => {
    // Janela generosa (±2 dias) — cobre os eventos recém-criados sem depender
    // do relógio da app (default sem from/to também cobriria os 30d).
    const from = new Date(Date.now() - 2 * 86_400_000).toISOString();
    const to = new Date(Date.now() + 2 * 86_400_000).toISOString();

    const res = await request.get(`/api/v1/portal/bots/${state.botId}/metrics?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, {
      headers: state.portalHeaders,
    });
    expect(res.status()).toBe(200);
    const m = await res.json();

    expect(m.botId).toBe(state.botId);
    expect(m.leads).toBe(1);                    // 1 usuário único (777)
    expect(m.vendas).toBe(1);                   // 1 transação APPROVED
    expect(Number(m.receita)).toBeCloseTo(PLAN_PRICE, 2);
    expect(m.conversao).toBeCloseTo(1.0, 5);    // 1/1
  });

  test('6. Espelho master — métricas com nome do lojista (RBAC Spec 69)', async ({ request }) => {
    // 6.1 Master criado via SQL (padrão auth.spec.js/bots-registro — BCrypt real).
    const nodeCrypto = require('crypto');
    const masterUsername = `funil-master-${Date.now()}`;
    await dbQuery(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), masterUsername,
        '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6',
        'Master Funil E2E', new Date().toISOString()],
    );

    const masterLogin = await request.post('/api/v1/auth/master/login', {
      data: { username: masterUsername, password: 'Senha-E2E-123' },
    });
    expect(masterLogin.status()).toBe(200);
    const masterHeaders = { Authorization: `Bearer ${(await masterLogin.json()).token}` };

    // 6.2 Espelho master lista o bot com o nome do lojista e os MESMOS agregados.
    const from = new Date(Date.now() - 2 * 86_400_000).toISOString();
    const to = new Date(Date.now() + 2 * 86_400_000).toISOString();
    const res = await request.get(`/api/v1/master/bots/metrics?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, {
      headers: masterHeaders,
    });
    expect(res.status()).toBe(200);
    const agg = await res.json();

    const found = agg.bots.find((b) => b.botId === state.botId);
    expect(found, 'bot deve aparecer no espelho master').toBeTruthy();
    expect(found.merchantName).toBe('Loja Funil E2E');
    expect(found.merchantId).toBe(state.merchantId);
    expect(found.leads).toBe(1);
    expect(found.vendas).toBe(1);
    expect(Number(found.receita)).toBeCloseTo(PLAN_PRICE, 2);
    expect(found.conversao).toBeCloseTo(1.0, 5);

    // 6.3 Total agregado espelha o mesmo resultado.
    expect(agg.total.leads).toBe(1);
    expect(agg.total.vendas).toBe(1);
    expect(Number(agg.total.receita)).toBeCloseTo(PLAN_PRICE, 2);
  });
});
