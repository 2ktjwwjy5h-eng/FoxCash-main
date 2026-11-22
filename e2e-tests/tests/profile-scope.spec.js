// @ts-check
// Perfil de operação — filtro rígido de dados por perfil (Spec 124):
//   - 2 perfis (default "Padrão BR" + criado) com bots/transações/leads/
//     broadcasts distintos (atribuição de bots via SQL — sem API de atribuição)
//   - trocar X-Profile-ID → dashboard/extrato/CRM/bots/broadcasts mostram só o perfil
//   - header inválido → VAZIO (nunca cai para o lojista inteiro, nunca vaza)
//   - sem header → lojista inteiro (regressão — clientes legados)
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');
const nodeCrypto = require('crypto');

const TELEGRAM_MOCK_PORT = Number(process.env.FOXCASH_TELEGRAM_MOCK_PORT || 9001);

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

test.describe.serial('Portal — Perfil de operação: filtro rígido (Spec 124)', () => {
  const ts = Date.now();
  const state = {
    portalHeaders: {},
    merchantId: '',
    defaultProfileId: '',
    profileBId: '',
    botA: '',
    botB: '',
  };

  /** @type {import('http').Server} */
  let telegramServer;

  test.beforeAll(async ({ request }) => {
    telegramServer = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.url.endsWith('/getMe')) {
          // Bot A e Bot B compartilham o mock; o getMe devolve identidade estável.
          res.end(JSON.stringify({ ok: true, result: { id: 111, is_bot: true, first_name: 'Bot Perfil', username: 'botperfil' } }));
        } else {
          res.end(JSON.stringify({ ok: true, result: true }));
        }
      });
    });
    await new Promise((resolve) => telegramServer.listen(TELEGRAM_MOCK_PORT, resolve));

    const email = `profile.e2e.${ts}@example.com`;
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Perfil E2E', email, password: 'Senha-Perfil-123' },
    });
    expect(reg.status()).toBe(201);
    state.merchantId = (await reg.json()).id;

    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: 'Senha-Perfil-123' },
    });
    expect(login.status()).toBe(200);
    state.portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // Perfil default criado de forma lazy (Spec 122 — ensureDefaultProfile).
    const profiles = await request.get('/api/v1/portal/profile', { headers: state.portalHeaders });
    expect(profiles.status()).toBe(200);
    const defaultList = await profiles.json();
    expect(defaultList.profiles).toHaveLength(1);
    expect(defaultList.profiles[0].name).toBe('Padrão BR');
    state.defaultProfileId = defaultList.profiles[0].id;

    // Perfil B (ex.: "Global/Stripe" — sem adapter real, só estrutura).
    const create = await request.post('/api/v1/portal/profile', {
      headers: state.portalHeaders,
      data: { name: 'Global/Stripe' },
    });
    expect(create.status()).toBe(201);
    state.profileBId = (await create.json()).id;

    // 2 bots (mock Telegram) — atribuídos aos perfis via SQL (a Spec 122 não
    // expõe atribuição de bot por API; o vínculo é a coluna store_bots.profile_id).
    const botA = await request.post('/api/v1/portal/bots', {
      headers: state.portalHeaders,
      data: { platform: 'telegram', token: '111111:AAA-profile-token', displayName: 'Bot A' },
    });
    expect(botA.status()).toBe(201);
    state.botA = (await botA.json()).id;
    const botB = await request.post('/api/v1/portal/bots', {
      headers: state.portalHeaders,
      data: { platform: 'telegram', token: '222222:BBB-profile-token', displayName: 'Bot B' },
    });
    expect(botB.status()).toBe(201);
    state.botB = (await botB.json()).id;

    await dbQuery('UPDATE store_bots SET profile_id = $1 WHERE id = $2', [state.defaultProfileId, state.botA]);
    await dbQuery('UPDATE store_bots SET profile_id = $1 WHERE id = $2', [state.profileBId, state.botB]);

    // Transações via o fluxo REAL de cobrança do portal (o pix_key é
    // criptografado em repouso — só a app grava valores válidos) com o perfil do
    // header X-Profile-ID (Spec 124/T2 — rota viva); o status vira APPROVED via
    // SQL (o foco aqui é o filtro de LEITURA por perfil no dashboard/extrato).
    const charge = async (profileId, amount, key) => {
      const res = await request.post('/api/v1/portal/charges', {
        headers: headersFor(profileId),
        data: { amount, description: 'Cobranca', expirationSeconds: 3600, buyerId: null, idempotencyKey: key },
      });
      expect(res.status()).toBe(201);
      return (await res.json()).transactionId;
    };
    const txA = await charge(state.defaultProfileId, 100.0, 'pk-profile-a');
    const txB = await charge(state.profileBId, 50.0, 'pk-profile-b');
    const txC = await charge(null, 25.0, 'pk-profile-c'); // sem header → default (avulsa)
    await dbQuery(
      `UPDATE pix_transactions SET status = 'APPROVED', paid_at = now() WHERE id IN ($1, $2, $3)`,
      [txA, txB, txC],
    );

    const now = new Date().toISOString();
    const lead = async (botId, platformUserId, name) => {
      await dbQuery(
        `INSERT INTO bot_leads (id, bot_id, platform_user_id, first_seen_at, created_at,
             name, username, status, handoff_status)
         VALUES ($1, $2, $3, $4, $4, $5, $6, 'NOVO', 'BOT')`,
        [nodeCrypto.randomUUID(), botId, platformUserId, now, name, `@${platformUserId}`],
      );
    };
    await lead(state.botA, 'u-profile-a', 'Cliente Perfil A');
    await lead(state.botB, 'u-profile-b', 'Cliente Perfil B');

    const bcast = async (botId, target) => {
      await dbQuery(
        `INSERT INTO broadcasts (id, store_bot_id, mode, target_chat_id, content, status, created_at, updated_at)
         VALUES ($1, $2, 'NOW', $3, 'Promo', 'COMPLETED', $4, $4)`,
        [nodeCrypto.randomUUID(), botId, target, now],
      );
    };
    await bcast(state.botA, '-100profilea');
    await bcast(state.botB, '-100profileb');
  });

  test.afterAll(async () => {
    await new Promise((resolve) => telegramServer && telegramServer.close(resolve));
  });

  const headersFor = (profileId) =>
    profileId ? { ...state.portalHeaders, 'X-Profile-ID': profileId } : state.portalHeaders;

  test('1. perfis listados + activeProfileId do header validado', async ({ request }) => {
    const res = await request.get('/api/v1/portal/profile', { headers: headersFor(state.profileBId) });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.profiles).toHaveLength(2);
    expect(body.activeProfileId).toBe(state.profileBId);
  });

  test('2. dashboard escopa por perfil; header inválido → vazio; sem header → tudo', async ({ request }) => {
    const summary = async (profileId) => {
      const res = await request.get('/api/v1/portal/dashboard/summary', { headers: headersFor(profileId) });
      expect(res.status()).toBe(200);
      return res.json();
    };
    const sumA = await summary(state.defaultProfileId);
    const sumB = await summary(state.profileBId);
    const sumAll = await summary(null);
    const sumInvalid = await summary('00000000-0000-0000-0000-000000000000');

    // A: 100 + 25 = 125 / 2 vendas aprovadas · B: 50 / 1 · tudo: 175 / 3 · inválido: 0
    expect(sumA.grossRevenue).toBe(125);
    expect(sumA.sales).toBe(2);
    expect(sumB.grossRevenue).toBe(50);
    expect(sumB.sales).toBe(1);
    expect(sumAll.grossRevenue).toBe(175);
    expect(sumAll.sales).toBe(3);
    expect(sumInvalid.sales).toBe(0); // nunca lojista inteiro
  });

  test('3. extrato (vendas) escopa por perfil', async ({ request }) => {
    const sales = async (profileId) => {
      const res = await request.get('/api/v1/portal/sales', { headers: headersFor(profileId) });
      expect(res.status()).toBe(200);
      return res.json();
    };
    const pageA = await sales(state.defaultProfileId);
    const pageB = await sales(state.profileBId);
    const pageAll = await sales(null);

    expect(pageA.totalElements).toBe(2);
    expect(pageB.totalElements).toBe(1);
    expect(pageAll.totalElements).toBe(3);
  });

  test('4. bots listam só a frota do perfil', async ({ request }) => {
    const bots = async (profileId) => {
      const res = await request.get('/api/v1/portal/bots', { headers: headersFor(profileId) });
      expect(res.status()).toBe(200);
      return res.json();
    };
    const fleetA = await bots(state.defaultProfileId);
    const fleetB = await bots(state.profileBId);
    const fleetAll = await bots(null);

    expect(fleetA.map((b) => b.id)).toEqual([state.botA]);
    expect(fleetB.map((b) => b.id)).toEqual([state.botB]);
    expect(fleetAll.map((b) => b.id).sort()).toEqual([state.botA, state.botB].sort());
  });

  test('5. CRM (leads) escopa por bot-set do perfil', async ({ request }) => {
    const leads = async (profileId) => {
      const res = await request.get('/api/v1/portal/crm/leads', { headers: headersFor(profileId) });
      expect(res.status()).toBe(200);
      return res.json();
    };
    const pageA = await leads(state.defaultProfileId);
    const pageB = await leads(state.profileBId);
    const pageAll = await leads(null);
    const pageInvalid = await leads('00000000-0000-0000-0000-000000000000');

    expect(pageA.items.map((l) => l.botId)).toEqual([state.botA]);
    expect(pageB.items.map((l) => l.botId)).toEqual([state.botB]);
    expect(pageAll.totalElements).toBe(2);
    expect(pageInvalid.items).toHaveLength(0);
  });

  test('6. broadcasts escopam por bot-set do perfil', async ({ request }) => {
    const broadcasts = async (profileId) => {
      const res = await request.get('/api/v1/portal/broadcasts', { headers: headersFor(profileId) });
      expect(res.status()).toBe(200);
      return res.json();
    };
    const listA = await broadcasts(state.defaultProfileId);
    const listB = await broadcasts(state.profileBId);
    const listAll = await broadcasts(null);

    expect(listA.map((b) => b.storeBotId)).toEqual([state.botA]);
    expect(listB.map((b) => b.storeBotId)).toEqual([state.botB]);
    expect(listAll.map((b) => b.storeBotId).sort()).toEqual([state.botA, state.botB].sort());
  });
});
