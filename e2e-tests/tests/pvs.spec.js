// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');

/**
 * Pagamento via Saldo (E2E, Spec 120) — o lojista paga um Pix Copia e Cola
 * ESTÁTICO com o saldo do wallet (destino arbitrário, sem cadastrar destino):
 *
 *   1. Criar lojista + cobrança → webhook PAID (saldo creditado)
 *   2. GET /api/v1/portal/pvs/limits → tetos do dia
 *   3. POST /api/v1/portal/pvs/pay → 200 PROCESSING (débito valor + taxa 2,50)
 *   4. Histórico de saques mostra o BILL_PAYMENT (sem filtro de tipo)
 *   5. Webhook de saída PAID → COMPLETED
 *   6. Erros: chave não suportada / Pix dinâmico / valor divergente / sem valor /
 *      teto por operação / código inválido
 *   7. Webhook de saída FAILED → estorno de valor + taxa
 *
 * Requisitos: app dev com payout mock (aceita e devolve PO<32hex>). O
 * global-setup limpa o banco. EMVs estáticos gerados com CRC-16 CCITT-FALSE.
 */
test.describe('Pagamento via Saldo (PVS) — Pix Copia e Cola com o saldo do wallet', () => {
  test.describe.configure({ mode: 'serial' });

  // Brcodes estáticos (chave CPF 52998224725) gerados com implementação
  // independente (CRC16-CCITT-FALSE).
  const EMV_NO_AMOUNT = '00020126330014br.gov.bcb.pix0111529982247255204000053039865802BR5905TESTE6008BRASILIA630471D8';
  const EMV_WITH_AMOUNT = '00020126330014br.gov.bcb.pix011152998224725520400005303986540510.005802BR5906FULANO6008BRASILIA62070503***6304B756'; // 10.00
  const EMV_BAD_KEY = '00020126370014br.gov.bcb.pix0115nao-e-uma-chave5204000053039865802BR5905TESTE6008BRASILIA630490D6';
  const EMV_DYNAMIC = '00020126410014br.gov.bcb.pix2519pix.example.com/1235204000053039865802BR5905TESTE6008BRASILIA630493A3';

  function dbClient() {
    return new Client({
      host: process.env.DB_HOST || 'localhost',
      port: Number(process.env.DB_PORT || 5432),
      user: process.env.DB_USERNAME || 'nyxcash',
      password: process.env.DB_PASSWORD || 'nyxcash',
      database: process.env.DB_NAME || 'nyxcash',
    });
  }

  /** Cria um lojista com saldo creditado (via cobrança + webhook PAID). */
  async function merchantWithBalance(request, tag, amount) {
    const email = `pvs.${tag}.${Date.now()}@example.com`;
    const password = 'Senha-PVS-123';
    const register = await request.post('/api/v1/merchants', {
      data: { name: `Loja PVS ${tag}`, email, password },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();

    const charge = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': merchant.apiKey },
      data: {
        merchantId: merchant.id,
        amount,
        buyerId: `buyer-pvs-${tag}`,
        expirationSeconds: 3600,
        idempotencyKey: `pvs-cob-${tag}-${Date.now()}`,
      },
    });
    expect(charge.status()).toBe(201);
    const cob = await charge.json();

    const paid = await request.post('/webhooks/pix', {
      data: {
        eventId: `evt-pvs-${tag}-${Date.now()}`,
        externalReference: cob.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount,
      },
    });
    expect(paid.status()).toBe(204);

    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };
    const me = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me.walletBalance)).toBeCloseTo(amount, 2);
    return { merchant, portalHeaders };
  }

  test('29. PVS: pay → PROCESSING → histórico BILL_PAYMENT → webhook PAID → COMPLETED + limits', async ({ request }) => {
    // 29.1 Lojista com saldo 150 (auto-suficiente).
    const { merchant, portalHeaders } = await merchantWithBalance(request, 'ok', 150.0);

    // 29.2 Limits antes: teto diário 5000, usado 0, restante 5000, teto op 1000.
    const limits0 = await (await request.get('/api/v1/portal/pvs/limits', {
      headers: portalHeaders,
    })).json();
    expect(Number(limits0.dailyCap)).toBeCloseTo(5000, 2);
    expect(Number(limits0.usedToday)).toBeCloseTo(0, 2);
    expect(Number(limits0.remaining)).toBeCloseTo(5000, 2);
    expect(Number(limits0.maxPerPayment)).toBeCloseTo(1000, 2);

    // 29.3 Pagar Pix Copia e Cola (sem valor no EMV → amount obrigatório).
    const pay = await request.post('/api/v1/portal/pvs/pay', {
      headers: portalHeaders,
      data: { pixCode: EMV_NO_AMOUNT, amount: 40.0 },
    });
    expect(pay.status()).toBe(200);
    const body = await pay.json();
    expect(body.status).toBe('PROCESSING');
    expect(Number(body.amount)).toBeCloseTo(40, 2);
    expect(Number(body.fee)).toBeCloseTo(2.5, 2);
    // Saldo: 150 − 40 − 2,50 (Spec 88).
    expect(Number(body.walletBalance)).toBeCloseTo(107.5, 2);
    const withdrawalId = body.withdrawalId;

    // 29.4 Limits depois: usado = 42,50 (40 + 2,50), restante = 4957,50.
    const limits1 = await (await request.get('/api/v1/portal/pvs/limits', {
      headers: portalHeaders,
    })).json();
    expect(Number(limits1.usedToday)).toBeCloseTo(42.5, 2);
    expect(Number(limits1.remaining)).toBeCloseTo(4957.5, 2);

    // 29.5 Histórico de saques mostra o BILL_PAYMENT (backend não filtra por tipo).
    const list = await (await request.get(`/api/v1/merchants/${merchant.id}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const pvs = list.find((w) => w.id === withdrawalId);
    expect(pvs).toBeTruthy();
    expect(pvs.type).toBe('BILL_PAYMENT');
    expect(pvs.status).toBe('PROCESSING');
    // Destino arbitrário: chave nunca em claro no response (máscara do snapshot);
    // o destination_id NULL é verificado direto no banco (29.7).
    expect(pvs.destination).toBe('***');

    // 29.6 Webhook de saída PAID → 204 → COMPLETED (débito já ocorreu na solicitação).
    const payout = await request.post('/webhooks/payouts', {
      data: { externalReference: pvs.externalReference, status: 'PAID' },
    });
    expect(payout.status()).toBe(204);

    const list2 = await (await request.get(`/api/v1/merchants/${merchant.id}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const settled = list2.find((w) => w.id === withdrawalId);
    expect(settled.status).toBe('COMPLETED');
    expect(settled.completedAt).toBeTruthy();

    const me = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me.walletBalance)).toBeCloseTo(107.5, 2);

    // 29.7 Banco: BILL_PAYMENT com destination_id NULL e snapshot criptografado
    // (v1:) — o destino arbitrário nunca fica em claro em repouso.
    const client = dbClient();
    await client.connect();
    const { rows: wdRows } = await client.query(
      `SELECT type, destination_id, destination_key_snapshot, status
         FROM withdrawals WHERE id = $1`,
      [withdrawalId],
    );
    expect(wdRows).toHaveLength(1);
    expect(wdRows[0].type).toBe('BILL_PAYMENT');
    expect(wdRows[0].destination_id).toBeNull();
    expect(wdRows[0].destination_key_snapshot).toMatch(/^v1:/);
    expect(wdRows[0].status).toBe('COMPLETED');

    // Ledger: exatamente UMA WITHDRAWAL_DEBIT (valor) + UMA WITHDRAWAL_FEE_DEBIT (taxa).
    const { rows: ledgerRows } = await client.query(
      `SELECT entry_type, COUNT(*)::int AS n FROM wallet_ledger
        WHERE merchant_id = $1 AND reference_type = 'WITHDRAWAL'
        GROUP BY entry_type`,
      [merchant.id],
    );
    const byType = Object.fromEntries(ledgerRows.map((r) => [r.entry_type, r.n]));
    expect(byType.WITHDRAWAL_DEBIT).toBe(1);
    expect(byType.WITHDRAWAL_FEE_DEBIT).toBe(1);
    expect(byType.WITHDRAWAL_REVERSAL).toBeUndefined();
    await client.end();
  });

  test('30. PVS: erros de código/valor/teto + trilha de atividade', async ({ request }) => {
    const { portalHeaders } = await merchantWithBalance(request, 'err', 150.0);

    // 30.1 Chave não suportada → 400 pvs_unsupported_key.
    const badKey = await request.post('/api/v1/portal/pvs/pay', {
      headers: portalHeaders,
      data: { pixCode: EMV_BAD_KEY, amount: 10 },
    });
    expect(badKey.status()).toBe(400);
    expect((await badKey.json()).code).toBe('pvs_unsupported_key');

    // 30.2 Pix dinâmico → 400 pvs_dynamic_pix_not_supported.
    const dynamic = await request.post('/api/v1/portal/pvs/pay', {
      headers: portalHeaders,
      data: { pixCode: EMV_DYNAMIC, amount: 10 },
    });
    expect(dynamic.status()).toBe(400);
    expect((await dynamic.json()).code).toBe('pvs_dynamic_pix_not_supported');

    // 30.3 EMV com valor 10.00 + amount 20 → 400 pvs_amount_mismatch (nunca cobrar
    // um valor diferente do que o brcode embute).
    const mismatch = await request.post('/api/v1/portal/pvs/pay', {
      headers: portalHeaders,
      data: { pixCode: EMV_WITH_AMOUNT, amount: 20 },
    });
    expect(mismatch.status()).toBe(400);
    expect((await mismatch.json()).code).toBe('pvs_amount_mismatch');

    // 30.4 EMV sem valor + amount ausente → 400 pvs_amount_required.
    const missing = await request.post('/api/v1/portal/pvs/pay', {
      headers: portalHeaders,
      data: { pixCode: EMV_NO_AMOUNT },
    });
    expect(missing.status()).toBe(400);
    expect((await missing.json()).code).toBe('pvs_amount_required');

    // 30.5 Teto por operação (1000 + 2,50 > 1000) → 409 pvs_max_per_payment_exceeded.
    const overCap = await request.post('/api/v1/portal/pvs/pay', {
      headers: portalHeaders,
      data: { pixCode: EMV_NO_AMOUNT, amount: 1000.0 },
    });
    expect(overCap.status()).toBe(409);
    expect((await overCap.json()).code).toBe('pvs_max_per_payment_exceeded');

    // 30.6 Código que não é um EMV → 400 pvs_invalid_code.
    const garbage = await request.post('/api/v1/portal/pvs/pay', {
      headers: portalHeaders,
      data: { pixCode: 'nao-e-um-brcode', amount: 10 },
    });
    expect(garbage.status()).toBe(400);
    expect((await garbage.json()).code).toBe('pvs_invalid_code');

    // 30.7 Nenhum PVS foi criado (todos os casos acima falharam antes da Fase A).
    const list = await (await request.get('/api/v1/portal/pvs/limits', {
      headers: portalHeaders,
    })).json();
    expect(Number(list.usedToday)).toBeCloseTo(0, 2);
  });

  test('31. PVS: webhook FAILED → estorno de valor + taxa (wallet restaurado)', async ({ request }) => {
    const { merchant, portalHeaders } = await merchantWithBalance(request, 'fail', 100.0);

    const pay = await request.post('/api/v1/portal/pvs/pay', {
      headers: portalHeaders,
      data: { pixCode: EMV_WITH_AMOUNT, amount: 10.0 }, // EMV 10.00 → bate
    });
    expect(pay.status()).toBe(200);
    const body = await pay.json();
    expect(Number(body.walletBalance)).toBeCloseTo(87.5, 2); // 100 − 10 − 2,50
    const withdrawalId = body.withdrawalId;

    const list0 = await (await request.get(`/api/v1/merchants/${merchant.id}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const pending = list0.find((w) => w.id === withdrawalId);
    expect(pending.status).toBe('PROCESSING');

    // Webhook de saída FAILED → 204 → estorno automático (valor + taxa, Spec 88).
    const fail = await request.post('/webhooks/payouts', {
      data: { externalReference: pending.externalReference, status: 'FAILED', failedReason: 'Falha simulada no PSP' },
    });
    expect(fail.status()).toBe(204);

    const list = await (await request.get(`/api/v1/merchants/${merchant.id}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const failed = list.find((w) => w.id === withdrawalId);
    expect(failed.status).toBe('FAILED');
    expect(failed.failedAt).toBeTruthy();

    // Wallet restaurado: 100,00 (débito de valor + taxa devolvido integralmente).
    const me = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me.walletBalance)).toBeCloseTo(100.0, 2);

    // Ledger: WITHDRAWAL_REVERSAL (valor) + WITHDRAWAL_FEE_REVERSAL (taxa).
    const client = dbClient();
    await client.connect();
    const { rows: ledgerRows } = await client.query(
      `SELECT entry_type, COUNT(*)::int AS n FROM wallet_ledger
        WHERE merchant_id = $1 AND reference_type = 'WITHDRAWAL'
        GROUP BY entry_type`,
      [merchant.id],
    );
    const byType = Object.fromEntries(ledgerRows.map((r) => [r.entry_type, r.n]));
    expect(byType.WITHDRAWAL_DEBIT).toBe(1);
    expect(byType.WITHDRAWAL_REVERSAL).toBe(1);
    expect(byType.WITHDRAWAL_FEE_DEBIT).toBe(1);
    expect(byType.WITHDRAWAL_FEE_REVERSAL).toBe(1);
    await client.end();
  });
});
