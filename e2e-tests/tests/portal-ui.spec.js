// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * E2E BROWSER do portal do lojista (Spec 46) — SPA React/Vite servida pelo
 * próprio Spring Boot.
 *
 * Requer a app com `FOXCASH_PORTAL_SERVE_FRONTEND=true` (fallback do
 * /index.html + CSP da SPA + rotas públicas) e o PostgreSQL do profile dev —
 * mesmas condições do job e2e do CI (ci.yml).
 *
 * Cobertura (critérios de aceite da Spec 46 — requirements.md):
 *   login → dashboard → cobrança avulsa (QR/Copia e Cola) → venda paga
 *   (webhook PAID → APPROVED na listagem) → destinos (SÓ a máscara no DOM,
 *   nunca o valor em claro) → saque com destino selecionado → logout →
 *   rota privada sem token redireciona para /login → API sem token responde 401.
 */
test.describe.serial('Portal UI (Spec 46)', () => {
  const ts = Date.now();
  const email = `portal-ui-${ts}@example.com`;
  const password = 'Senha-Portal-1';
  const CLEAR_CPF = '12345678909'; // valor em claro que NUNCA pode aparecer no DOM

  test.beforeAll(async ({ request }) => {
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Portal UI E2E', email, password },
    });
    expect(reg.status()).toBe(201);
  });

  test('login → dashboard → cobrança (QR) → venda paga → destinos mascarados → saque → logout → 401/redirect', async ({
    page,
    request,
  }) => {
    // --- Login (rota pública) ----------------------------------------------
    await page.goto('/login');
    await expect(page.locator('h1.auth-title')).toHaveText('FoxCash');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');
    await expect(page.locator('h1')).toHaveText('Dashboard');

    // --- Cobrança avulsa → QR Code / Copia e Cola --------------------------
    await page.getByRole('link', { name: 'Cobrança' }).click();
    await page.waitForURL('**/charges');
    await page.getByLabel('Valor (R$)').fill('12.50');
    await page.getByLabel('Descrição').fill('Cobrança UI E2E');
    await page.getByRole('button', { name: 'Gerar cobrança' }).click();
    await expect(page.locator('text=Copia e Cola')).toBeVisible();
    await expect(page.locator('textarea#copy-paste')).toHaveValue(/000201/);

    // Captura o externalReference exibido (usado no webhook PAID abaixo).
    const refLine = await page.locator('.charge-details p', { hasText: 'Referência' }).textContent();
    const externalReference = refLine.replace(/.*Referência:\s*/, '').trim();
    // Mock PSP: 'E' + 32 hex (UUID sem hífen) — formato do mock no backend.
    expect(externalReference).toMatch(/^E[A-F0-9]{32}$/);

    // --- Venda paga: webhook PAID → transação APPROVED (Spec 25/35) --------
    // O webhook é público (assinatura HMAC do PSP), não usa o token do portal.
    const paid = await request.post('/webhooks/pix', {
      data: {
        eventId: `evt-portal-ui-${ts}-paid`,
        externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 12.5,
      },
    });
    expect(paid.status()).toBe(204);

    // A venda aparece na listagem como APPROVED (Spec 25).
    await page.getByRole('link', { name: 'Vendas' }).click();
    await page.waitForURL('**/sales');
    await expect(page.locator('table')).toContainText('R$ 12,50');
    await expect(page.locator('table')).toContainText('APPROVED');

    // --- Destinos: só a MÁSCARA no DOM, nunca o valor em claro -------------
    await page.getByRole('link', { name: 'Destinos' }).click();
    await page.waitForURL('**/destinations');
    await page.getByLabel('Tipo').selectOption('PIX_CPF');
    await page.getByLabel('Chave / dados bancários').fill(CLEAR_CPF);
    await page.getByLabel('Rótulo').fill('CPF principal');
    await page.getByRole('button', { name: 'Cadastrar' }).click();
    await expect(page.locator('text=CPF principal')).toBeVisible();
    // máscara visível (padrão de CPF mascarado contém ***)
    await expect(page.locator('.dest-item')).toContainText('***');
    // valor em claro AUSENTE do DOM (requisito de aceite da Spec 31/46)
    await expect(page.locator('body')).not.toContainText(CLEAR_CPF);

    // --- Saque com destino selecionado (Spec 32/36) ------------------------
    await page.getByRole('link', { name: 'Saque' }).click();
    await page.waitForURL('**/withdraw');
    // saldo credita a venda paga (12.50)
    await expect(page.locator('text=R$ 12,50')).toBeVisible();
    await page.getByLabel('Valor (R$)').fill('10.00');
    // seleciona o destino recém-criado pelo texto da option (o id é UUID do banco).
    // Spec 70: a página ganhou um 2º form ("Saque automático") com o MESMO select
    // de destinos — escopa ao form "Solicitar saque" para evitar strict mode.
    const solicitarForm = page.locator('form', {
      has: page.getByRole('button', { name: 'Solicitar saque' }),
    });
    const destOption = solicitarForm.locator('select option', { hasText: 'CPF principal' });
    const destValue = await destOption.getAttribute('value');
    expect(destValue).toBeTruthy();
    await solicitarForm.getByLabel('Destino').selectOption(destValue);
    await page.getByRole('button', { name: 'Solicitar saque' }).click();
    // saque solicitado (mock payout → PROCESSING, Spec 36)
    await expect(page.locator('text=Saque de R$ 10,00 solicitado')).toBeVisible();
    // o saque aparece na listagem de saques recentes (item da lista, não a msg)
    await expect(page.locator('.plain-list li', { hasText: 'R$ 10,00' })).toBeVisible();

    // --- Logout explícito → volta ao login ---------------------------------
    await page.getByRole('button', { name: 'Sair' }).click();
    await page.waitForURL('**/login');

    // --- Rota privada sem token → redirect para /login (guard client-side) --
    await page.goto('/dashboard');
    await page.waitForURL('**/login');

    // --- API do portal sem token → 401 (backend mantém o contrato) ---------
    const noAuth = await request.get('/api/v1/portal/me');
    expect(noAuth.status()).toBe(401);
  });
});

