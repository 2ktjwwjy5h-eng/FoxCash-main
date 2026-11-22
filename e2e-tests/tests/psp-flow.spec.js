// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');

/**
 * Fluxo PSP completo (E2E) — o ciclo fechado de um provedor de pagamentos Pix
 * (Spec 10/11 + Spec 36):
 *
 *   1. Criar cobrança            → 201 com QR Code (pixQrCode + pixCopyPaste)
 *   2. Pagar via webhook         → POST /webhooks/pix PAID → 204
 *   3. Transação → APPROVED      (saldo creditado SALE_CREDIT)
 *   4. Criar saque (com destino) → 201 PROCESSING (débito imediato)
 *   5. Webhook de saída          → POST /webhooks/payouts PAID → 204 → COMPLETED
 *   6. Webhook duplicado         → 204 sem efeitos (idempotência do settlement)
 *
 * O cenário é AUTO-SUFICIENTE (cria o próprio lojista e destino) e termina com a
 * asserção-chave do fluxo: re-entrega do webhook de saída → 204 e NENHUM efeito
 * colateral (status permanece COMPLETED, saldo inalterado, ledger sem duplicidade,
 * sem estorno WITHDRAWAL_REVERSAL).
 *
 * Requisitos: aplicação rodando (default http://localhost:8080) e PostgreSQL
 * acessível (mesmas credenciais do application.yml). O global-setup limpa o banco.
 * Requer o perfil dev (provider de payout = mock, que aceita e devolve PO<32hex>).
 */
