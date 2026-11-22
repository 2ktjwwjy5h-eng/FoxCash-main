// @ts-check
const { test, expect } = require('@playwright/test');
const http = require('http');

/**
 * Portal do lojista (backend-first — a API que o frontend da Spec 46 consumirá):
 * jornada E2E das 10 seções contra /api/v1/portal/** + /api/v1/merchant-auth.
 *
 *   Ordem funcional (cria dados antes de ler):
 *   1. Login      — cadastro do lojista + login + /me (sessão ativa)
 *   2. Cobranças  — cobrança avulsa + Gift PIX + pagar via webhook (saldo sobe)
 *   3. Carteira   — saldo, resumo mensal, entradas da semana, fluxo de caixa, ledger
 *   4. Dashboard  — summary, série de receita, vendas recentes, pontos de atenção
 *   5. API Keys   — listar, criar (secret 1×), revogar
 *   6. Webhooks   — CRUD + teste de entrega (receptor local)
 *   7. Store      — criar, ler, atualizar a loja
 *   8. Planos     — CRUD de plano + ação de entrega + ativar
 *   9. Mensagens  — upsert por etapa + agendar envio em massa
 *   10. Logout    — sessão revogada (token antigo → 401)
 *
 * Requer perfil dev (FOXCASH_NOTIFICATION_ALLOW_LOOPBACK=true para o webhook local).
 */
