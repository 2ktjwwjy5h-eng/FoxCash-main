// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');

/**
 * Teste de CONFIABILIDADE do mecanismo de reconciliação financeira (Spec 30).
 *
 * Prova o ciclo completo de detecção e correção de inconsistência no saldo do
 * wallet:
 *
 *   1. Criar cenário VÁLIDO (venda + saque)          → ledger e saldo consistentes
 *   2. Corromper wallet_balance no banco (proposital) → saldo ≠ ledger
 *   3. Executar o Reconciliation Job                  → detecta a divergência
 *   4. Conferir a issue                               → saldo esperado (ledger),
 *                                                       saldo atual (corrompido),
 *                                                       diferença
 *   5. Corrigir o wallet_balance (fonte da verdade)   → banco volta ao estado correto
 *   6. Executar o job novamente                       → divergência zerada
 *
 * O que se prova: o sistema DETECTA corrupção de dados, o LEDGER é a fonte da
 * verdade, o saldo pode ser reconciliado com segurança e o processo de auditoria
 * (issue OPEN registrada) funciona.
 *
 * NOTA (design do Spec 30): a correção automática está FORA de escopo — o job
 * registra a issue e o master corrige (aqui, o mesmo UPDATE que corrompeu,
 * revertido ao valor do ledger). A issue OPEN permanece como trilha de auditoria
 * (idempotente — re-execução não duplica).
 *
 * Requisitos: aplicação rodando (default http://localhost:8080) + PostgreSQL.
 * O global-setup limpa o banco. Requer perfil dev (provider de payout mock).
 */