test.describe('Fluxo PSP completo — cobrança → APPROVED → saque → COMPLETED → webhook duplicado 204', () => {
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

  test('28. PSP: cobrança → QR → webhook PAID → APPROVED → saque → PROCESSING → COMPLETED → webhook duplicado → 204 sem efeitos', async ({ request }) => {
    // 28.1 Lojista próprio (cenário auto-suficiente — não depende do estado da
    // jornada do lojista). E-mail único para permitir re-execução da suíte.
    const email = `psp.e2e.${Date.now()}@example.com`;
    const password = 'Senha-PSP-123';
    const register = await request.post('/api/v1/merchants', {
      data: { name: 'Loja PSP E2E', email, password },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    const merchantId = merchant.id;
    const apiKey = merchant.apiKey;
    expect(apiKey).toMatch(/^mch_[0-9a-f]{32}$/);

    // 28.2 Criar cobrança → 201 com QR Code (Pix Copia e Cola BR Code + QR base64).
    const idempotencyKey = `psp-cob-${Date.now()}`;
    const charge = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: {
        merchantId,
        amount: 150.0,
        buyerId: 'buyer-psp-001',
        expirationSeconds: 3600,
        idempotencyKey,
      },
    });
    expect(charge.status()).toBe(201);
    const cob = await charge.json();
    expect(cob.transactionId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(cob.externalReference).toMatch(/^E[0-9A-F]{32}$/);
    expect(cob.pixQrCode).toBeTruthy();
    expect(cob.pixCopyPaste).toContain('000201');
    expect(cob.status).toBe('PENDING');
    const transactionId = cob.transactionId;
    const externalReference = cob.externalReference;

    // 28.3 Pagar via webhook (PAID) → 204; transação → APPROVED; saldo creditado.
    const paid = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-psp-1',
        externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 150.0,
      },
    });
    expect(paid.status()).toBe(204);

    const tx = await (await request.get(`/api/v1/transactions/${transactionId}`, {
      headers: { 'X-Api-Key': apiKey },
    })).json();
    expect(tx.status).toBe('APPROVED');
    expect(tx.paidAt).toBeTruthy();

    // Saldo = 150 (SALE_CREDIT) — base para o saque.
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };
    const me0 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me0.walletBalance)).toBeCloseTo(150, 2);

    // 28.4 Destino de saque ativo (Spec 31) — necessário para o saque (Spec 32).
    const dest = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_CPF', value: '52998224725', label: 'Conta PSP' },
    });
    expect(dest.status()).toBe(201);
    const destBody = await dest.json();
    expect(destBody.status).toBe('ACTIVE');
    const destinationId = destBody.id;

    // 28.5 Criar saque → 201 PROCESSING com externalReference PO<32hex>; débito
    // imediato (saldo 150 → 120). O PSP (mock) aceita e devolve a referência.
    const withdrawal = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 30.0, destinationId },
    });
    expect(withdrawal.status()).toBe(201);
    const wd = await withdrawal.json();
    expect(wd.status).toBe('PROCESSING');
    expect(wd.externalReference).toMatch(/^PO[0-9A-F]{32}$/);
    expect(Number(wd.walletBalance)).toBeCloseTo(117.50, 2); // 150 − 30 − taxa 2,50 (Spec 88)
    const payoutRef = wd.externalReference;

    // 28.6 Webhook de saída PAID → 204; saque → COMPLETED (saldo inalterado — o
    // débito já ocorreu na solicitação). Sem estorno.
    const payout = await request.post('/webhooks/payouts', {
      data: { externalReference: payoutRef, status: 'PAID' },
    });
    expect(payout.status()).toBe(204);

    const list = await (await request.get(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const settled = list.find((w) => w.id === wd.id);
    expect(settled).toBeTruthy();
    expect(settled.status).toBe('COMPLETED');
    expect(settled.completedAt).toBeTruthy();

    const me1 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me1.walletBalance)).toBeCloseTo(117.50, 2);

    // 28.7 WEBHOOK DUPLICADO (re-entrega do mesmo PAID do payout) → 204 SEM
    // EFEITOS: idempotência do WithdrawalSettlementService (gate PROCESSING).
    const replay = await request.post('/webhooks/payouts', {
      data: { externalReference: payoutRef, status: 'PAID' },
    });
    expect(replay.status()).toBe(204);

    // 28.7a Status permanece COMPLETED (não re-processa nem estorna).
    const list2 = await (await request.get(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const afterReplay = list2.find((w) => w.id === wd.id);
    expect(afterReplay.status).toBe('COMPLETED');

    // 28.7b Saldo inalterado (117.50) — replay não gera segundo débito nem estorno.
    const me2 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me2.walletBalance)).toBeCloseTo(117.50, 2);

    // 28.7c Ledger SEM duplicidade: exatamente UMA entrada WITHDRAWAL_DEBIT e ZERO
    // WITHDRAWAL_REVERSAL (o replay idempotente não toca o financeiro).
    const client = dbClient();
    await client.connect();
    const { rows: ledgerRows } = await client.query(
      `SELECT entry_type, COUNT(*)::int AS n
         FROM wallet_ledger
        WHERE merchant_id = $1 AND reference_type = 'WITHDRAWAL'
        GROUP BY entry_type`,
      [merchantId],
    );
    const byType = Object.fromEntries(ledgerRows.map((r) => [r.entry_type, r.n]));
    expect(byType.WITHDRAWAL_DEBIT).toBe(1);
    expect(byType.WITHDRAWAL_FEE_DEBIT).toBe(1); // Spec 88: taxa de saque registrada
    expect(byType.WITHDRAWAL_REVERSAL).toBeUndefined();

    // 28.7d Banco: saque único PROCESSING→COMPLETED, sem entradas fantasma.
    const { rows: wdRows } = await client.query(
      'SELECT status, completed_at FROM withdrawals WHERE id = $1',
      [wd.id],
    );
    expect(wdRows).toHaveLength(1);
    expect(wdRows[0].status).toBe('COMPLETED');
    expect(wdRows[0].completed_at).toBeTruthy();
    await client.end();

    // 28.8 Webhook duplicado da COBRANÇA (replay PAID da transação já APPROVED)
    // → também 204 sem efeitos (idempotência do ProcessPixWebhookService — o
    // crédito SALE_CREDIT só ocorre uma vez).
    const replayCharge = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-psp-1-replay',
        externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 150.0,
      },
    });
    expect(replayCharge.status()).toBe(204);

    const txAfterReplay = await (await request.get(`/api/v1/transactions/${transactionId}`, {
      headers: { 'X-Api-Key': apiKey },
    })).json();
    expect(txAfterReplay.status).toBe('APPROVED');

    const me3 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me3.walletBalance)).toBeCloseTo(117.50, 2); // sem segundo crédito

    const client2 = dbClient();
    await client2.connect();
    const { rows: saleCredits } = await client2.query(
      `SELECT COUNT(*)::int AS n
         FROM wallet_ledger
        WHERE merchant_id = $1 AND entry_type = 'SALE_CREDIT' AND reference_type = 'TRANSACTION'`,
      [merchantId],
    );
    expect(saleCredits[0].n).toBe(1); // exatamente UMA entrada SALE_CREDIT
    await client2.end();
  });
});