test.describe('Portal do lojista — as 10 seções funcionando (backend-first)', () => {
  test.describe.configure({ mode: 'serial' });

  /** @type {{merchantId: string, apiKey: string, portalHeaders: Record<string,string>}} */
  const state = { merchantId: '', apiKey: '', portalHeaders: {} };

  test('1. Login — cadastro, login e sessão (GET /api/v1/portal/me)', async ({ request }) => {
    const email = `portal.e2e.${Date.now()}@example.com`;
    const password = 'Senha-Portal-123';
    const register = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Portal E2E', email, password },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    state.merchantId = merchant.id;
    state.apiKey = merchant.apiKey;
    expect(merchant.apiKey).toMatch(/^mch_[0-9a-f]{32}$/);

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password } });
    expect(login.status()).toBe(200);
    const { token } = await login.json();
    expect(token).toBeTruthy();
    state.portalHeaders = { Authorization: `Bearer ${token}` };

    // Sessão ativa: /me devolve o perfil do lojista logado.
    const me = await request.get('/api/v1/portal/me', { headers: state.portalHeaders });
    expect(me.status()).toBe(200);
    const meBody = await me.json();
    expect(meBody.merchantId).toBe(state.merchantId);
    expect(meBody.name).toBe('Loja Portal E2E');
    expect(meBody.email).toBe(email);
    expect(Number(meBody.walletBalance)).toBe(0);
  });

  test('2. Cobranças — avulsa + Gift PIX + pagamento (saldo sobe)', async ({ request }) => {
    // 2.1 Cobrança avulsa → 201 com QR (Pix Copia e Cola).
    const charge = await request.post('/api/v1/portal/charges', {
      headers: state.portalHeaders,
      data: {
        amount: 50.0,
        description: 'Cobrança portal E2E',
        expirationSeconds: 3600,
        buyerId: 'buyer-portal-1',
        idempotencyKey: `portal-cob-${Date.now()}`,
      },
    });
    expect(charge.status()).toBe(201);
    const cob = await charge.json();
    expect(cob.status).toBe('PENDING');
    expect(cob.pixCopyPaste).toContain('000201');
    const transactionId = cob.transactionId;

    // 2.2 Gift PIX personalizado → 201.
    const gift = await request.post('/api/v1/portal/charges/gift', {
      headers: state.portalHeaders,
      data: {
        amount: 25.0,
        expirationSeconds: 3600,
        buyerId: 'buyer-portal-gift',
        idempotencyKey: `portal-gift-${Date.now()}`,
      },
    });
    expect(gift.status()).toBe(201);
    expect((await gift.json()).status).toBe('PENDING');

    // 2.3 Catálogo de presets (leitura).
    const presets = await request.get('/api/v1/portal/charges/gift-presets', {
      headers: state.portalHeaders,
    });
    expect(presets.status()).toBe(200);
    expect(Array.isArray(await presets.json())).toBe(true);

    // 2.4 Paga a cobrança avulsa via webhook do PSP → saldo 50.
    const paid = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-portal-1',
        externalReference: cob.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 50.0,
      },
    });
    expect(paid.status()).toBe(204);

    const me = await (await request.get('/api/v1/portal/me', { headers: state.portalHeaders })).json();
    expect(Number(me.walletBalance)).toBeCloseTo(50, 2);

    // 2.5 A venda aparece nas vendas (Spec 25) — provê o dashboard.
    const sales = await request.get(`/api/v1/portal/sales/${transactionId}`, {
      headers: state.portalHeaders,
    });
    expect(sales.status()).toBe(200);
    expect((await sales.json()).status).toBe('APPROVED');
  });

  test('3. Carteira — saldo, resumo, entradas, fluxo de caixa e ledger', async ({ request }) => {
    const balance = await request.get('/api/v1/portal/wallet/balance', { headers: state.portalHeaders });
    expect(balance.status()).toBe(200);
    expect(Number((await balance.json()).walletBalance)).toBeCloseTo(50, 2);

    const month = new Date().toISOString().slice(0, 7); // YYYY-MM (UTC)
    const monthly = await request.get(`/api/v1/portal/wallet/monthly-summary?month=${month}`, {
      headers: state.portalHeaders,
    });
    expect(monthly.status()).toBe(200);
    const monthlyBody = await monthly.json();
    expect(Number(monthlyBody.grossRevenue)).toBeCloseTo(50, 2);
    expect(monthlyBody.transactionCount).toBe(1);

    const weekly = await request.get('/api/v1/portal/wallet/weekly-inflows', { headers: state.portalHeaders });
    expect(weekly.status()).toBe(200);
    expect(Array.isArray(await weekly.json())).toBe(true);

    const cashFlow = await request.get('/api/v1/portal/wallet/cash-flow', { headers: state.portalHeaders });
    expect(cashFlow.status()).toBe(200);

    // Ledger (Spec 27): o SALE_CREDIT da venda está registrado.
    const ledger = await request.get('/api/v1/portal/wallet/ledger', { headers: state.portalHeaders });
    expect(ledger.status()).toBe(200);
    const ledgerBody = await ledger.json();
    expect(Array.isArray(ledgerBody.items)).toBe(true);
    const credit = ledgerBody.items.find((e) => e.type === 'SALE_CREDIT');
    expect(credit).toBeTruthy();
    expect(Number(credit.amount)).toBeCloseTo(50, 2);
  });

  test('4. Dashboard — summary, série de receita, vendas recentes, atenção', async ({ request }) => {
    const summary = await request.get('/api/v1/portal/dashboard/summary', { headers: state.portalHeaders });
    expect(summary.status()).toBe(200);
    const s = await summary.json();
    expect(Number(s.grossRevenue)).toBeCloseTo(50, 2);
    expect(s.sales).toBe(1);

    const revenue = await request.get('/api/v1/portal/dashboard/revenue-series', {
      headers: state.portalHeaders,
    });
    expect(revenue.status()).toBe(200);
    expect(Array.isArray(await revenue.json())).toBe(true);

    const recent = await request.get('/api/v1/portal/dashboard/recent-sales?limit=5', {
      headers: state.portalHeaders,
    });
    expect(recent.status()).toBe(200);
    expect((await recent.json()).length).toBeGreaterThanOrEqual(1);

    const attention = await request.get('/api/v1/portal/dashboard/attention-points', {
      headers: state.portalHeaders,
    });
    expect(attention.status()).toBe(200);
    expect(Array.isArray(await attention.json())).toBe(true);
  });

  test('5. API Keys — listar, criar (secret 1×) e revogar', async ({ request }) => {
    const list0 = await (await request.get('/api/v1/portal/credentials', { headers: state.portalHeaders })).json();
    const before = list0.length;

    const create = await request.post('/api/v1/portal/credentials', {
      headers: state.portalHeaders,
      data: { label: 'Chave portal E2E', scopes: ['transactions'] },
    });
    expect(create.status()).toBe(201);
    const key = await create.json();
    expect(key.id).toBeTruthy();
    expect(key.secret).toBeTruthy(); // secret devolvido UMA vez

    const list1 = await (await request.get('/api/v1/portal/credentials', { headers: state.portalHeaders })).json();
    expect(list1.length).toBe(before + 1);
    expect(list1.find((k) => k.id === key.id)).toBeTruthy();

    const revoke = await request.post(`/api/v1/portal/credentials/${key.id}/revoke`, {
      headers: state.portalHeaders,
    });
    expect(revoke.status()).toBe(204);
    const list2 = await (await request.get('/api/v1/portal/credentials', { headers: state.portalHeaders })).json();
    const revoked = list2.find((k) => k.id === key.id);
    expect(revoked).toBeTruthy();
    expect(revoked.status).toBe('REVOKED'); // permanece na lista para auditoria
  });

  test('6. Webhooks — CRUD + teste de entrega (receptor local)', async ({ request }) => {
    const receiver = http.createServer((req, res) => {
      req.resume();
      res.statusCode = 200;
      res.end();
    });
    await new Promise((resolve) => receiver.listen(0, '127.0.0.1', resolve));
    const webhookUrl = `http://127.0.0.1:${receiver.address().port}/hook`;
    try {
      const created = await request.post('/api/v1/portal/webhooks', {
        headers: state.portalHeaders,
        data: { url: webhookUrl, events: ['SALE_PAID'] },
      });
      expect(created.status()).toBe(201);
      const webhook = await created.json();
      expect(webhook.secret).toMatch(/^[0-9a-f]{64}$/);
      expect(webhook.active).toBe(true);
      expect(webhook.events).toEqual(['SALE_PAID']);

      const list = await request.get('/api/v1/portal/webhooks', { headers: state.portalHeaders });
      expect(list.status()).toBe(200);
      expect((await list.json()).some((w) => w.id === webhook.id)).toBe(true);

      const test = await request.post('/api/v1/portal/webhooks/test', {
        headers: state.portalHeaders,
        data: { url: webhookUrl, secret: webhook.secret, eventType: 'SALE_PAID' },
      });
      expect(test.status()).toBe(200);
      expect((await test.json()).delivered).toBe(true);

      const deactivate = await request.post(`/api/v1/portal/webhooks/${webhook.id}/deactivate`, {
        headers: state.portalHeaders,
      });
      expect(deactivate.status()).toBe(204);
    } finally {
      receiver.closeAllConnections();
      await new Promise((resolve) => receiver.close(resolve));
    }
  });

  test('7. Store — criar, ler e atualizar a loja', async ({ request }) => {
    const create = await request.post('/api/v1/portal/store', {
      headers: state.portalHeaders,
      data: {
        name: 'Minha Loja',
        description: 'Loja E2E do portal',
        color: '#8E44AD',
        emoji: '🛍️',
        title: 'Bem-vindo à Minha Loja!',
        footer: 'Obrigado pela compra!',
      },
    });
    expect(create.status()).toBe(201);
    const store = await create.json();
    expect(store.id).toBeTruthy();
    expect(store.name).toBe('Minha Loja');

    const get = await request.get('/api/v1/portal/store', { headers: state.portalHeaders });
    expect(get.status()).toBe(200);
    expect((await get.json()).id).toBe(store.id);

    const update = await request.patch('/api/v1/portal/store', {
      headers: state.portalHeaders,
      data: { name: 'Minha Loja v2' },
    });
    expect(update.status()).toBe(200);
    expect((await update.json()).name).toBe('Minha Loja v2');
  });

  test('8. Planos — CRUD + ação de entrega + ativar', async ({ request }) => {
    const create = await request.post('/api/v1/portal/plans', {
      headers: state.portalHeaders,
      data: { name: 'VIP Bronze', price: 39.9, description: 'Acesso VIP', embedColor: '#8E44AD', featured: true },
    });
    expect(create.status()).toBe(201);
    const plan = await create.json();
    expect(plan.id).toBeTruthy();
    expect(Number(plan.price)).toBeCloseTo(39.9, 2);

    // Ação de entrega (grant_role) — payload em claro na resposta.
    const action = await request.post(`/api/v1/portal/plans/${plan.id}/actions`, {
      headers: state.portalHeaders,
      data: { seq: 0, type: 'GRANT_ROLE', payload: { roleId: 'r1' } },
    });
    expect(action.status()).toBe(201);
    expect((await action.json()).type).toBe('GRANT_ROLE');

    const list = await request.get('/api/v1/portal/plans', { headers: state.portalHeaders });
    expect(list.status()).toBe(200);
    expect((await list.json()).some((p) => p.id === plan.id)).toBe(true);

    const activate = await request.post(`/api/v1/portal/plans/${plan.id}/activate`, {
      headers: state.portalHeaders,
    });
    expect(activate.status()).toBe(204);

    const getPlan = await request.get(`/api/v1/portal/plans/${plan.id}`, { headers: state.portalHeaders });
    expect((await getPlan.json()).active).toBe(true);
  });

  test('9. Mensagens — upsert por etapa + agendamento em massa', async ({ request }) => {
    const upsert = await request.post('/api/v1/portal/store/messages', {
      headers: state.portalHeaders,
      data: { event: 'welcome', text: 'Bem-vindo à loja!', active: true },
    });
    expect(upsert.status()).toBe(201);
    const message = await upsert.json();
    expect(message.event).toBe('WELCOME');
    expect(message.text).toBe('Bem-vindo à loja!');

    // Upsert do mesmo evento → 200 (1 mensagem por etapa).
    const reUpsert = await request.post('/api/v1/portal/store/messages', {
      headers: state.portalHeaders,
      data: { event: 'welcome', text: 'Bem-vindo atualizado!', active: true },
    });
    expect(reUpsert.status()).toBe(200);

    const list = await request.get('/api/v1/portal/store/messages', { headers: state.portalHeaders });
    expect(list.status()).toBe(200);
    expect((await list.json()).length).toBeGreaterThanOrEqual(1);

    const future = new Date(Date.now() + 3600_000).toISOString();
    const schedule = await request.post('/api/v1/portal/store/messages/schedule', {
      headers: state.portalHeaders,
      data: { text: 'Promo de sexta!', scheduledAt: future, cron: null, audience: 'BUYERS' },
    });
    expect(schedule.status()).toBe(201);
    expect((await schedule.json()).audience).toBe('BUYERS');
  });

  test('10. Logout — sessão revogada (token antigo → 401)', async ({ request }) => {
    const logout = await request.post('/api/v1/merchant-auth/logout', { headers: state.portalHeaders });
    expect(logout.status()).toBe(204);

    const me = await request.get('/api/v1/portal/me', { headers: state.portalHeaders });
    expect(me.status()).toBe(401);
  });
});
