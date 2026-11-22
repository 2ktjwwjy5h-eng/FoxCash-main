// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');

/**
 * Fallback automático de adquirente (Spec 91) — E2E:
 * o PixProviderRouter tenta os adquirentes em ordem de prioridade
 * (`foxcash.psp.routing.*`), com poll anti-dupla-cobrança e política por risco
 * do lojista (`merchant_risk.risk_score` → `routing.policies.*`).
 *
 * Requer a app rodando com o perfil dev E o roteamento ligado:
 *
 *   FOXCASH_PSP_ROUTING_ENABLED=true \
 *   FOXCASH_PSP_ROUTING_PROVIDERS=mock,suitpay \
 *   FOXCASH_PSP_ROUTING_POLICY_DEFAULT_PROVIDERS=mock,suitpay \
 *   FOXCASH_PSP_ROUTING_POLICY_HIGH_RISK_PROVIDERS=suitpay \
 *   FOXCASH_PSP_ROUTING_POLICY_HIGH_RISK_SCORE_MIN=60 \
 *   SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
 *
 * A falha do mock A ("mock") é controlada em RUNTIME pelo endpoint dev
 * `/internal/psp-mock/{provider}/simulation` (PspMockControlController —
 * @Profile("dev"); em prod o endpoint não existe). O "suitpay" é o 2º mock
 * (adapter futuro simulado), sempre limpo.
 *
 * Sem essas envs o cenário é ignorado (fail-fast honesto: sem routing ligado o
 * fallback não existe para testar).
 */
test.describe('Fallback de adquirente Pix (Spec 91)', () => {
  test.describe.configure({ mode: 'serial' });

  function dbClient() {
    return new Client({
      host: process.env.DB_HOST || 'localhost',
      port: Number(process.env.DB_PORT || 5432),
      user: process.env.DB_USERNAME || 'nyxcash',
      password: process.env.DB_PASSWORD || 'nyxcash',
      database: process.env.DB_NAME || 'nyxcash',
    });
  }

  async function providerOf(transactionId) {
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      'SELECT provider, external_reference, status FROM pix_transactions WHERE id = $1',
      [transactionId],
    );
    await client.end();
    return rows[0];
  }

  async function setSimulation(request, provider, mode) {
    const res = await request.post(`/internal/psp-mock/${provider}/simulation`, { data: { mode } });
    expect(res.status(), `simulação ${provider}=${mode}`).toBe(200);
  }

  async function createMerchant(request, suffix) {
    const register = await request.post('/api/v1/merchants', {
      data: { name: `Loja Fallback ${suffix}`, email: `fallback.${suffix}@example.com`, password: 'Senha-Fallback-1' },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    return { merchantId: merchant.id, apiKey: merchant.apiKey };
  }

  async function createCharge(request, apiKey, merchantId, idempotencyKey) {
    const charge = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount: 25.0, buyerId: 'buyer-fallback-001', expirationSeconds: 3600, idempotencyKey },
    });
    expect(charge.status(), 'cobrança deve ser criada (201)').toBe(201);
    return charge.json();
  }

  test.beforeEach(async ({ request }) => {
    // Simulação limpa entre cenários (o estado dos mocks é em memória — o
    // global-setup limpa o banco, não o mock).
    await setSimulation(request, 'mock', 'NORMAL');
    await setSimulation(request, 'suitpay', 'NORMAL');
  });

  test.afterAll(async ({ request }) => {
    // Reset: não deixa o mock A falhando para as specs seguintes.
    await request.post('/internal/psp-mock/mock/simulation', { data: { mode: 'NORMAL' } });
    await request.post('/internal/psp-mock/suitpay/simulation', { data: { mode: 'NORMAL' } });
  });

  let sharedRef;

  test('1. Fallback: mock A fora do ar (5xx) → cobrança criada no B (suitpay)', async ({ request }) => {
    const { merchantId, apiKey } = await createMerchant(request, `a1-${Date.now()}`);
    await setSimulation(request, 'mock', 'FAILURE'); // A fora do ar (5xx incerto)

    const cob = await createCharge(request, apiKey, merchantId, `fb-1-${Date.now()}`);

    // O poll no A não acha cobrança (nada foi criado) → B (suitpay) cria.
    const row = await providerOf(cob.transactionId);
    expect(row.provider).toBe('suitpay');
    expect(row.external_reference).toMatch(/^E[0-9A-F]{32}$/);
    expect(cob.pixQrCode).toBeTruthy();
    sharedRef = row.external_reference;
  });

  test('2. Timeout de A com cobrança CRIADA → poll acha → usa A (sem dupla cobrança, sem B)', async ({ request }) => {
    const { merchantId, apiKey } = await createMerchant(request, `a2-${Date.now()}`);
    await setSimulation(request, 'mock', 'TIMEOUT_AFTER_CREATE'); // A criou mas respondeu incerto

    const cob = await createCharge(request, apiKey, merchantId, `fb-2-${Date.now()}`);

    // O poll (getChargeByCode) ACHOU a cobrança no A → o router re-cria (retry
    // idempotente, Spec 28) e usa A — B NUNCA é chamado (sem dupla cobrança).
    const row = await providerOf(cob.transactionId);
    expect(row.provider).toBe('mock');
    expect(row.external_reference).toMatch(/^E[0-9A-F]{32}$/);
  });

  test('3. Webhook do B processa PAID → APPROVED (provider=suitpay)', async ({ request }) => {
    // Reusa a cobrança criada no suitpay pelo cenário 1 (serial).
    expect(sharedRef, 'cenário 1 deve ter criado a cobrança no B').toMatch(/^E[0-9A-F]{32}$/);

    const paid = await request.post('/webhooks/pix', {
      data: { eventId: 'evt-fallback-3', externalReference: sharedRef, status: 'PAID',
        settledAt: new Date().toISOString(), amount: 25.0 },
    });
    expect(paid.status()).toBe(204);

    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      "SELECT status, provider FROM pix_transactions WHERE external_reference = $1", [sharedRef]);
    await client.end();
    expect(rows[0].status).toBe('APPROVED');
    expect(rows[0].provider).toBe('suitpay');
  });

  test('4. Isolamento por policy: lojista high-risk → suitpay; default → mock', async ({ request }) => {
    // Lojista DEFAULT (sem snapshot de risco) → policy default → mock (A).
    const white = await createMerchant(request, `w-${Date.now()}`);
    const cobWhite = await createCharge(request, white.apiKey, white.merchantId, `fb-w-${Date.now()}`);
    const whiteRow = await providerOf(cobWhite.transactionId);
    expect(whiteRow.provider).toBe('mock');

    // Lojista HIGH-RISK (score 85 ≥ limiar 60) → policy high-risk [suitpay] —
    // isolamento por nicho: o tráfego dele NUNCA vai para o adquirente white.
    const risky = await createMerchant(request, `r-${Date.now()}`);
    const client = dbClient();
    await client.connect();
    await client.query(
      `INSERT INTO merchant_risk (id, merchant_id, computed_at, approved_volume,
          total_transactions, approved_transactions, refunded_transactions,
          refund_rate_percent, risk_score)
       VALUES ($1, $2, now(), 0, 0, 0, 0, 0, 85)`,
      [nodeCrypto.randomUUID(), risky.merchantId],
    );
    await client.end();

    const cobRisky = await createCharge(request, risky.apiKey, risky.merchantId, `fb-r-${Date.now()}`);
    const riskyRow = await providerOf(cobRisky.transactionId);
    expect(riskyRow.provider).toBe('suitpay');
  });
});
