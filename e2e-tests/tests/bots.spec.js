// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * Bots renderizadores (Spec 52) — jornada E2E da API que o bot consome:
 *
 *   1. Setup (portal): criar loja + botões do menu + plano com as 4 ações de
 *      entrega (GRANT_ROLE, SEND_MESSAGE, ADD_TO_GROUP, SEND_FILE) + mensagem.
 *   2. Storefront:      GET /api/v1/foxcash/storefront (API key) → o bot RENDERIZA
 *                       a loja (identidade + botões ativos + planos ativos +
 *                       mensagens por etapa) — sem payload de ações em claro.
 *   3. Compra:          POST /api/v1/foxcash/plans/{id}/purchase (DISCORD) → QR.
 *   4. Webhook:         PAID → transação APPROVED → entrega criada.
 *   5. Entrega:         GET /api/v1/foxcash/deliveries/pending → o bot executa CADA
 *                       ação (seq) confirmando DONE: cargo, mensagem, grupo,
 *                       arquivo — payloads em claro ao dono da API key.
 *   6. Prova:           POST /{id}/access (acesso do comprador) + by-transaction
 *                       com ações DONE e accessLogs.
 *
 * Requer perfil dev. Usa a API key do lojista (ROLE_MERCHANT) nas rotas foxcash/*.
 */