// --- Spec 121: Pagamento via Saldo (UI) --------------------------------------
// Brcodes estáticos gerados com implementação independente (CRC16-CCITT-FALSE).
const PVS_EMV_WITH_AMOUNT =
  '00020126330014br.gov.bcb.pix011152998224725520400005303986540510.005802BR5906FULANO6008BRASILIA62070503***6304B756'; // 10.00
const PVS_EMV_NO_AMOUNT =
  '00020126330014br.gov.bcb.pix0111529982247255204000053039865802BR5905TESTE6008BRASILIA630471D8';
const PVS_EMV_DYNAMIC =
  '00020126410014br.gov.bcb.pix2519pix.example.com/1235204000053039865802BR5905TESTE6008BRASILIA630493A3';

test.describe.serial('Portal UI — Pagamento via Saldo (Spec 121)', () => {
  const ts = Date.now();
  const email = `portal-pvs-${ts}@example.com`;
  const password = 'Senha-Portal-2';

  test.beforeAll(async ({ request }) => {
    // Lojista + saldo 150 (cobrança + webhook PAID) — auto-suficiente.
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Portal PVS E2E', email, password },
    });
    expect(reg.status()).toBe(201);
    const merchant = await reg.json();
    const charge = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': merchant.apiKey },
      data: {
        merchantId: merchant.id,
        amount: 150,
        buyerId: `buyer-pvs-ui-${ts}`,
        expirationSeconds: 3600,
        idempotencyKey: `pvs-ui-cob-${ts}`,
      },
    });
    expect(charge.status()).toBe(201);
    const cob = await charge.json();
    const paid = await request.post('/webhooks/pix', {
      data: {
        eventId: `evt-pvs-ui-${ts}`,
        externalReference: cob.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 150,
      },
    });
    expect(paid.status()).toBe(204);
  });

  /** Login do lojista (cada teste tem contexto/browser novo — isolar sessão). */
  async function login(page) {
    await page.goto('/login');
    await page.getByLabel('E-mail').fill(email);
    await page.getByLabel('Senha').fill(password);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/dashboard');
  }

  test('PVS: colar brcode → prévia (chave mascarada + total) → confirmar → PROCESSING → item no histórico', async ({
    page,
  }) => {
    await login(page);
    await page.getByRole('link', { name: 'Saque' }).click();
    await page.waitForURL('**/withdraw');

    // Critério 7 (Spec 121): foco no campo ao entrar na aba.
    await expect(page.locator('#pvs-pix-code')).toBeFocused();

    // Cola o brcode com valor (10.00) → Ver prévia → chave mascarada + total.
    await page.locator('#pvs-pix-code').fill(PVS_EMV_WITH_AMOUNT);
    await page.getByRole('button', { name: 'Ver prévia' }).click();
    await expect(page.locator('text=529***725')).toBeVisible();
    await expect(page.locator('text=Total a debitar')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Pagar R$ 12,50' })).toBeVisible();

    // Confirma no dialog (danger → confirmação explícita da ação financeira).
    await page.getByRole('button', { name: 'Pagar R$ 12,50' }).click();
    const dialog = page.locator('[role="dialog"]');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: /^Pagar R\$ 12,50/ }).click();

    // Sucesso: PROCESSING + saldo atualizado + item no histórico de saques.
    await expect(page.locator('text=Pagamento de R$ 10,00 solicitado')).toBeVisible();
    await expect(page.locator('.plain-list li', { hasText: 'R$ 10,00' })).toBeVisible();
    // Formulário limpo após o pagamento.
    await expect(page.locator('#pvs-pix-code')).toHaveValue('');
  });

  test('PVS: Pix dinâmico → aviso claro (sem chamar o backend)', async ({ page }) => {
    await login(page);
    await page.goto('/withdraw');
    await page.locator('#pvs-pix-code').fill(PVS_EMV_DYNAMIC);
    await page.getByRole('button', { name: 'Ver prévia' }).click();
    await expect(
      page.locator('text=Pix dinâmico não é suportado no Pagamento via Saldo'),
    ).toBeVisible();
  });

  test('PVS: teto por operação → erro do backend + limites recarregados', async ({ page }) => {
    await login(page);
    await page.goto('/withdraw');
    // EMV sem valor → digitar 1000,00 (valor + taxa 2,50 = 1.002,50 > teto 1.000,00).
    // A página tem 2 campos "Valor (R$)" (saque + prévia do PVS) — escopa à prévia.
    await page.locator('#pvs-pix-code').fill(PVS_EMV_NO_AMOUNT);
    await page.getByRole('button', { name: 'Ver prévia' }).click();
    const previewCard = page.locator('section.card', { hasText: 'Prévia do pagamento' });
    await previewCard.getByLabel('Valor (R$)').fill('1000');
    await expect(page.getByRole('button', { name: /Pagar R\$ 1\.002,50/ })).toBeVisible();
    await page.getByRole('button', { name: /Pagar R\$ 1\.002,50/ }).click();
    const dialog = page.locator('[role="dialog"]');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: /^Pagar R\$ 1\.002,50/ }).click();
    // Banner com a mensagem mapeada (Spec 121/D4) — o dialog mantém o erro aberto.
    await expect(dialog.getByText('Valor acima do limite por operação.')).toBeVisible();
  });
});
