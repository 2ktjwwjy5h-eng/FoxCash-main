// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');

/**
 * Invariantes de CONCORRÊNCIA nas rotas críticas da loja (Spec 49/52) — TDD:
 * propriedades que DEVEM valer sob requests paralelos, verificadas no banco
 * (wallet_ledger, platform_activity_log) além da API:
 *
 *   1. Compra concorrente do MESMO plano com a MESMA idempotencyKey → 1 cobrança.
 *   2. Webhook PAID duplicado/concorrente → 1 crédito SALE_CREDIT (lock da tx).
 *   3. ★ Duas entregas SIMULTÂNEAS para o mesmo usuário → 2 deliveries distintos,
 *        ações NUNCA se misturam, cada entrega DELIVERED no seu próprio fluxo.
 *   4. ★ Replay CONCORRENTE da mesma ação → EXATAMENTE 1 execução (auditoria).
 *        (Ação concluída não pode ser re-executada sob corrida.)
 *   5. Falha parcial entre ações 0→3 → entrega FAILED; retry reabre; DELIVERED.
 *   6. Expiração/revogação do acesso — comportamento atual (append-only).
 *   7. IDOR: lojista B NUNCA acessa entrega do lojista A (404 cross-loja).
 *
 * Requer perfil dev.
 */
