// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');

/**
 * Painel de usuários do master (Spec 97) — endpoints READ-ONLY novos:
 *   GET /api/v1/master/merchants/panel         (MED%/conversão/Pix pagos/gateway)
 *   GET /api/v1/master/merchants/{id}/panel    (KPIs + dados da conta mascarados)
 *   GET /api/v1/master/merchants/{id}/transactions (comprador via lead, produto, líquido)
 *   GET /api/v1/master/merchants/{id}/stores   (Ver Shops — espelho read-only)
 *
 * Seeds via API do portal (mesmo padrão do psp-flow/bot-funnel):
 *   lojista → loja → plano → cobrança avulsa (foxcash/cob) + cobrança via plano
 *   (purchase com botId + lead) → webhook PAID (APPROVED) → chargeback (master)
 *   → valida MED, conversão, gateway, perfil mascarado, transações com
 *   comprador/produto/líquido e stores. 401 sem token / 403 com API key.
 */
test.describe.serial('Painel de usuários do master (Spec 97)', () => {
  const ts = Date.now();
  const BASE = process.env.API_BASE_URL || 'http://localhost:8080';

  /**
   * Replica o CryptoConverter do backend (AES-256/GCM, `v1:<base64(iv+ct+tag)>`,
   * chave = SHA-256 de foxcash.crypto.secret). O seed SQL de store_bots precisa
   * de ciphertext VÁLIDO: o purchase carrega a entidade e decripta o token —
   * 'v1:fake' (inválido) → 500 (Spec 97 D3.3 / master-panel.spec.js 1.3).
   */
  function encryptAtRest(plain) {
    const secret = process.env.FOXCASH_CRYPTO_SECRET || 'dev-only-secret-change-me-32bytes';
    const key = nodeCrypto.createHash('sha256').update(secret).digest();
    const iv = nodeCrypto.randomBytes(12);
    const cipher = nodeCrypto.createCipheriv('aes-256-gcm', key, iv);
    const ct = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
    return 'v1:' + Buffer.concat([iv, ct, cipher.getAuthTag()]).toString('base64');
  }
  const PASSWORD = 'Senha-E2E-123';
  const PASSWORD_BCRYPT = '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6';
  const username = `panel-master-${ts}`;

  /** @type {string} */
  let masterToken;
  /** @type {string} */
  let merchantId;
  /** @type {string} */
  let apiKey;
  /** @type {Record<string, string>} */
  let portalHeaders;
  /** @type {string} */
  let planId;
  /** @type {string} */
  let avulsaTransactionId;
  /** @type {string} */
  let avulsaExternalRef;
  /** @type {string} */
  let planExternalRef;

  function dbClient() {
    return new Client({
      host: process.env.DB_HOST || 'localhost',
      port: Number(process.env.DB_PORT || 5432),
      user: process.env.DB_USERNAME || 'nyxcash',
      password: process.env.DB_PASSWORD || 'nyxcash',
      database: process.env.DB_NAME || 'nyxcash',
    });
  }

  test.beforeAll(async ({ request }) => {
    // Master OPERATOR (can_manage_masters=false) — padrão do auth.spec.js B1.
    const client = dbClient();
    await client.connect();
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), username, PASSWORD_BCRYPT, 'Panel Master E2E', new Date().toISOString()],
    );
    await client.end();

    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    masterToken = (await login.json()).token;

    // Lojista (cadastro público) + login no portal (cria a loja e o plano).
    const email = `panel.e2e.${ts}@example.com`;
    const register = await request.post(`${BASE}/api/v1/merchants`, {
      data: { name: 'Painel Loja E2E', email, password: 'Senha-Loja-1' },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    merchantId = merchant.id;
    apiKey = merchant.apiKey;

    // Documento (CPF) para o painel mascarado (Spec 97 D3.2) — coluna
    // criptografada em repouso (CryptoConverter): seed com ciphertext válido.
    const dbc = dbClient();
    await dbc.connect();
    await dbc.query('UPDATE merchants SET document = $1 WHERE id = $2', [encryptAtRest('52998224725'), merchantId]);
    await dbc.end();

    const portalLogin = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password: 'Senha-Loja-1' },
    });
    expect(portalLogin.status()).toBe(200);
    portalHeaders = { Authorization: `Bearer ${(await portalLogin.json()).token}` };

    const store = await request.post(`${BASE}/api/v1/portal/store`, {
      headers: portalHeaders,
      data: { name: 'Loja Painel', description: 'E2E', color: '#3498DB', emoji: '🛒',
        title: 'Compre agora', footer: 'Obrigado!' },
    });
    expect(store.status()).toBe(201);

    const plan = await request.post(`${BASE}/api/v1/portal/plans`, {
      headers: portalHeaders,
      data: { name: 'VIP Semanal', price: 4.89, description: 'Plano VIP' },
    });
    expect(plan.status()).toBe(201);
    planId = (await plan.json()).id;
  });

  test('1. Cobrança avulsa + cobrança via plano + PAID + chargeback (seeds)', async ({ request }) => {
    // 1.1 Cobrança AVULSA (sem bot/lead) — PENDING; paga via webhook → APPROVED.
    const avulsa = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount: 10.0, buyerId: 'buyer-avulsa', expirationSeconds: 3600,
        idempotencyKey: `panel-avulsa-${ts}` },
    });
    expect(avulsa.status()).toBe(201);
    const cob = await avulsa.json();
    avulsaTransactionId = cob.transactionId;
    avulsaExternalRef = cob.externalReference;
    expect(cob.externalReference).toMatch(/^E[0-9A-F]{32}$/);

    const paidAvulsa = await request.post(`${BASE}/webhooks/pix`, {
      data: { eventId: `evt-panel-avulsa-${ts}`, externalReference: avulsaExternalRef,
        status: 'PAID', settledAt: new Date().toISOString(), amount: 10.0 },
    });
    expect(paidAvulsa.status()).toBe(204);

    // 1.2 Cobrança AVULSA 2 (alvo do chargeback — vira REFUNDED; as outras 2
    //     cobranças ficam APPROVED → paidPixCount = 2).
    const avulsa2 = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount: 5.0, buyerId: 'buyer-cb', expirationSeconds: 3600,
        idempotencyKey: `panel-cb-${ts}` },
    });
    expect(avulsa2.status()).toBe(201);
    const cob2 = await avulsa2.json();
    const avulsa2TransactionId = cob2.transactionId;
    const avulsa2ExternalRef = cob2.externalReference;

    const paidAvulsa2 = await request.post(`${BASE}/webhooks/pix`, {
      data: { eventId: `evt-panel-cb-${ts}`, externalReference: avulsa2ExternalRef,
        status: 'PAID', settledAt: new Date().toISOString(), amount: 5.0 },
    });
    expect(paidAvulsa2.status()).toBe(204);

    // 1.3 Cobrança via PLANO com lead (compra com botId) — a transação ganha
    //     plan_id + platform_user_id; seed do lead direto no banco (o funil do
    //     Telegram é coberto pelo bot-funnel.spec.js; aqui basta o dado).
    const client = dbClient();
    await client.connect();
    const botId = nodeCrypto.randomUUID();
    await client.query(
      `INSERT INTO store_bots (id, merchant_id, platform, token_encrypted, token_hash,
                               webhook_secret_hash, display_name, username, status, created_at, updated_at)
       VALUES ($1, $2, 'TELEGRAM', $5, $3, $4, 'Bot Painel', 'botpainel', 'ACTIVE', now(), now())`,
      // token_encrypted com ciphertext VÁLIDO (encryptAtRest) — o purchase
      // carrega a entidade e decripta; 'v1:fake' (inválido) → 500.
      [botId, merchantId, nodeCrypto.randomUUID().replace(/-/g, ''), nodeCrypto.randomUUID().replace(/-/g, ''), encryptAtRest('fake-token')],
    );
    await client.query(
      `INSERT INTO bot_leads (id, bot_id, platform_user_id, first_seen_at, created_at, name, username)
       VALUES ($1, $2, $3, now(), now(), $4, $5)`,
      [nodeCrypto.randomUUID(), botId, 'tg-user-panel', 'João da Silva', 'joaosilva'],
    );
    await client.end();

    // Compra do plano via purchase com botId → PENDING com plan_id/bot_id.
    const purchase = await request.post(`${BASE}/api/v1/foxcash/plans/${planId}/purchase`, {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, botId, platform: 'TELEGRAM', platformUserId: 'tg-user-panel',
        idempotencyKey: `panel-plan-${ts}` },
    });
    expect(purchase.status()).toBe(201);
    const planCob = await purchase.json();
    planExternalRef = planCob.externalReference;
    expect(planCob.externalReference).toMatch(/^E[0-9A-F]{32}$/);

    const paidPlan = await request.post(`${BASE}/webhooks/pix`, {
      data: { eventId: `evt-panel-plan-${ts}`, externalReference: planExternalRef,
        status: 'PAID', settledAt: new Date().toISOString(), amount: 4.89 },
    });
    expect(paidPlan.status()).toBe(204);

    // 1.4 Chargeback na cobrança AVULSA 2 (via master) — vira REFUNDED e conta
    //     como ocorrência MED (as cobranças 1 e 3 ficam APPROVED).
    const cb = await request.post(`${BASE}/api/v1/master/chargebacks`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { merchantId, transactionId: avulsa2TransactionId, reason: 'Fraude E2E' },
    });
    expect(cb.status()).toBe(201);

    // 1.5 Aguarda o processamento assíncrono do webhook (poll curto).
    await new Promise((r) => setTimeout(r, 500));

    const client2 = dbClient();
    await client2.connect();
    const { rows } = await client2.query(
      'SELECT status FROM pix_transactions WHERE merchant_id = $1 ORDER BY created_at', [merchantId]);
    await client2.end();
    expect(rows.filter((r) => r.status === 'APPROVED').length).toBeGreaterThanOrEqual(2);
  });

  test('2. GET /merchants/panel — MED%, conversão, Pix pagos e gateway', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/master/merchants/panel`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.items.length).toBeGreaterThanOrEqual(1);

    const item = body.items.find((m) => m.id === merchantId);
    expect(item).toBeTruthy();
    // 2 APPROVED (avulsa + plano); chargeback = 1 → MED = 1/2*100 = 50.00.
    expect(item.paidPixCount).toBeGreaterThanOrEqual(2);
    expect(Number(item.medPercent)).toBeGreaterThan(0);
    expect(item.medOccurrences).toBeGreaterThanOrEqual(1);
    // Conversão: todas aprovadas → 100%.
    expect(Number(item.conversionRate)).toBeGreaterThan(0);
    // Gateway: provider da última cobrança (mock no dev).
    expect(item.gateway).toBeTruthy();
    expect(Number(item.withdrawalFee)).toBe(2.5);
    expect(item.status).toBe('ACTIVE');
  });

  test('3. GET /merchants/{id}/panel — KPIs, documento mascarado, taxas', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/master/merchants/${merchantId}/panel`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();

    expect(Number(body.kpis.walletBalance)).toBeGreaterThanOrEqual(0);
    expect(Number(body.kpis.grossRevenue)).toBeGreaterThan(0);
    expect(body.kpis.paidPixCount).toBeGreaterThanOrEqual(2);
    expect(Number(body.kpis.conversionRate)).toBeGreaterThan(0);
    expect(body.kpis.storesCount).toBe(1);

    expect(body.account.name).toBe('Painel Loja E2E');
    expect(body.account.email).toContain('panel.e2e');
    expect(body.account.documentMasked).toMatch(/^\*{3}$|^\d{2,3}\.\*{3}/);
    expect(body.account.gateway).toBeTruthy();

    expect(Number(body.feesAndLimits.transactionFee)).toBe(0.5);
    expect(Number(body.feesAndLimits.fixedFee)).toBe(0.5);
    expect(Number(body.feesAndLimits.withdrawalFee)).toBe(2.5);
    expect(Number(body.feesAndLimits.medPercent)).toBeGreaterThan(0);
    expect(body.feesAndLimits.medOccurrences).toBeGreaterThanOrEqual(1);
  });

  test('4. GET /merchants/{id}/transactions — comprador via lead, produto, líquido', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/master/merchants/${merchantId}/transactions`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.items.length).toBeGreaterThanOrEqual(2);

    // Cobrança via plano: comprador via lead do CRM + produto + líquido.
    const planTx = body.items.find((t) => t.externalReference === planExternalRef);
    expect(planTx).toBeTruthy();
    expect(planTx.buyerName).toBe('João da Silva');
    expect(planTx.productName).toBe('VIP Semanal');
    // líquido = 4.89 - 0.50 = 4.39.
    expect(Number(planTx.netAmount)).toBe(4.39);

    // Cobrança avulsa (sem lead): buyerName null, buyerIdHash opaco presente.
    const avulsaTx = body.items.find((t) => t.externalReference === avulsaExternalRef);
    expect(avulsaTx).toBeTruthy();
    expect(avulsaTx.buyerName).toBeNull();
    expect(avulsaTx.buyerIdHash).toBeTruthy();
    // líquido = 10.00 - 0.50 = 9.50.
    expect(Number(avulsaTx.netAmount)).toBe(9.5);
  });

  test('5. GET /merchants/{id}/stores — espelho read-only (1 store)', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/master/merchants/${merchantId}/stores`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    expect(res.status()).toBe(200);
    const stores = await res.json();
    expect(stores).toHaveLength(1);
    expect(stores[0].name).toBe('Loja Painel');
    expect(stores[0].title).toBe('Compre agora');
    expect(stores[0].color).toBe('#3498DB');
    expect(stores[0].emoji).toBe('🛒');
    // Sem social_links (URLs sensíveis do lojista) e sem footer.
    expect(stores[0].socialLinks).toBeUndefined();
    expect(stores[0].footer).toBeUndefined();
  });

  test('6. 401 sem token / 403 com API key nas rotas novas', async ({ request }) => {
    const noToken = await request.get(`${BASE}/api/v1/master/merchants/panel`);
    expect(noToken.status()).toBe(401);

    const apiKeyAuth = await request.get(`${BASE}/api/v1/master/merchants/panel`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(apiKeyAuth.status()).toBe(403);
  });

  test('7. 404 merchant_not_found no detalhe', async ({ request }) => {
    const missing = nodeCrypto.randomUUID();
    const res = await request.get(`${BASE}/api/v1/master/merchants/${missing}/panel`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    expect(res.status()).toBe(404);
    expect((await res.json()).code).toBe('merchant_not_found');
  });
});