test.describe('Bots — storefront, compra, webhook e entrega (4 ações)', () => {
  test.describe.configure({ mode: 'serial' });

  /** @type {{merchantId: string, apiKey: string, portalHeaders: Record<string,string>, planId: string, storeId: string}} */
  const state = { merchantId: '', apiKey: '', portalHeaders: {}, planId: '', storeId: '' };

  test('1. Setup da loja + storefront renderiza (bot consulta GET /storefront)', async ({ request }) => {
    // 1.1 Lojista + portal.
    const email = `bots.e2e.${Date.now()}@example.com`;
    const password = 'Senha-Bots-123';
    const register = await request.post('/api/v1/merchants', { data: { name: 'Loja Bots E2E', email, password } });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    state.merchantId = merchant.id;
    state.apiKey = merchant.apiKey;

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password } });
    expect(login.status()).toBe(200);
    state.portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 1.2 Criar loja (o bot renderiza a identidade).
    const store = await request.post('/api/v1/portal/store', {
      headers: state.portalHeaders,
      data: {
        name: 'Loja VIP',
        description: 'Loja de planos E2E',
        color: '#2ECC71',
        emoji: '🎮',
        title: 'Escolha seu plano!',
        footer: 'Obrigado!',
      },
    });
    expect(store.status()).toBe(201);
    state.storeId = (await store.json()).id;

    // 1.3 Botões do menu (BUY + FAQ) — o bot renderiza o fluxo.
    const buyBtn = await request.post('/api/v1/portal/store/buttons', {
      headers: state.portalHeaders,
      data: { label: 'Comprar', action: 'BUY', payload: null, active: true },
    });
    expect(buyBtn.status()).toBe(201);
    const faqBtn = await request.post('/api/v1/portal/store/buttons', {
      headers: state.portalHeaders,
      data: { label: 'FAQ', action: 'FAQ', payload: { text: 'Tire suas dúvidas!' }, active: true },
    });
    expect(faqBtn.status()).toBe(201);

    // 1.4 Plano com as 4 ações de entrega (cargo, mensagem, grupo, arquivo).
    const plan = await request.post('/api/v1/portal/plans', {
      headers: state.portalHeaders,
      data: { name: 'VIP Bronze', price: 39.9, description: 'Acesso VIP', embedColor: '#8E44AD', featured: true },
    });
    expect(plan.status()).toBe(201);
    state.planId = (await plan.json()).id;

    const actions = [
      { seq: 0, type: 'GRANT_ROLE', payload: { roleId: 'role-vip-123' } },
      { seq: 1, type: 'SEND_MESSAGE', payload: { text: 'Bem-vindo ao VIP!' } },
      { seq: 2, type: 'ADD_TO_GROUP', payload: { groupId: 'group-vip-456' } },
      { seq: 3, type: 'SEND_FILE', payload: { fileUrl: 'https://example.com/ebook.pdf', filename: 'ebook.pdf' } },
    ];
    for (const action of actions) {
      const added = await request.post(`/api/v1/portal/plans/${state.planId}/actions`, {
        headers: state.portalHeaders,
        data: action,
      });
      expect(added.status()).toBe(201);
    }
    const activate = await request.post(`/api/v1/portal/plans/${state.planId}/activate`, {
      headers: state.portalHeaders,
    });
    expect(activate.status()).toBe(204);

    // 1.5 Mensagem por etapa (o bot renderiza no fluxo).
    const message = await request.post('/api/v1/portal/store/messages', {
      headers: state.portalHeaders,
      data: { event: 'welcome', text: 'Olá! Bem-vindo à Loja VIP!', active: true },
    });
    expect(message.status()).toBe(201);

    // 1.6 BOT consulta o storefront (GET /foxcash/storefront) → renderiza tudo.
    const storefront = await request.get('/api/v1/foxcash/storefront', {
      headers: { 'X-Api-Key': state.apiKey },
    });
    expect(storefront.status()).toBe(200);
    const sf = await storefront.json();

    expect(sf.store.id).toBe(state.storeId);
    expect(sf.store.name).toBe('Loja VIP');
    expect(sf.store.color).toBe('#2ECC71');

    // Botões ativos em ordem, com payload tipado (dono da API key).
    const buttons = sf.buttons;
    expect(buttons.length).toBe(2);
    expect(buttons[0].action).toBe('BUY');
    expect(buttons[1].action).toBe('FAQ');
    expect(buttons[1].payload.text).toBe('Tire suas dúvidas!');

    // Planos ativos (card visual — SEM payload de ações em claro).
    expect(sf.plans.length).toBe(1);
    const planSf = sf.plans[0];
    expect(planSf.id).toBe(state.planId);
    expect(planSf.name).toBe('VIP Bronze');
    expect(Number(planSf.price)).toBeCloseTo(39.9, 2);
    expect(planSf.featured).toBe(true);

    // Mensagens por etapa (mapa event → text).
    expect(sf.messages.welcome).toBe('Olá! Bem-vindo à Loja VIP!');
  });

  test('2. Compra → webhook → entrega: cargo, mensagem, grupo e arquivo', async ({ request }) => {
    // 2.1 Bot cria a compra do plano (DISCORD, destinatário user-123).
    const purchase = await request.post(`/api/v1/foxcash/plans/${state.planId}/purchase`, {
      headers: { 'X-Api-Key': state.apiKey },
      data: {
        platform: 'DISCORD',
        platformUserId: 'user-123',
        buyerId: 'buyer-bot-1',
        expirationSeconds: 3600,
        idempotencyKey: `bot-purchase-${Date.now()}`,
        termsAccepted: true,
      },
    });
    expect(purchase.status()).toBe(201);
    const cob = await purchase.json();
    expect(cob.status).toBe('PENDING');
    expect(cob.pixCopyPaste).toContain('000201');
    const transactionId = cob.transactionId;

    // 2.2 Cliente paga → webhook PAID → transação APPROVED.
    const paid = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-bots-1',
        externalReference: cob.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 39.9,
      },
    });
    expect(paid.status()).toBe(204);

    // 2.3 Bot consulta as pendências → 1 entrega com as 4 ações (payload em claro).
    await expect.poll(async () => {
      const res = await request.get('/api/v1/foxcash/deliveries/pending', {
        headers: { 'X-Api-Key': state.apiKey },
      });
      return (await res.json()).length;
    }, { timeout: 60_000 }).toBe(1);

    const pending = (await (await request.get('/api/v1/foxcash/deliveries/pending', {
      headers: { 'X-Api-Key': state.apiKey },
    })).json())[0];
    expect(pending.transactionId).toBe(transactionId);
    expect(pending.platform).toBe('DISCORD');
    expect(pending.platformUserId).toBe('user-123');
    expect(pending.termsAcceptedAt).toBeTruthy(); // prova de aceite capturada na compra
    const deliveryId = pending.id;

    const actions = pending.actions;
    expect(actions.length).toBe(4);
    expect(actions.map((a) => a.actionType)).toEqual([
      'GRANT_ROLE', 'SEND_MESSAGE', 'ADD_TO_GROUP', 'SEND_FILE',
    ]);
    // Payloads decifrados ao dono da API key (criptografados em repouso).
    expect(actions[0].payload.roleId).toBe('role-vip-123');    // cargo
    expect(actions[1].payload.text).toBe('Bem-vindo ao VIP!'); // mensagem
    expect(actions[2].payload.groupId).toBe('group-vip-456');  // grupo
    expect(actions[3].payload.fileUrl).toBe('https://example.com/ebook.pdf'); // arquivo
    expect(actions.every((a) => a.status === 'PENDING')).toBe(true);

    // 2.4 Bot executa CADA ação confirmando DONE (replay idempotente 204).
    for (const action of actions) {
      const complete = await request.post(`/api/v1/foxcash/deliveries/${deliveryId}/actions/${action.seq}/complete`, {
        headers: { 'X-Api-Key': state.apiKey },
        data: { status: 'DONE' },
      });
      expect(complete.status()).toBe(204);
    }

    // 2.5 Prova de acesso: o bot confirma que o comprador usou o conteúdo.
    const access = await request.post(`/api/v1/foxcash/deliveries/${deliveryId}/access`, {
      headers: { 'X-Api-Key': state.apiKey },
      data: { note: 'Acesso concedido ao conteúdo' },
    });
    expect(access.status()).toBe(204);

    // 2.6 Entrega completa: ações DONE + accessLogs registrados.
    const done = await (await request.get(`/api/v1/foxcash/deliveries/by-transaction/${transactionId}`, {
      headers: { 'X-Api-Key': state.apiKey },
    })).json();
    expect(done.id).toBe(deliveryId);
    expect(done.actions.every((a) => a.status === 'DONE')).toBe(true);
    expect(done.actions.every((a) => a.executedAt)).toBe(true);
    expect(done.accessLogs.length).toBe(1);
    expect(done.accessLogs[0].note).toBe('Acesso concedido ao conteúdo');
    expect(done.accessLogs[0].ip).toBeTruthy();
  });
});