test.describe('Confiabilidade da reconciliação (Spec 30) — detectar, inspecionar, corrigir e confirmar', () => {
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

  test('29. Reconciliação: cenário válido → corrupção → job detecta 1 issue (esperado/atual/diferença) → correção → 0 divergências', async ({ request }) => {
    // 29.1 Lojista próprio (cenário auto-suficiente).
    const email = `recon.e2e.${Date.now()}@example.com`;
    const password = 'Senha-RECON-123';
    const register = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Recon E2E', email, password },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    const merchantId = merchant.id;
    const apiKey = merchant.apiKey;
    expect(apiKey).toMatch(/^mch_[0-9a-f]{32}$/);

    // 29.2 Master via SQL (mesmo padrão dos cenários 13/14/18) — o endpoint de
    // reconciliação exige ROLE_MASTER.
    const masterRawToken = 'mst_' + nodeCrypto.randomBytes(16).toString('hex');
    const masterId = nodeCrypto.randomUUID();
    const client0 = dbClient();
    await client0.connect();
    await client0.query(
      'INSERT INTO masters (id, username, password_hash, name, active, created_at) VALUES ($1,$2,$3,$4,true,$5)',
      [masterId, `recon-master-${Date.now()}`, 'x'.repeat(60), 'Master Recon', new Date().toISOString()],
    );
    await client0.query(
      'INSERT INTO master_sessions (id, master_id, token_hash, created_at, expires_at, revoked_at) VALUES ($1,$2,$3,$4,$5,NULL)',
      [
        nodeCrypto.randomUUID(),
        masterId,
        nodeCrypto.createHash('sha256').update(masterRawToken).digest('hex'),
        new Date().toISOString(),
        new Date(Date.now() + 3600_000).toISOString(),
      ],
    );
    await client0.end();
    const masterHeaders = { Authorization: `Bearer ${masterRawToken}` };

    // 29.3 CENÁRIO VÁLIDO: venda de 150 aprovada (SALE_CREDIT) + saque de 30
    // (WITHDRAWAL_DEBIT + taxa 2,50 Spec 88) → saldo esperado 117,50, ledger e
    // saldo consistentes.
    const charge = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: {
        merchantId,
        amount: 150.0,
        buyerId: 'buyer-recon-001',
        expirationSeconds: 3600,
        idempotencyKey: `recon-cob-${Date.now()}`,
      },
    });
    expect(charge.status()).toBe(201);
    const cob = await charge.json();
    const paid = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-recon-1',
        externalReference: cob.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 150.0,
      },
    });
    expect(paid.status()).toBe(204);

    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    const dest = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_CPF', value: '52998224725', label: 'Conta Recon' },
    });
    expect(dest.status()).toBe(201);
    const destinationId = (await dest.json()).id;

    const withdrawal = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 30.0, destinationId },
    });
    expect(withdrawal.status()).toBe(201);
    const wd = await withdrawal.json();
    expect(wd.status).toBe('PROCESSING');

    const payout = await request.post('/webhooks/payouts', {
      data: { externalReference: wd.externalReference, status: 'PAID' },
    });
    expect(payout.status()).toBe(204);

    // 29.4 BASELINE: cenário válido → job NÃO encontra divergência para o lojista.
    const baselineRun = await (await request.post('/api/v1/master/reconciliation/run', {
      headers: masterHeaders,
    })).json();
    expect(baselineRun.checked).toBeGreaterThanOrEqual(1);
    const baselineIssues = await request.get(
      `/api/v1/master/reconciliation/issues?merchantId=${merchantId}&status=OPEN`,
      { headers: masterHeaders },
    );
    expect(baselineIssues.status()).toBe(200);
    expect((await baselineIssues.json()).items).toHaveLength(0);

    // 29.5 CORROMPER o wallet_balance no banco (UPDATE direto, sem tocar o ledger)
    // — simula bug/erro operacional/alteração indevida. Saldo 117,50 → 617,50.
    const client1 = dbClient();
    await client1.connect();
    await client1.query('UPDATE merchants SET wallet_balance = wallet_balance + 500.00 WHERE id = $1', [merchantId]);
    await client1.end();

    // 29.6 EXECUTAR O JOB → detecta a divergência (+1 issue em relação ao baseline).
    const afterCorrupt = await (await request.post('/api/v1/master/reconciliation/run', {
      headers: masterHeaders,
    })).json();
    expect(afterCorrupt.issues).toBe(baselineRun.issues + 1);
    expect(afterCorrupt.ok).toBe(afterCorrupt.checked - afterCorrupt.issues);

    // 29.7 CONFERIR A ISSUE: saldo esperado (ledger = 117,50), saldo atual (617,50) e
    // diferença (500) — o ledger é a fonte da verdade.
    const issuesRes = await request.get(
      `/api/v1/master/reconciliation/issues?merchantId=${merchantId}&status=OPEN`,
      { headers: masterHeaders },
    );
    expect(issuesRes.status()).toBe(200);
    const items = (await issuesRes.json()).items;
    expect(items).toHaveLength(1);
    const issue = items[0];
    expect(issue.merchantId).toBe(merchantId);
    expect(issue.status).toBe('OPEN');
    expect(Number(issue.expectedBalance)).toBeCloseTo(117.50, 2); // ledger (150 − 30 − taxa 2,50)
    expect(Number(issue.actualBalance)).toBeCloseTo(617.50, 2); // corrompido (+500)
    expect(Number(issue.actualBalance) - Number(issue.expectedBalance)).toBeCloseTo(500, 2); // diferença
    expect(issue.detectedAt).toBeTruthy();
    expect(issue.resolvedAt).toBeNull();

    // Ledger confirma: balance_after da última entry = 117,50 (fonte da verdade).
    const client2 = dbClient();
    await client2.connect();
    const { rows: ledgerRows } = await client2.query(
      'SELECT balance_after FROM wallet_ledger WHERE merchant_id = $1 ORDER BY created_at DESC, id DESC LIMIT 1',
      [merchantId],
    );
    expect(ledgerRows).toHaveLength(1);
    expect(Number(ledgerRows[0].balance_after)).toBeCloseTo(117.50, 2); // 150 − 30 − taxa 2,50 (Spec 88)

    // 29.8 CORRIGIR o wallet_balance para o valor do ledger (fonte da verdade) —
    // o mecanismo de reparo é o próprio dado correto (Spec 30: correção manual
    // pelo master; o job não corrige sozinho por design).
    await client2.query('UPDATE merchants SET wallet_balance = $2 WHERE id = $1', [
      merchantId,
      Number(ledgerRows[0].balance_after),
    ]);
    const { rows: fixedRow } = await client2.query(
      'SELECT wallet_balance FROM merchants WHERE id = $1',
      [merchantId],
    );
    expect(Number(fixedRow[0].wallet_balance)).toBeCloseTo(117.50, 2); // 150 − 30 − taxa 2,50 (Spec 88)
    await client2.end();

    // 29.9 EXECUTAR O JOB NOVAMENTE → a divergência do nosso lojista zerou. O
    // total de issues volta ao BASELINE (que pode ser > 0 se outro cenário da
    // suíte deixou uma divergência própria — ex.: cenário 18 corrompe o lojista
    // compartilhado da jornada; o que importa é o DELTA do nosso lojista).
    const afterFix = await (await request.post('/api/v1/master/reconciliation/run', {
      headers: masterHeaders,
    })).json();
    expect(afterFix.issues).toBe(baselineRun.issues);
    expect(afterFix.ok).toBe(afterFix.checked - afterFix.issues);

    // Nosso lojista, especificamente, não tem mais divergência de saldo no banco.
    const client3 = dbClient();
    await client3.connect();
    const { rows: consistentRow } = await client3.query(
      'SELECT wallet_balance FROM merchants WHERE id = $1',
      [merchantId],
    );
    const { rows: ledgerCheck } = await client3.query(
      'SELECT balance_after FROM wallet_ledger WHERE merchant_id = $1 ORDER BY created_at DESC, id DESC LIMIT 1',
      [merchantId],
    );
    expect(Number(consistentRow[0].wallet_balance)).toBeCloseTo(Number(ledgerCheck[0].balance_after), 2);
    await client3.end();

    // 29.10 A issue OPEN permanece como trilha de auditoria (não duplica — o job
    // é idempotente e a correção não gera reabertura). Re-executar não muda nada.
    const issuesAfterFix = await request.get(
      `/api/v1/master/reconciliation/issues?merchantId=${merchantId}`,
      { headers: masterHeaders },
    );
    expect(issuesAfterFix.status()).toBe(200);
    const afterFixItems = (await issuesAfterFix.json()).items;
    expect(afterFixItems).toHaveLength(1);
    expect(afterFixItems[0].status).toBe('OPEN');
    expect(afterFixItems[0].detectedAt).toBe(issue.detectedAt);
  });
});