test.describe('Invariantes de concorrência — compra, webhook e entrega', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

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

  /** @type {{a: {apiKey: string, planId: string}, b: {apiKey: string}}} */
  const state = { a: { apiKey: '', planId: '' }, b: { apiKey: '' } };

  async function setupMerchant(request, name, email) {
    const register = await request.post('/api/v1/merchants', {
      data: { name, email, password: 'Senha-Concurrency-123' },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password: 'Senha-Concurrency-123' } });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };
    return { merchant, portalHeaders };
  }

  /** Cria loja + plano com 4 ações + activate (retorna planId). */
  async function setupStoreWithPlan(request, portalHeaders) {
    const store = await request.post('/api/v1/portal/store', {
      headers: portalHeaders,
      data: { name: `Loja Conc ${Date.now()}`, description: 'loja concorrencia' },
    });
    expect(store.status()).toBe(201);

    const plan = await request.post('/api/v1/portal/plans', {
      headers: portalHeaders,
      data: { name: 'Plano Concorrente', price: 10.0, description: 'plano', embedColor: '#2ECC71' },
    });
    expect(plan.status()).toBe(201);
    const planId = (await plan.json()).id;

    const actions = [
      { seq: 0, type: 'GRANT_ROLE', payload: { roleId: 'role-c' } },
      { seq: 1, type: 'SEND_MESSAGE', payload: { text: 'oi' } },
      { seq: 2, type: 'ADD_TO_GROUP', payload: { groupId: 'group-c' } },
      { seq: 3, type: 'SEND_FILE', payload: { fileUrl: 'https://example.com/f.pdf' } },
    ];
    for (const action of actions) {
      const added = await request.post(`/api/v1/portal/plans/${planId}/actions`, {
        headers: portalHeaders,
        data: action,
      });
      expect(added.status()).toBe(201);
    }
    const activate = await request.post(`/api/v1/portal/plans/${planId}/activate`, { headers: portalHeaders });
    expect(activate.status()).toBe(204);
    return planId;
  }

  async function buy(request, apiKey, planId, platformUserId, idempotencyKey) {
    const purchase = await request.post(`/api/v1/foxcash/plans/${planId}/purchase`, {
      headers: { 'X-Api-Key': apiKey },
      data: {
        platform: 'DISCORD',
        platformUserId,
        buyerId: 'buyer-conc',
        expirationSeconds: 3600,
        idempotencyKey,
        termsAccepted: true,
      },
    });
    return purchase;
  }

  async function pay(request, cob) {
    return request.post('/webhooks/pix', {
      data: {
        eventId: `evt-conc-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        externalReference: cob.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 10.0,
      },
    });
  }

  const api = (key) => ({ headers: { 'X-Api-Key': key } });

  test('1. Compra concorrente do mesmo plano (mesma idempotencyKey) → 1 cobrança', async ({ request }) => {
    const email = `conc1.${Date.now()}@example.com`;
    const { merchant, portalHeaders } = await setupMerchant(request, 'Loja Conc 1', email);
    const apiKey = merchant.apiKey;
    const planId = await setupStoreWithPlan(request, portalHeaders);

    const key = `conc-same-${Date.now()}`;
    const [r1, r2] = await Promise.all([
      buy(request, apiKey, planId, 'user-1', key),
      buy(request, apiKey, planId, 'user-1', key),
    ]);
    // Sob corrida real o 2º request pode ser 201 (replay da mesma cobrança),
    // 200 ou 409 (chave já produziu resultado) — nunca 5xx.
    for (const r of [r1, r2]) {
      expect([200, 201, 409]).toContain(r.status());
    }

    // INVARIANTE (no banco): a chave produziu EXATAMENTE UMA cobrança — nunca
    // duplica no PSP sob concorrência.
    const txRows = await dbQuery(
      'SELECT count(*)::int AS n FROM pix_transactions WHERE merchant_id = $1',
      [merchant.id],
    );
    expect(txRows[0].n).toBe(1);

    // Paga a cobrança (qualquer resposta válida carrega externalReference) →
    // exatamente 1 entrega criada.
    const cob = r1.status() === 201 || r1.status() === 200 ? await r1.json() : await r2.json();
    const paid = await pay(request, cob);
    expect(paid.status()).toBe(204);
    await expect.poll(async () => {
      const res = await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey));
      return (await res.json()).length;
    }, { timeout: 60_000 }).toBe(1);
  });

  test('2. Webhook PAID concorrente → 1 crédito SALE_CREDIT (lock da transação)', async ({ request }) => {
    const email = `conc2.${Date.now()}@example.com`;
    const { merchant } = await setupMerchant(request, 'Loja Conc 2', email);
    const apiKey = merchant.apiKey;
    const merchantId = merchant.id;

    // Cria cobrança via rota legada (merchantId explícito no body).
    const legado = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: {
        merchantId,
        amount: 10.0,
        buyerId: 'buyer-conc2',
        expirationSeconds: 3600,
        idempotencyKey: `conc2-${Date.now()}`,
      },
    });
    expect(legado.status()).toBe(201);
    const cob = await legado.json();

    // 2 webhooks PAID simultâneos para a MESMA transação.
    const [w1, w2] = await Promise.all([
      request.post('/webhooks/pix', {
        data: { eventId: `evt-conc2a-${Date.now()}`, externalReference: cob.externalReference, status: 'PAID', settledAt: new Date().toISOString(), amount: 10.0 },
      }),
      request.post('/webhooks/pix', {
        data: { eventId: `evt-conc2b-${Date.now()}`, externalReference: cob.externalReference, status: 'PAID', settledAt: new Date().toISOString(), amount: 10.0 },
      }),
    ]);
    expect([w1.status(), w2.status()]).toEqual([204, 204]);

    // Invariante: exatamente 1 SALE_CREDIT no ledger; transação APPROVED.
    await expect.poll(async () => {
      const rows = await dbQuery(
        "SELECT count(*)::int AS n FROM wallet_ledger WHERE merchant_id=$1 AND entry_type='SALE_CREDIT'",
        [merchantId],
      );
      return rows[0].n;
    }, { timeout: 60_000 }).toBe(1);
  });

  test('3. ★ Duas entregas simultâneas para o mesmo usuário → 2 deliveries distintos, sem misturar ações', async ({ request }) => {
    const email = `conc3.${Date.now()}@example.com`;
    const { merchant, portalHeaders } = await setupMerchant(request, 'Loja Conc 3', email);
    const apiKey = merchant.apiKey;
    const planId = await setupStoreWithPlan(request, portalHeaders);

    // Mesmo usuário compra o MESMO plano 2× (chaves distintas) → 2 cobranças/entregas.
    const ts = Date.now();
    const [p1, p2] = await Promise.all([
      buy(request, apiKey, planId, 'user-123', `conc3-a-${ts}`),
      buy(request, apiKey, planId, 'user-123', `conc3-b-${ts}`),
    ]);
    expect(p1.status()).toBe(201);
    expect(p2.status()).toBe(201);
    const cob1 = await p1.json();
    const cob2 = await p2.json();

    // Paga as duas em paralelo → 2 deliveries para o mesmo usuário.
    await Promise.all([pay(request, cob1), pay(request, cob2)]);

    await expect.poll(async () => {
      const res = await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey));
      return (await res.json()).length;
    }, { timeout: 60_000 }).toBe(2);

    const pending = await (await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey))).json();
    const [d1, d2] = pending;
    expect(d1.platformUserId).toBe('user-123');
    expect(d2.platformUserId).toBe('user-123');
    expect(d1.id).not.toBe(d2.id);
    expect(d1.transactionId).not.toBe(d2.transactionId);

    // Bot processa AS DUAS em paralelo (Promise.all) completando cada ação da SUA entrega.
    const completeAll = async (delivery) => {
      for (const action of delivery.actions) {
        const res = await request.post(
          `/api/v1/foxcash/deliveries/${delivery.id}/actions/${action.seq}/complete`,
          { ...api(apiKey), data: { status: 'DONE' } },
        );
        expect(res.status()).toBe(204);
      }
    };
    await Promise.all([completeAll(d1), completeAll(d2)]);

    // Invariante: AMBAS DELIVERED; ações NÃO se misturaram (cada entrega tem as SUAS 4).
    for (const delivery of [d1, d2]) {
      const done = await (await request.get(
        `/api/v1/foxcash/deliveries/by-transaction/${delivery.transactionId}`, api(apiKey),
      )).json();
      expect(done.actions).toHaveLength(4);
      expect(done.actions.every((a) => a.status === 'DONE')).toBe(true);
    }

    // No banco: 2 deliveries, 8 execuções (4 por entrega), cada exec no delivery certo.
    const deliveries = await dbQuery('SELECT count(*)::int AS n FROM plan_deliveries WHERE store_id=(SELECT id FROM stores WHERE merchant_id=$1)', [merchant.id]);
    expect(deliveries[0].n).toBe(2);
    const execs = await dbQuery(
      `SELECT d.id AS delivery_id, count(e.id)::int AS n
         FROM plan_deliveries d JOIN plan_delivery_actions_exec e ON e.delivery_id = d.id
        WHERE d.store_id = (SELECT id FROM stores WHERE merchant_id = $1)
        GROUP BY d.id`,
      [merchant.id],
    );
    expect(execs).toHaveLength(2);
    expect(execs.every((e) => e.n === 4)).toBe(true);
  });

  test('4. ★ Replay concorrente da mesma ação → EXATAMENTE 1 execução (auditoria)', async ({ request }) => {
    const email = `conc4.${Date.now()}@example.com`;
    const { merchant, portalHeaders } = await setupMerchant(request, 'Loja Conc 4', email);
    const apiKey = merchant.apiKey;
    const planId = await setupStoreWithPlan(request, portalHeaders);

    const purchase = await buy(request, apiKey, planId, 'user-4', `conc4-${Date.now()}`);
    expect(purchase.status()).toBe(201);
    const cob = await purchase.json();
    await pay(request, cob);

    await expect.poll(async () => {
      const res = await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey));
      return (await res.json()).length;
    }, { timeout: 60_000 }).toBe(1);
    const delivery = (await (await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey))).json())[0];

    // N=8 completes SIMULTÂNEOS da MESMA ação (seq 0) — o bot não pode re-executar.
    const bursts = Array.from({ length: 8 }, () =>
      request.post(`/api/v1/foxcash/deliveries/${delivery.id}/actions/0/complete`, {
        ...api(apiKey),
        data: { status: 'DONE' },
      }));
    const results = await Promise.all(bursts);
    expect(results.every((r) => r.status() === 204)).toBe(true);

    // INVARIANTE: a ação foi executada UMA vez — 1 registro de auditoria
    // DELIVERY_ACTION_COMPLETED para (deliveryId, seq 0). Execução dupla
    // (gate em memória, sem lock) gravaria 2+ — o teste expõe o bug.
    const audits = await dbQuery(
      "SELECT count(*)::int AS n FROM platform_activity_log WHERE action='DELIVERY_ACTION_COMPLETED' AND entity_id=$1 AND details LIKE '%\"seq\":0%'",
      [delivery.id],
    );
    expect(audits[0].n).toBe(1);

    // A ação terminou DONE uma única vez (executedAt definido, nota consistente).
    const done = await (await request.get(
      `/api/v1/foxcash/deliveries/by-transaction/${delivery.transactionId}`, api(apiKey),
    )).json();
    expect(done.actions.find((a) => a.seq === 0).status).toBe('DONE');
  });

  test('5. Falha parcial entre ações 0→3 → entrega FAILED; retry reabre; DELIVERED', async ({ request }) => {
    const email = `conc5.${Date.now()}@example.com`;
    const { merchant, portalHeaders } = await setupMerchant(request, 'Loja Conc 5', email);
    const apiKey = merchant.apiKey;
    const planId = await setupStoreWithPlan(request, portalHeaders);

    const purchase = await buy(request, apiKey, planId, 'user-5', `conc5-${Date.now()}`);
    const cob = await purchase.json();
    await pay(request, cob);

    await expect.poll(async () => {
      const res = await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey));
      return (await res.json()).length;
    }, { timeout: 60_000 }).toBe(1);
    const delivery = (await (await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey))).json())[0];

    // seq 0 DONE; seq 1 FAILED → entrega vira FAILED (ações 2,3 ficam PENDING).
    await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/actions/0/complete`, {
      ...api(apiKey), data: { status: 'DONE' },
    });
    await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/actions/1/complete`, {
      ...api(apiKey), data: { status: 'FAILED', note: 'discord sem permissao' },
    });
    const failed = await (await request.get(
      `/api/v1/foxcash/deliveries/by-transaction/${delivery.transactionId}`, api(apiKey),
    )).json();
    expect(failed.status).toBe('FAILED');
    expect(failed.actions.find((a) => a.seq === 1).status).toBe('FAILED');
    expect(failed.actions.find((a) => a.seq === 1).note).toBe('discord sem permissao');
    expect(failed.actions.find((a) => a.seq === 0).status).toBe('DONE');
    expect(failed.actions.find((a) => a.seq === 2).status).toBe('PENDING');

    // Retry reabre a ação FAILED → entrega volta PENDING; completa o resto → DELIVERED.
    const retried = await (await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/retry`, api(apiKey))).json();
    expect(retried.actions.find((a) => a.seq === 1).status).toBe('PENDING');
    for (const seq of [1, 2, 3]) {
      const res = await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/actions/${seq}/complete`, {
        ...api(apiKey), data: { status: 'DONE' },
      });
      expect(res.status()).toBe(204);
    }
    const delivered = await (await request.get(
      `/api/v1/foxcash/deliveries/by-transaction/${delivery.transactionId}`, api(apiKey),
    )).json();
    expect(delivered.status).toBe('DELIVERED');
    expect(delivered.deliveredAt).toBeTruthy();
    expect(delivered.actions.every((a) => a.status === 'DONE')).toBe(true);
  });

  test('6. Acesso pós-entrega — append-only, sem revogação (comportamento atual)', async ({ request }) => {
    // Documenta a SEMÂNTICA atual: entregas DELIVERED são finais; o acesso é
    // append-only (accessLogs cresce). NÃO há endpoint de revogação/expiração
    // de acesso hoje — registrado como lacuna para evolução futura.
    const email = `conc6.${Date.now()}@example.com`;
    const { merchant, portalHeaders } = await setupMerchant(request, 'Loja Conc 6', email);
    const apiKey = merchant.apiKey;
    const planId = await setupStoreWithPlan(request, portalHeaders);

    const purchase = await buy(request, apiKey, planId, 'user-6', `conc6-${Date.now()}`);
    const cob = await purchase.json();
    await pay(request, cob);

    await expect.poll(async () => {
      const res = await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey));
      return (await res.json()).length;
    }, { timeout: 60_000 }).toBe(1);
    const delivery = (await (await request.get('/api/v1/foxcash/deliveries/pending', api(apiKey))).json())[0];

    for (const action of delivery.actions) {
      await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/actions/${action.seq}/complete`, {
        ...api(apiKey), data: { status: 'DONE' },
      });
    }
    // Acesso registrado 2× (append-only — cada acesso é um novo log).
    await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/access`, {
      ...api(apiKey), data: { note: 'acesso 1' },
    });
    await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/access`, {
      ...api(apiKey), data: { note: 'acesso 2' },
    });

    const done = await (await request.get(
      `/api/v1/foxcash/deliveries/by-transaction/${delivery.transactionId}`, api(apiKey),
    )).json();
    expect(done.status).toBe('DELIVERED');
    expect(done.accessLogs).toHaveLength(2); // append-only
    expect(done.accessLogs.map((l) => l.note)).toEqual(['acesso 1', 'acesso 2']);
  });

  test('7. IDOR — lojista B NUNCA acessa entrega do lojista A (404 cross-loja)', async ({ request }) => {
    const emailA = `conc7a.${Date.now()}@example.com`;
    const emailB = `conc7b.${Date.now()}@example.com`;
    const a = await setupMerchant(request, 'Loja A', emailA);
    const b = await setupMerchant(request, 'Loja B', emailB);
    state.a.apiKey = a.merchant.apiKey;
    state.b.apiKey = b.merchant.apiKey;

    const planId = await setupStoreWithPlan(request, a.portalHeaders);
    // Loja B TAMBÉM tem loja (mas sem entregas) — isola o acesso cross-loja de
    // verdade: B com loja própria NÃO vê/acessa as entregas de A.
    const storeB = await request.post('/api/v1/portal/store', {
      headers: b.portalHeaders,
      data: { name: `Loja B ${Date.now()}`, description: 'loja B' },
    });
    expect(storeB.status()).toBe(201);

    // Cria a entrega de A.
    const purchase = await buy(request, a.merchant.apiKey, planId, 'user-7', `conc7-${Date.now()}`);
    const cob = await purchase.json();
    await pay(request, cob);
    await expect.poll(async () => {
      const res = await request.get('/api/v1/foxcash/deliveries/pending', api(a.merchant.apiKey));
      return (await res.json()).length;
    }, { timeout: 60_000 }).toBe(1);
    const delivery = (await (await request.get('/api/v1/foxcash/deliveries/pending', api(a.merchant.apiKey))).json())[0];

    // B (outra loja) tenta: listar pendências (não vê as de A), by-transaction, complete, access.
    const pendingB = await request.get('/api/v1/foxcash/deliveries/pending', api(b.merchant.apiKey));
    expect(pendingB.status()).toBe(200);
    expect(await pendingB.json()).toHaveLength(0);

    const byTxB = await request.get(`/api/v1/foxcash/deliveries/by-transaction/${delivery.transactionId}`, api(b.merchant.apiKey));
    expect(byTxB.status()).toBe(404);

    const completeB = await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/actions/0/complete`, {
      ...api(b.merchant.apiKey), data: { status: 'DONE' },
    });
    expect(completeB.status()).toBe(404);

    const accessB = await request.post(`/api/v1/foxcash/deliveries/${delivery.id}/access`, {
      ...api(b.merchant.apiKey), data: { note: 'invasor' },
    });
    expect(accessB.status()).toBe(404);

    // A entrega de A permanece intocada (ação ainda PENDING).
    const still = await (await request.get(
      `/api/v1/foxcash/deliveries/by-transaction/${delivery.transactionId}`, api(a.merchant.apiKey),
    )).json();
    expect(still.actions[0].status).toBe('PENDING');
  });
});
