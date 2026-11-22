// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');

/**
 * Saque automático por limiar de saldo (Spec 70) — E2E autossuficiente:
 * configuração via portal (GET defaults / PUT com validações), gatilho por
 * saldo (venda PAID >= limiar → job cria PROCESSING com amount == saldo),
 * conclusão via webhook de saída (PAID → COMPLETED) e desativação (nova venda
 * acima do limiar NÃO gera saque). Auditoria AUTO_WITHDRAW_SETTINGS_UPDATED /
 * AUTO_WITHDRAW_REQUESTED verificada no banco.
 *
 * Requer a app rodando com o perfil dev (cron curto do job — a cada 30s,
 * D10.1) e o PostgreSQL acessível (global-setup limpa o banco antes).
 */

const PASSWORD = 'Senha-E2E-123';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function dbClient() {
  return new Client({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    user: process.env.DB_USERNAME || 'nyxcash',
    password: process.env.DB_PASSWORD || 'nyxcash',
    database: process.env.DB_NAME || 'nyxcash',
  });
}

test.describe('Saque automático por limiar (Spec 70)', () => {
  test.describe.configure({ mode: 'serial' });

  let merchantId;
  let portalHeaders;

  test('1. Configuração via portal — GET com defaults (nunca 404) + PUT com validações', async ({ request }) => {
    const email = `auto.e2e.${Date.now()}@example.com`;
    const res = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Auto E2E', email, password: PASSWORD },
    });
    expect(res.status()).toBe(201);
    merchantId = (await res.json()).id;

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password: PASSWORD } });
    expect(login.status()).toBe(200);
    portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // GET sem config → 200 com defaults da plataforma (nunca 404 — D11.1).
    const getDefault = await request.get('/api/v1/portal/auto-withdraw', { headers: portalHeaders });
    expect(getDefault.status()).toBe(200);
    const def = await getDefault.json();
    expect(def.enabled).toBe(false);
    expect(Number(def.threshold)).toBe(1000.0);
    expect(def.destinationId).toBeNull();

    // Destino ATIVO para a config.
    const dest = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_CPF', value: '52998224725', label: 'Auto destino' },
    });
    expect(dest.status()).toBe(201);
    const destinationId = (await dest.json()).id;

    // Validações: threshold inválido → 400 invalid_argument.
    const badThreshold = await request.put('/api/v1/portal/auto-withdraw', {
      headers: portalHeaders,
      data: { enabled: true, threshold: 0, destinationId },
    });
    expect(badThreshold.status()).toBe(400);
    expect((await badThreshold.json()).code).toBe('invalid_argument');

    // Ligar sem destino → 400.
    const noDest = await request.put('/api/v1/portal/auto-withdraw', {
      headers: portalHeaders,
      data: { enabled: true, threshold: 100, destinationId: null },
    });
    expect(noDest.status()).toBe(400);
    expect((await noDest.json()).code).toBe('invalid_argument');

    // Destino de OUTRO lojista → 404 destination_not_found (não revela existência).
    const otherEmail = `outra.${Date.now()}@example.com`;
    const other = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Outra', email: otherEmail, password: PASSWORD },
    });
    expect(other.status()).toBe(201);
    const otherLogin = await request.post('/api/v1/merchant-auth/login', {
      data: { email: otherEmail, password: PASSWORD },
    });
    const otherHeaders = { Authorization: `Bearer ${(await otherLogin.json()).token}` };
    const otherDest = await request.post('/api/v1/portal/destinations', {
      headers: otherHeaders,
      data: { type: 'PIX_CPF', value: '39053344705', label: 'Outra' },
    });
    const otherDestId = (await otherDest.json()).id;
    const cross = await request.put('/api/v1/portal/auto-withdraw', {
      headers: portalHeaders,
      data: { enabled: true, threshold: 100, destinationId: otherDestId },
    });
    expect(cross.status()).toBe(404);
    expect((await cross.json()).code).toBe('destination_not_found');

    // Destino inativo → 409 destination_inactive.
    await request.post(`/api/v1/portal/destinations/${destinationId}/deactivate`, { headers: portalHeaders });
    const inactive = await request.put('/api/v1/portal/auto-withdraw', {
      headers: portalHeaders,
      data: { enabled: true, threshold: 100, destinationId },
    });
    expect(inactive.status()).toBe(409);
    expect((await inactive.json()).code).toBe('destination_inactive');
    await request.post(`/api/v1/portal/destinations/${destinationId}/activate`, { headers: portalHeaders });

    // Ativa → 200 com o recurso; GET reflete.
    const enable = await request.put('/api/v1/portal/auto-withdraw', {
      headers: portalHeaders,
      data: { enabled: true, threshold: 100, destinationId },
    });
    expect(enable.status()).toBe(200);
    const enabled = await enable.json();
    expect(enabled.enabled).toBe(true);
    expect(Number(enabled.threshold)).toBe(100.0);
    expect(enabled.destinationId).toBe(destinationId);

    const get = await (await request.get('/api/v1/portal/auto-withdraw', { headers: portalHeaders })).json();
    expect(get.enabled).toBe(true);
    expect(Number(get.threshold)).toBe(100.0);
  });

  test('2. Venda PAID ≥ limiar → job cria saque automático (amount == saldo) → COMPLETED via webhook de saída', async ({ request }) => {
    // O gatilho é assíncrono (job cron 30s) — polling tolerante a timing.
    test.setTimeout(150_000);

    // Cobrança 250 + webhook PAID → saldo 250 >= limiar 100.
    const charge = await request.post('/api/v1/portal/charges', {
      headers: portalHeaders,
      data: {
        amount: 250.0,
        description: 'Saldo auto',
        expirationSeconds: 3600,
        idempotencyKey: `auto-saldo-${Date.now()}`,
      },
    });
    expect(charge.status()).toBe(201);
    const chargeBody = await charge.json();
    const webhook = await request.post('/webhooks/pix', {
      data: {
        eventId: `evt-auto-${Date.now()}`,
        externalReference: chargeBody.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 250.0,
      },
    });
    expect(webhook.status()).toBe(204);

    // Polling tolerante a timing: o job (cron */30 no perfil dev) cria o
    // PROCESSING com amount == saldo − taxa 2,50 (Spec 88; wallet zera).
    let auto;
    const deadline = Date.now() + 90_000;
    while (Date.now() < deadline) {
      const list = await request.get(`/api/v1/merchants/${merchantId}/withdrawals`, {
        headers: portalHeaders,
      });
      const withdrawals = await list.json();
      auto = withdrawals.find((w) => w.status === 'PROCESSING');
      if (auto) break;
      await sleep(2000);
    }
    expect(auto, 'o job deve criar o saque automático PROCESSING').toBeTruthy();
    expect(Number(auto.amount)).toBe(247.50); // saldo 250 − taxa 2,50 (Spec 88)
    expect(Number(auto.fee)).toBe(2.50); // Spec 88: taxa de saque no registro
    expect(auto.type).toBe('MERCHANT');

    // Webhook de saída PAID → COMPLETED (idempotente).
    const payout = await request.post('/webhooks/payouts', {
      data: { externalReference: auto.externalReference, status: 'PAID' },
    });
    expect(payout.status()).toBe(204);

    // Saldo pós-débito ~0.
    const me = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me.walletBalance)).toBe(0.0);

    // Auditoria no banco: AUTO_WITHDRAW_SETTINGS_UPDATED (MERCHANT) e
    // AUTO_WITHDRAW_REQUESTED (SYSTEM, sem IP).
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      `SELECT action, actor_type FROM platform_activity_log
       WHERE merchant_id = $1 AND action IN ('AUTO_WITHDRAW_SETTINGS_UPDATED','AUTO_WITHDRAW_REQUESTED')`,
      [merchantId],
    );
    await client.end();
    expect(rows.some((r) => r.action === 'AUTO_WITHDRAW_SETTINGS_UPDATED')).toBe(true);
    expect(rows.some((r) => r.action === 'AUTO_WITHDRAW_REQUESTED' && r.actor_type === 'SYSTEM')).toBe(true);
  });

  test('3. Desativa → nova venda acima do limiar NÃO gera saque', async ({ request }) => {
    // Aguarda ~2 ciclos do job (30s) — precisa de timeout estendido.
    test.setTimeout(150_000);

    const disable = await request.put('/api/v1/portal/auto-withdraw', {
      headers: portalHeaders,
      data: { enabled: false, threshold: 100, destinationId: null },
    });
    expect(disable.status()).toBe(200);
    expect((await disable.json()).enabled).toBe(false);

    // Nova venda 300 → saldo 300 (bem acima do limiar), config desabilitada.
    const charge = await request.post('/api/v1/portal/charges', {
      headers: portalHeaders,
      data: {
        amount: 300.0,
        description: 'Saldo sem auto',
        expirationSeconds: 3600,
        idempotencyKey: `auto-off-${Date.now()}`,
      },
    });
    expect(charge.status()).toBe(201);
    const chargeBody = await charge.json();
    await request.post('/webhooks/pix', {
      data: {
        eventId: `evt-auto-off-${Date.now()}`,
        externalReference: chargeBody.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 300.0,
      },
    });

    // Espera ~2 ciclos do job (cron 30s) → NENHUM PROCESSING novo.
    await sleep(65_000);
    const list = await (await request.get(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const processing = list.filter((w) => w.status === 'PROCESSING');
    expect(processing).toHaveLength(0);
  });
});
