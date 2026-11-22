// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');
const { totpAt } = require('./helpers/totp');

/**
 * Config fina por lojista + controle de conta (Spec 98) — endpoints de ESCRITA:
 *   GET/PUT /api/v1/master/merchants/{id}/config     (config parcial PATCH)
 *   POST /api/v1/master/merchants/{id}/config/debit   (débito com step-up TOTP)
 *   POST .../config/honeypot {enabled}                (trap + saques bloqueados)
 *   POST .../config/deactivate | /config/activate     (kill switch)
 *   POST .../config/debit e /ban                      (sensíveis — step-up)
 *   POST /api/v1/master/merchants/{id}/ban            (BANNED irreversível)
 *
 * Fluxo: taxa FIXED → netAmount reflete; PERCENT+threshold → % acima / fixa
 * abaixo; rate limit por lojista → 429 no teto do lojista; deactivate →
 * portal/API bloqueados → activate; honeypot on → cobrança HP + sem PAID +
 * saque 409; api_blocked → API 403; debit com step-up → ledger + auditoria;
 * ban → tudo bloqueado + auditoria.
 */
test.describe.serial('Config fina por lojista (Spec 98)', () => {
  const ts = Date.now();
  const BASE = process.env.API_BASE_URL || 'http://localhost:8080';
  const PASSWORD = 'Senha-E2E-123';
  const PASSWORD_BCRYPT = '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6';
  const username = `config-master-${ts}`;

  /** @type {string} */
  let masterToken;
  /** @type {string} */
  let mfaSecret;
  /** @type {string} */
  let merchantId;
  /** @type {string} */
  let apiKey;
  /** @type {Record<string, string>} */
  let portalHeaders;
  /** @type {import('@playwright/test').APIRequestContext} */
  let request;

  function dbClient() {
    return new Client({
      host: process.env.DB_HOST || 'localhost',
      port: Number(process.env.DB_PORT || 5432),
      user: process.env.DB_USERNAME || 'nyxcash',
      password: process.env.DB_PASSWORD || 'nyxcash',
      database: process.env.DB_NAME || 'nyxcash',
    });
  }

  /** Faz o step-up via API (TOTP +offset janelas — anti-replay: o mesmo código
   * não pode ser reusado; testes seriais próximos precisam de janelas distintas). */
  async function stepUpApi(request, token, secret, offset = 30) {
    const res = await request.post(`${BASE}/api/v1/master/mfa/step-up`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { code: totpAt(secret, offset) },
    });
    expect(res.status()).toBe(200);
  }

  /** Cria cobrança avulsa (API key) e paga via webhook; devolve {txId, externalRef}. */
  async function chargeAndPay(request, amount, suffix) {
    const cob = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount, buyerId: `buyer-${suffix}`, expirationSeconds: 3600,
        idempotencyKey: `cfg-${suffix}-${ts}` },
    });
    expect(cob.status()).toBe(201);
    const body = await cob.json();
    const paid = await request.post(`${BASE}/webhooks/pix`, {
      data: { eventId: `evt-cfg-${suffix}-${ts}`, externalReference: body.externalReference,
        status: 'PAID', settledAt: new Date().toISOString(), amount },
    });
    expect(paid.status()).toBe(204);
    return body;
  }

  /** Net de uma transação via painel do master (Spec 97 — líquido). */
  async function netOf(request, txId) {
    const list = await request.get(`${BASE}/api/v1/master/merchants/${merchantId}/transactions`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    expect(list.status()).toBe(200);
    const page = await list.json();
    // O DTO do painel usa `transactionId` (Spec 97 D3.3), não `id`.
    const tx = page.items.find((t) => t.transactionId === txId);
    expect(tx).toBeTruthy();
    return tx.netAmount;
  }

  test.beforeAll(async ({ request: req }) => {
    request = req;
    // Master OPERATOR com MFA (para o step-up das ações sensíveis).
    const client = dbClient();
    await client.connect();
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), username, PASSWORD_BCRYPT, 'Config Master E2E', new Date().toISOString()],
    );
    await client.end();

    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    masterToken = (await login.json()).token;

    // Ativa o MFA de verdade (TOTP real) para o step-up.
    const activate = await request.post(`${BASE}/api/v1/master/mfa/activate`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    expect(activate.status()).toBe(200);
    mfaSecret = (await activate.json()).manualEntryKey;
    const confirm = await request.post(`${BASE}/api/v1/master/mfa/confirm`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { code: totpAt(mfaSecret, 0) },
    });
    expect(confirm.status()).toBe(200);

    // Lojista (cadastro público) + login no portal.
    const email = `config.e2e.${ts}@example.com`;
    const register = await request.post(`${BASE}/api/v1/merchants`, {
      data: { name: 'Config Loja E2E', email, password: 'Senha-Loja-1' },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    merchantId = merchant.id;
    apiKey = merchant.apiKey;

    const portalLogin = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password: 'Senha-Loja-1' },
    });
    expect(portalLogin.status()).toBe(200);
    portalHeaders = { Authorization: `Bearer ${(await portalLogin.json()).token}` };

    // Saldo para o débito: paga 2 cobranças (100 + 50) → wallet 150.00.
    await chargeAndPay(request, 100.0, 'seed-a');
    await chargeAndPay(request, 50.0, 'seed-b');
  });

  test('1. GET config retorna defaults (sem overrides)', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/master/merchants/${merchantId}/config`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    expect(res.status()).toBe(200);
    const cfg = await res.json();
    expect(cfg.pixFeeType).toBeNull();
    expect(cfg.apiBlocked).toBe(false);
    expect(cfg.honeypot).toBe(false);
  });

  test('2. PUT config FIXED → netAmount reflete a taxa fixa', async ({ request }) => {
    const put = await request.put(`${BASE}/api/v1/master/merchants/${merchantId}/config`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { pixFeeType: 'FIXED', pixFeeFixed: 1.25 },
    });
    expect(put.status()).toBe(200);

    const tx = await chargeAndPay(request, 10.0, 'fixed');
    // 10,00 − 1,25 = 8,75 (override substitui os R$ 0,50 do plano).
    expect(await netOf(request, tx.transactionId)).toBeCloseTo(8.75, 2);
  });

  test('3. PUT config PERCENT+threshold → % acima / fixa abaixo', async ({ request }) => {
    const put = await request.put(`${BASE}/api/v1/master/merchants/${merchantId}/config`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { pixFeeType: 'PERCENT', pixFeeFixed: 1.0, pixFeePercent: 2.0, pixFeeThreshold: 100.0 },
    });
    expect(put.status()).toBe(200);

    // Acima do threshold: 150 → 2% = 3,00 → net 147,00.
    const above = await chargeAndPay(request, 150.0, 'pct-above');
    expect(await netOf(request, above.transactionId)).toBeCloseTo(147.0, 2);
    // Até o threshold: 40 → fixa 1,00 → net 39,00.
    const below = await chargeAndPay(request, 40.0, 'pct-below');
    expect(await netOf(request, below.transactionId)).toBeCloseTo(39.0, 2);
  });

  test('4. Config inválida → 400 invalid_argument', async ({ request }) => {
    const bad = await request.put(`${BASE}/api/v1/master/merchants/${merchantId}/config`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { pixFeeType: 'PERCENT', pixFeePercent: 150 },
    });
    expect(bad.status()).toBe(400);
    expect((await bad.json()).code).toBe('invalid_argument');
  });

  test('5. Kill switch: deactivate bloqueia portal/API → activate restaura', async ({ request }) => {
    await request.post(`${BASE}/api/v1/master/merchants/${merchantId}/config/deactivate`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    const portal = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email: `config.e2e.${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(portal.status()).toBe(403); // merchant_not_active (INACTIVE)

    await request.post(`${BASE}/api/v1/master/merchants/${merchantId}/config/activate`, {
      headers: { Authorization: `Bearer ${masterToken}` },
    });
    const portalBack = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email: `config.e2e.${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(portalBack.status()).toBe(200);
  });

  test('6. Honeypot on → cobrança trap (HP) + sem PAID + saque 409; off restaura', async ({ request }) => {
    // O deactivate do teste 5 revoga as sessões do portal — o portalHeaders do
    // beforeAll está inválido. Refaz o login (token fresco) antes do saque.
    const relogin = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email: `config.e2e.${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(relogin.status()).toBe(200);
    portalHeaders = { Authorization: `Bearer ${(await relogin.json()).token}` };

    await request.post(`${BASE}/api/v1/master/merchants/${merchantId}/config/honeypot`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { enabled: true },
    });

    // Cobrança cria com referência HP (trap) — o atacante segue vendo a operação.
    const cob = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount: 10.0, buyerId: 'buyer-hp', expirationSeconds: 3600,
        idempotencyKey: `cfg-hp-${ts}` },
    });
    expect(cob.status()).toBe(201);
    const hpBody = await cob.json();
    expect(hpBody.externalReference).toMatch(/^HP/);

    // Nenhum crédito real: webhook PAID da ref HP não resolve (getCharge NOT_FOUND).
    const paid = await request.post(`${BASE}/webhooks/pix`, {
      data: { eventId: `evt-hp-${ts}`, externalReference: hpBody.externalReference,
        status: 'PAID', settledAt: new Date().toISOString(), amount: 10.0 },
    });
    expect(paid.status()).toBe(204);

    // Destino Pix (Spec 32) para o saque — o endpoint exige destinationId
    // (destinationKey/pixKeyType não fazem mais parte do contrato).
    const dest = await request.post(`${BASE}/api/v1/portal/destinations`, {
      headers: portalHeaders,
      data: { type: 'PIX_CPF', value: '52998224725', label: 'Honeypot E2E' },
    });
    expect(dest.status()).toBe(201);
    const destId = (await dest.json()).id;

    // Saque bloqueado (409 honeypot_blocked) — vendedor e administrativo.
    // portalHeaders já carrega o prefixo 'Bearer ' — header direto (sem duplicar).
    const withdrawal = await request.post(`${BASE}/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 10.0, destinationId: destId },
    });
    expect(withdrawal.status()).toBe(409);
    expect((await withdrawal.json()).code).toBe('honeypot_blocked');

    // Off → routing normal restaurado.
    await request.post(`${BASE}/api/v1/master/merchants/${merchantId}/config/honeypot`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { enabled: false },
    });
    const normal = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount: 5.0, buyerId: 'buyer-normal', expirationSeconds: 3600,
        idempotencyKey: `cfg-normal-${ts}` },
    });
    expect(normal.status()).toBe(201);
    expect((await normal.json()).externalReference).toMatch(/^E[0-9A-F]{32}$/);
  });

  test('7. api_blocked → API key 403 api_blocked; portal segue', async ({ request }) => {
    await request.put(`${BASE}/api/v1/master/merchants/${merchantId}/config`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { apiBlocked: true },
    });
    const blocked = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount: 10.0, buyerId: 'buyer-ab', expirationSeconds: 3600,
        idempotencyKey: `cfg-ab-${ts}` },
    });
    expect(blocked.status()).toBe(403);
    expect((await blocked.json()).code).toBe('api_blocked');

    await request.put(`${BASE}/api/v1/master/merchants/${merchantId}/config`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { apiBlocked: false },
    });
  });

  test('8. Débito com step-up → saldo −amount + ledgers + auditoria', async ({ request }) => {
    // Sem step-up → 403 step_up_required.
    const noStep = await request.post(`${BASE}/api/v1/master/merchants/${merchantId}/config/debit`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { amount: 10.0, reason: 'Multa E2E' },
    });
    expect(noStep.status()).toBe(403);
    expect((await noStep.json()).code).toBe('step_up_required');

    // Com step-up → 200 {newBalance, debitedAmount}.
    await stepUpApi(request, masterToken, mfaSecret);
    const debit = await request.post(`${BASE}/api/v1/master/merchants/${merchantId}/config/debit`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { amount: 10.0, reason: 'Multa E2E' },
    });
    expect(debit.status()).toBe(200);
    const body = await debit.json();
    expect(body.debitedAmount).toBeCloseTo(10.0, 2);

    // Ledger duplo + auditoria gravados (mesma transação).
    const client = dbClient();
    await client.connect();
    const wallet = await client.query(
      `SELECT 1 FROM wallet_ledger WHERE merchant_id = $1 AND entry_type = 'ADMIN_DEBIT'`, [merchantId]);
    expect(wallet.rowCount).toBeGreaterThan(0);
    const masterLedger = await client.query(
      `SELECT 1 FROM master_ledger WHERE merchant_id = $1 AND entry_type = 'ADMIN_DEBIT'`, [merchantId]);
    expect(masterLedger.rowCount).toBeGreaterThan(0);
    const audit = await client.query(
      `SELECT 1 FROM master_audit_log WHERE merchant_id = $1 AND action = 'ADMIN_DEBIT'`, [merchantId]);
    expect(audit.rowCount).toBeGreaterThan(0);
    await client.end();
  });

  test('9. Rate limit por lojista (rpm) → 429 no teto do lojista', async ({ request }) => {
    // O job e2e do CI desliga o rate limit global (FOXCASH_SECURITY_RATE_LIMIT
    // _ENABLED=false → AuthenticatedRateLimitFilter.shouldNotFilter=true, que
    // cobre TAMBÉM o teto por lojista). Sem o filtro não há 429 — skip honesto.
    test.skip(process.env.FOXCASH_SECURITY_RATE_LIMIT_ENABLED === 'false',
      'rate limit global desligado neste ambiente (CI e2e)');
    // rpm=2 → 2 chamadas OK, 3ª → 429 (teto MAIS restritivo que o global 120/min).
    await request.put(`${BASE}/api/v1/master/merchants/${merchantId}/config`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { rateLimitRpm: 2 },
    });
    const headers = { 'X-Api-Key': apiKey, 'Content-Type': 'application/json' };
    // /api/v1/transactions é POST-only (GET sem id → 405); idempotency keys
    // distintas por chamada para exercitar o teto do rate limit.
    const body = (n) => ({
      merchantId, amount: 5.0, description: 'Rate E2E', pixKey: 'chave-rate',
      buyerId: 'buyer-rate', idempotencyKey: `rate-${ts}-${n}`,
    });
    expect((await request.post(`${BASE}/api/v1/transactions`, { headers, data: body(1) })).status()).toBe(201);
    expect((await request.post(`${BASE}/api/v1/transactions`, { headers, data: body(2) })).status()).toBe(201);
    const limited = await request.post(`${BASE}/api/v1/transactions`, { headers, data: body(3) });
    expect(limited.status()).toBe(429);
    expect((await limited.json()).code).toBe('too_many_requests');
  });

  test('10. Ban com step-up → BANNED bloqueia tudo + auditoria', async ({ request }) => {
    // Anti-replay: o teste 8 consumiu a janela +30s do MFA — limpa o
    // last_code_hash para o step-up reusar a janela atual (padrão stepup).
    const antiReplay = dbClient();
    await antiReplay.connect();
    await antiReplay.query(
      `UPDATE master_totp t
       SET last_code_hash = NULL, failed_attempts = 0, locked_until = NULL
       FROM master_mfa_methods m JOIN masters ma ON ma.id = m.master_id
       WHERE t.method_id = m.id AND ma.username = $1`,
      [`config-master-${ts}`],
    );
    await antiReplay.end();

    await stepUpApi(request, masterToken, mfaSecret);
    const ban = await request.post(`${BASE}/api/v1/master/merchants/${merchantId}/ban`, {
      headers: { Authorization: `Bearer ${masterToken}` },
      data: { reason: 'Fraude confirmada E2E' },
    });
    expect(ban.status()).toBe(204);

    // Portal 403 merchant_not_active; API 401 (não autentica BANNED).
    const portal = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email: `config.e2e.${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(portal.status()).toBe(403);
    const api = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount: 10.0, buyerId: 'buyer-ban', expirationSeconds: 3600,
        idempotencyKey: `cfg-ban-${ts}` },
    });
    expect(api.status()).toBe(401);

    const client = dbClient();
    await client.connect();
    const audit = await client.query(
      `SELECT 1 FROM master_audit_log WHERE merchant_id = $1 AND action = 'MERCHANT_BANNED'`, [merchantId]);
    expect(audit.rowCount).toBeGreaterThan(0);
    await client.end();
  });
});
