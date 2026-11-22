// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');

/**
 * Registro central de bots de venda (Spec 74) — jornada E2E com o Telegram
 * MOCKADO (a app deve rodar com FOXCASH_TELEGRAM_API_BASE_URL=http://localhost:
 * <mockPort>):
 *
 *   1. Mock HTTP do Telegram: getMe (identidade) + setWebhook (captura a URL
 *      com o segredo — o webhook_secret em claro NUNCA é persistido nem
 *      devolvido; só o mock o vê na URL).
 *   2. Registrar bot no portal → ACTIVE (getMe + setWebhook chamados), resposta
 *      sem token (só tokenMask).
 *   3. Simular update do Telegram (POST /webhooks/telegram/{id}/{secret}) →
 *      evento TELEGRAM_UPDATE no outbox (Spec 35) para o renderizador.
 *   4. Pausar → update descartado (nenhum evento novo); retomar → ACTIVE.
 *   5. Espelho read-only do master (ROLE_MASTER) com o nome do lojista.
 *   6. Remover (soft-delete) → 404 no portal; o registro permanece no banco.
 */
test.describe('Registro central de bots de venda (Spec 74)', () => {
  test.describe.configure({ mode: 'serial' });

  const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);

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

  /** @type {{merchantId: string, portalHeaders: Record<string,string>, masterHeaders: Record<string,string>, botId: string}} */
  const state = { merchantId: '', portalHeaders: {}, masterHeaders: {}, botId: '' };

  test.beforeAll(async () => {
    // Mock de contrato da Bot API do Telegram (T2.5/T5.3): getMe devolve a
    // identidade; qualquer outro método (setWebhook/deleteWebhook) → ok:true.
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        telegramRequests.push({ method: req.method, url: req.url, body });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          res.end(JSON.stringify({
            ok: true,
            result: { id: 123, is_bot: true, first_name: 'Bot E2E', username: 'bote2e', can_join_groups: true },
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

  test('1. Registra bot no portal → ACTIVE (getMe + setWebhook no mock)', async ({ request }) => {
    // 1.1 Lojista próprio + login no portal.
    const email = `botsreg.e2e.${Date.now()}@example.com`;
    const password = 'Senha-BotsReg-123';
    const register = await request.post('/api/v1/merchants', { data: { name: 'Loja Bots E2E', email, password } });
    expect(register.status()).toBe(201);
    state.merchantId = (await register.json()).id;

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password } });
    expect(login.status()).toBe(200);
    state.portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 1.2 Registro do bot Telegram (token fake — o mock valida).
    const created = await request.post('/api/v1/portal/bots', {
      headers: state.portalHeaders,
      data: { platform: 'telegram', token: '123456:ABC-E2E-token', displayName: 'Bot E2E' },
    });
    expect(created.status()).toBe(201);
    const bot = await created.json();
    expect(bot.platform).toBe('TELEGRAM');
    expect(bot.status).toBe('ACTIVE');
    expect(bot.username).toBe('bote2e'); // do getMe
    expect(bot.token).toBeUndefined();   // token nunca devolvido
    expect(bot.tokenMask).toBe('****oken');
    state.botId = bot.id;

    // 1.3 O mock recebeu getMe + setWebhook com URL contendo o id do bot e o
    //     segredo (nunca o id como segredo) + secret_token no corpo.
    const setWebhook = telegramRequests.find((r) => r.url.endsWith('/setWebhook'));
    expect(setWebhook, 'setWebhook deve ter sido chamado').toBeTruthy();
    expect(telegramRequests.some((r) => r.url.endsWith('/getMe'))).toBe(true);
    expect(setWebhook.body).toContain(`/webhooks/telegram/${state.botId}/`);
    expect(setWebhook.body).toContain('"secret_token"');

    // 1.4 GET lista o bot (filtro por platform) — sem token em claro.
    const list = await request.get('/api/v1/portal/bots?platform=telegram', { headers: state.portalHeaders });
    expect(list.status()).toBe(200);
    const bots = await list.json();
    expect(bots).toHaveLength(1);
    expect(bots[0].id).toBe(state.botId);
    expect(bots[0].tokenMask).toBe('****oken');
  });

  test('2. Update do Telegram → evento TELEGRAM_UPDATE no outbox', async ({ request }) => {
    // 2.1 Extrai o segredo da URL registrada no mock (o único lugar onde o
    //     webhook_secret em claro existe — nunca no banco/resposta).
    const setWebhook = telegramRequests.find((r) => r.url.endsWith('/setWebhook'));
    const urlMatch = setWebhook.body.match(/https?:\/\/[^/]+\/webhooks\/telegram\/([^"\/]+)\/([^"]+)/);
    expect(urlMatch, 'URL do webhook com id/secret').toBeTruthy();
    const [, idFromUrl, secret] = urlMatch;

    // 2.2 Simula um update real do Telegram (mensagem de texto arbitrária —
    //     inclusive conteúdo que parece SQLi, validando a exclusão do body
    //     inspection para /webhooks/telegram). O header usa o secret_token
    //     INDEPENDENTE (corpo do setWebhook), não o segredo da URL (auditoria
    //     2026-08-18).
    const update = {
      update_id: 100,
      message: { message_id: 1, from: { id: 999, first_name: 'Comprador' }, chat: { id: 999, type: 'private' }, text: 'oi union select 1' },
    };
    const sent = await request.post(`/webhooks/telegram/${idFromUrl}/${secret}`, {
      headers: { 'X-Telegram-Bot-Api-Secret-Token': JSON.parse(setWebhook.body).secret_token },
      data: update,
    });
    expect(sent.status()).toBe(204);

    // 2.3 O evento TELEGRAM_UPDATE foi persistido no outbox (mesma transação).
    const rows = await dbQuery(
      `SELECT type, payload, merchant_id FROM notification_outbox
        WHERE type = 'TELEGRAM_UPDATE' AND merchant_id = $1 ORDER BY created_at DESC`,
      [state.merchantId],
    );
    expect(rows.length).toBeGreaterThanOrEqual(1);
    const payload = JSON.parse(rows[0].payload);
    expect(payload.data.botId).toBe(state.botId);
    expect(payload.data.update.update_id).toBe(100);
  });

  test('3. Pausar → update descartado; retomar → ACTIVE', async ({ request }) => {
    // 3.1 Pausa (ACTIVE → PAUSED, deleteWebhook chamado).
    const paused = await request.post(`/api/v1/portal/bots/${state.botId}/pause`, { headers: state.portalHeaders });
    expect(paused.status()).toBe(200);
    expect((await paused.json()).status).toBe('PAUSED');
    expect(telegramRequests.some((r) => r.url.endsWith('/deleteWebhook'))).toBe(true);

    // 3.2 Update recebido em PAUSED é DESCARTADO (204, sem evento novo). O
    //     header segue obrigatório (auditoria 2026-08-18) — mesmo para descarte.
    const setWebhook = telegramRequests.find((r) => r.url.endsWith('/setWebhook'));
    const urlMatch = setWebhook.body.match(/https?:\/\/[^/]+\/webhooks\/telegram\/([^"\/]+)\/([^"]+)/);
    const [, idFromUrl, secret] = urlMatch;
    const discarded = await request.post(`/webhooks/telegram/${idFromUrl}/${secret}`, {
      headers: { 'X-Telegram-Bot-Api-Secret-Token': JSON.parse(setWebhook.body).secret_token },
      data: { update_id: 101 },
    });
    expect(discarded.status()).toBe(204);

    const afterPause = await dbQuery(
      `SELECT COUNT(*)::int AS count FROM notification_outbox WHERE type = 'TELEGRAM_UPDATE' AND merchant_id = $1`,
      [state.merchantId],
    );
    expect(afterPause[0].count).toBe(1); // nenhum evento novo

    // 3.3 Retoma (PAUSED → ACTIVE, re-integra — setWebhook chamado de novo).
    const resumed = await request.post(`/api/v1/portal/bots/${state.botId}/resume`, { headers: state.portalHeaders });
    expect(resumed.status()).toBe(200);
    expect((await resumed.json()).status).toBe('ACTIVE');
    const setWebhooks = telegramRequests.filter((r) => r.url.endsWith('/setWebhook'));
    expect(setWebhooks.length).toBeGreaterThanOrEqual(2);
  });

  test('4. Espelho read-only do master (todos os lojistas, com nome)', async ({ request }) => {
    // 4.0 Master criado via SQL (o seed FOXCASH_MASTER_SEED_* NÃO é garantido após
    //     o global-setup truncar `masters`; padrão dos cenários 13/14/27 da
    //     journey — auth.spec.js). BCrypt real da senha conhecida.
    const nodeCrypto = require('crypto');
    const masterUsername = `bots-master-${Date.now()}`;
    await dbQuery(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), masterUsername,
        '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6',
        'Master Bots E2E', new Date().toISOString()],
    );

    // 4.1 Login master.
    const masterLogin = await request.post('/api/v1/auth/master/login', {
      data: { username: masterUsername, password: 'Senha-E2E-123' },
    });
    expect(masterLogin.status()).toBe(200);
    state.masterHeaders = { Authorization: `Bearer ${(await masterLogin.json()).token}` };

    // 4.2 Espelho lista o bot com o nome do lojista.
    const list = await request.get('/api/v1/master/bots', { headers: state.masterHeaders });
    expect(list.status()).toBe(200);
    const bots = await list.json();
    const found = bots.find((b) => b.id === state.botId);
    expect(found).toBeTruthy();
    expect(found.merchantName).toBe('Loja Bots E2E');
    expect(found.tokenMask).toBe('****oken');
    expect(found.token).toBeUndefined();

    // 4.3 Detalhe read-only + escrita rejeitada (403/405 — sem rota de escrita).
    const detail = await request.get(`/api/v1/master/bots/${state.botId}`, { headers: state.masterHeaders });
    expect(detail.status()).toBe(200);
    const writeAttempt = await request.post('/api/v1/master/bots', {
      headers: state.masterHeaders,
      data: { platform: 'telegram', token: 'x', displayName: 'X' },
    });
    expect([403, 405]).toContain(writeAttempt.status());
  });

  test('5. Remover (soft-delete) → 404 no portal, linha preservada no banco', async ({ request }) => {
    const removed = await request.delete(`/api/v1/portal/bots/${state.botId}`, { headers: state.portalHeaders });
    expect(removed.status()).toBe(204);

    const get = await request.get(`/api/v1/portal/bots/${state.botId}`, { headers: state.portalHeaders });
    expect(get.status()).toBe(404);

    // Soft-delete: a linha permanece (deleted_at preenchido) — trilha/histórico.
    const rows = await dbQuery('SELECT deleted_at IS NOT NULL AS soft_deleted FROM store_bots WHERE id = $1', [state.botId]);
    expect(rows[0].soft_deleted).toBe(true);
  });
});
