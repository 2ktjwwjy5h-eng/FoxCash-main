// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');
const { totpAt } = require('./helpers/totp');

/**
 * E2E BROWSER do Master Console — fatia 2 (Spec 72): ações sensíveis com
 * step-up TOTP no cliente.
 *
 * Cobertura (critério 10 do requirements):
 *   1. write-off sem step-up → 403 step_up_required → prompt TOTP → POST
 *      /master/mfa/step-up → ação repetida → 200 {loss} → feedback;
 *   2. sessão permanece viva após o 403 (navegação intacta; /master/me 200);
 *   3. janela expirada (stepped_up_at no passado via SQL) → prompt reabre;
 *   4. perfis master admin-only: OPERATOR não vê o menu + GET /master/profiles
 *      → 403 forbidden (sessão viva); ADMIN cria perfil → senha temporária 1×;
 *   5. saque administrativo com step-up → 201 com saldo pós-débito.
 *
 * Requer a app com FOXCASH_CONSOLE_SERVE_FRONTEND=true + FOXCASH_PORTAL_SERVE_FRONTEND=true
 * e o PostgreSQL do profile dev (mesmas condições do job e2e do CI).
 */

const BASE = process.env.API_BASE_URL || 'http://localhost:8080';

/** Senha conhecida cujo hash BCrypt está em PASSWORD_BCRYPT (cost 10). */
const PASSWORD = 'Senha-E2E-123';
const PASSWORD_BCRYPT = '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6';

function dbClient() {
  return new Client({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    user: process.env.DB_USERNAME || 'nyxcash',
    password: process.env.DB_PASSWORD || 'nyxcash',
    database: process.env.DB_NAME || 'nyxcash',
  });
}

test.describe.serial('Master Console step-up (Spec 72)', () => {
  const ts = Date.now();
  const operatorUsername = `stp-op-${ts}`;
  const adminUsername = `stp-adm-${ts}`;
  let operatorToken;
  let adminToken;
  let adminSecret;
  let merchantId;
  let merchantApiKey;
  /** Fixture `request` do Playwright (injetada no beforeAll e em cada teste). */
  let request;

  async function seedMaster(username, canManageMasters) {
    const client = dbClient();
    await client.connect();
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, $5, $6)`,
      [nodeCrypto.randomUUID(), username, PASSWORD_BCRYPT, username, canManageMasters, new Date().toISOString()],
    );
    await client.end();
  }

  /** Ativa o MFA de verdade (TOTP real) para o step-up; devolve o secret. */
  async function activateMfa(username, token) {
    const activate = await request.post(`${BASE}/api/v1/master/mfa/activate`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(activate.status()).toBe(200);
    const secret = (await activate.json()).manualEntryKey;
    const confirm = await request.post(`${BASE}/api/v1/master/mfa/confirm`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { code: totpAt(secret, 0) },
    });
    expect(confirm.status()).toBe(200);
    return secret;
  }

  /** Faz o step-up via API (TOTP) — retorna 200 quando a janela abre.
   *  Usa o código da PRÓXIMA janela (+30s): o anti-replay do backend
   *  (verifySecondFactor.lastCodeHash) rejeita reusar o código do login
   *  (que é a janela atual) — o +30 é sempre válido (janela ±1) e distinto. */
  async function stepUpApi(token, secret) {
    const res = await request.post(`${BASE}/api/v1/master/mfa/step-up`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { code: totpAt(secret, 30) },
    });
    expect(res.status()).toBe(200);
  }

  /** Cria um receivable OPEN via SQL (origem CHARGEBACK — dívida formal). */
  async function createReceivable() {
    const client = dbClient();
    await client.connect();
    const r = await client.query(
      `INSERT INTO receivables (id, merchant_id, origin, reference_id, amount, paid_amount, status, created_at, updated_at)
       VALUES ($1, $2, 'CHARGEBACK', $3, 10.00, 0.00, 'OPEN', $4, $5)
       RETURNING id`,
      [nodeCrypto.randomUUID(), merchantId, nodeCrypto.randomUUID(), new Date().toISOString(), new Date().toISOString()],
    );
    await client.end();
    return r.rows[0].id;
  }

  /** Login no browser; se o master tiver MFA ativo, preenche o TOTP do login. */
  async function loginAs(page, username, secret = null) {
    if (secret) {
      // Anti-replay entre testes: o `last_code_hash` persiste no banco e um
      // login na MESMA janela de 30s de um código já consumido (teste anterior)
      // seria rejeitado como replay → 401 → redirect pro login. Limpa o marcador
      // (e o lockout, para o spec ser re-executável) antes do login.
      const client = dbClient();
      await client.connect();
      await client.query(
        `UPDATE master_totp t
         SET last_code_hash = NULL, failed_attempts = 0, locked_until = NULL
         FROM master_mfa_methods m JOIN masters ma ON ma.id = m.master_id
         WHERE t.method_id = m.id AND ma.username = $1`,
        [username],
      );
      await client.end();
    }
    await page.goto('/console/login');
    await page.getByLabel('Usuário').fill(username);
    await page.getByLabel('Senha').fill(PASSWORD);
    if (secret) {
      await page.getByLabel('Código TOTP (se o MFA estiver ativo)').fill(totpAt(secret, 0));
    }
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');
  }

  /** O prompt de step-up — o único dialog com "Reautenticação necessária"
   *  (o ConfirmDialog da ação permanece aberto durante o 403 step_up_required,
   *  então getByRole('dialog') resolve 2 elementos e exige filtro). */
  function stepUpDialog(page) {
    return page.getByRole('dialog').filter({ hasText: 'Reautenticação necessária' });
  }

  test.beforeAll(async ({ request: req }) => {
    request = req;
    // Limpa masters deixados por specs anteriores (ex.: auth.spec ativa MFA e
    // deixa vários masters). O limite de 4 perfis da Spec 41 estouraria o
    // create do teste 4 com 409 too_many_masters (flakiness cross-spec
    // observada 2026-08-13 em suíte completa/combo auth — o access log mostra
    // step-up 200 + repeat 409; isolado passa porque o banco está limpo).
    // Mesma classe da limpeza do journey 22 (FKs primeiro).
    const cleanup = dbClient();
    await cleanup.connect();
    await cleanup.query('DELETE FROM master_mfa_methods'); // V42 cascata p/ totp/recovery
    await cleanup.query('DELETE FROM master_sessions');
    await cleanup.query('DELETE FROM master_audit_log');
    await cleanup.query('DELETE FROM masters');
    await cleanup.end();
    await seedMaster(operatorUsername, false); // OPERATOR (can_manage_masters=false)
    await seedMaster(adminUsername, true); // ADMIN

    const opLogin = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: operatorUsername, password: PASSWORD },
    });
    expect(opLogin.status()).toBe(200);
    operatorToken = (await opLogin.json()).token;

    const admLogin = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: adminUsername, password: PASSWORD },
    });
    expect(admLogin.status()).toBe(200);
    adminToken = (await admLogin.json()).token;
    adminSecret = await activateMfa(adminUsername, adminToken);

    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'StepUp Loja E2E', email: `stp-${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(reg.status()).toBe(201);
    merchantId = (await reg.json()).id;
    merchantApiKey = (await reg.json()).apiKey;
  });

  test('1. write-off sem step-up → 403 step_up_required → prompt TOTP → repete → 200', async ({ page, request: req }) => {
    request = req;
    // Login no browser como ADMIN (o write-off é ação do bloco completo — todo
    // master ativo; MFA ativo no beforeAll → login exige TOTP).
    await loginAs(page, adminUsername, adminSecret);

    // Cria receivable OPEN para o write-off.
    const receivableId = await createReceivable();

    // Garante que a sessão do browser NÃO tem step-up (janela limpa).
    const client = dbClient();
    await client.connect();
    await client.query('UPDATE master_sessions SET stepped_up_at = NULL');
    await client.end();

    await page.goto('/console/receivables');
    await expect(page.locator('table')).toContainText('CHARGEBACK');

    // Click "Write-off" → 403 step_up_required → prompt TOTP aparece.
    await page.getByRole('button', { name: 'Write-off' }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.locator('h2')).toContainText('Write-off de receivable');
    await page.getByLabel('Motivo (obrigatório)').fill('Dívida incobrável');

    // Ação → o backend responde 403 step_up_required (sem step-up) → prompt abre.
    await page.getByRole('button', { name: 'Confirmar baixa' }).click();
    await expect(stepUpDialog(page)).toHaveText(/Reautenticação necessária/);

    // Digita o TOTP do step-up (janela +30s — distinto do código do login,
    // que o anti-replay do backend já consumiu) → repete a ação → 200 {loss}.
    await page.getByLabel('Código TOTP').fill(totpAt(adminSecret, 30));
    await stepUpDialog(page).getByRole('button', { name: 'Confirmar' }).click();
    // Timeout maior: sob carga da suíte completa o round-trip step-up + repetição
    // da ação pode exceder os 5s default (flakiness observada 2026-08-12).
    await expect(page.locator('.alert-success')).toContainText('Baixa de', { timeout: 15000 });

    // O receivable foi baixado: a linha CHARGEBACK virou PAID e perdeu o botão
    // Write-off (o filtro mostra todos os status — a linha continua na lista).
    const row = page.locator('tbody tr', { hasText: 'CHARGEBACK' }).first();
    await expect(row).toContainText('PAID');
    await expect(row).not.toContainText('Write-off');
  });

  test('2. sessão permanece viva após 403 step_up_required (sem logout)', async ({ page, request: req }) => {
    request = req;
    // Cada teste tem contexto novo → login explícito (MFA ativo exige TOTP).
    await loginAs(page, adminUsername, adminSecret);
    // Cria outro receivable; tenta write-off e CANCELA o prompt.
    await createReceivable();
    const client = dbClient();
    await client.connect();
    await client.query('UPDATE master_sessions SET stepped_up_at = NULL');
    await client.end();

    await page.goto('/console/receivables');
    await page.getByRole('button', { name: 'Write-off' }).first().click();
    await page.getByLabel('Motivo (obrigatório)').fill('teste');
    await page.getByRole('button', { name: 'Confirmar baixa' }).click();
    await expect(stepUpDialog(page)).toHaveText(/Reautenticação necessária/);
    await stepUpDialog(page).getByRole('button', { name: 'Cancelar' }).click();

    // A sessão NÃO caiu: a navegação continua autenticada.
    await page.goto('/console/dashboard');
    await expect(page.locator('h1')).toHaveText('Dashboard');
    const me = await request.get(`${BASE}/api/v1/master/me`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(me.status()).toBe(200);
  });

  test('3. janela expirada → prompt reabre (backend é a fonte de verdade)', async ({ page, request: req }) => {
    request = req;
    // Cada teste tem contexto novo → login explícito (MFA ativo exige TOTP).
    await loginAs(page, adminUsername, adminSecret);
    await createReceivable();
    // Step-up real via API (abre a janela)...
    await stepUpApi(adminToken, adminSecret);
    // ...e expira a janela no banco (20 min no passado).
    const client = dbClient();
    await client.connect();
    await client.query("UPDATE master_sessions SET stepped_up_at = now() - interval '20 minutes'");
    await client.end();

    await page.goto('/console/receivables');
    await page.getByRole('button', { name: 'Write-off' }).first().click();
    await page.getByLabel('Motivo (obrigatório)').fill('janela expirada');
    await page.getByRole('button', { name: 'Confirmar baixa' }).click();
    // Mesmo com o cache do cliente (se houver), o backend rejeita → prompt reabre.
    await expect(stepUpDialog(page)).toHaveText(/Reautenticação necessária/);
    await stepUpDialog(page).getByRole('button', { name: 'Cancelar' }).click();
  });

  test('4. perfis master admin-only: OPERATOR escondido + 403; ADMIN cria perfil (senha 1×)', async ({ page, request: req }) => {
    request = req;

    // OPERATOR: item "Perfis master" NÃO aparece no menu (UI RBAC-aware).
    await loginAs(page, operatorUsername);
    await expect(page.locator('nav')).not.toContainText('Perfis master');

    // OPERATOR: GET /master/profiles → 403 forbidden; sessão viva.
    const forbidden = await request.get(`${BASE}/api/v1/master/profiles`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
    });
    expect(forbidden.status()).toBe(403);
    expect((await forbidden.json()).code).toBe('forbidden');
    const opMe = await request.get(`${BASE}/api/v1/master/me`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
    });
    expect(opMe.status()).toBe(200);

    // ADMIN: cria perfil com step-up → 201 com senha temporária exibida 1×.
    // MFA ativo → login exige TOTP (helper loginAs).
    await loginAs(page, adminUsername, adminSecret);

    // Limpa a janela de step-up para o fluxo abrir o prompt.
    const client = dbClient();
    await client.connect();
    await client.query('UPDATE master_sessions SET stepped_up_at = NULL');
    await client.end();

    await page.getByRole('link', { name: 'Perfis master' }).click();
    await page.waitForURL('**/console/profiles');
    await expect(page.locator('h1')).toHaveText('Perfis master');

    await page.getByLabel('Usuário (login)').fill(`novo-perfil-${ts}`);
    await page.getByLabel('Nome').fill('Novo Perfil');
    await page.getByRole('button', { name: 'Criar perfil' }).click();

    // 403 step_up_required → prompt TOTP (código +30s, anti-replay) → step-up
    // → repete → 201 com senha 1×.
    await expect(stepUpDialog(page)).toHaveText(/Reautenticação necessária/);
    await page.getByLabel('Código TOTP').fill(totpAt(adminSecret, 30));
    await stepUpDialog(page).getByRole('button', { name: 'Confirmar' }).click();

    // Timeout maior: o round-trip step-up + repetição da ação sob a suíte
    // completa pode exceder os 5s default (flakiness observada 2026-08-12).
    await expect(page.getByRole('dialog')).toContainText('senha temporária', { timeout: 15000 });
    const secretBox = await page.locator('.secret-box').textContent();
    expect(secretBox.trim()).toMatch(/^[A-Za-z0-9!@#$%^&*._-]+$/);
    await page.getByRole('button', { name: 'Já copiei' }).click();

    // O novo perfil aparece na lista.
    await expect(page.locator('table')).toContainText(`novo-perfil-${ts}`, { timeout: 15000 });
  });

  test('5. saque administrativo com step-up → 201 com saldo pós-débito', async ({ page, request: req }) => {
    request = req;
    // Cada teste tem contexto novo → login explícito (MFA ativo exige TOTP).
    await loginAs(page, adminUsername, adminSecret);
    // Deposita saldo no wallet do lojista: cobrança + webhook PAID (SALE_CREDIT).
    // Contrato do PixChargeRequest: merchantId + expirationSeconds + idempotencyKey
    // obrigatórios (Spec 28 — key única por lojista).
    const charge = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': merchantApiKey },
      data: {
        merchantId,
        amount: 100.0,
        expirationSeconds: 3600,
        idempotencyKey: `stp-cob-${ts}`,
      },
    });
    expect(charge.status()).toBe(201);
    const chargeBody = await charge.json();

    // Webhook PAID (público, sem X-Api-Key — assinatura HMAC desligada em dev;
    // mesmo contrato do merchant-journey: eventId/externalReference/settledAt).
    const webhook = await request.post(`${BASE}/webhooks/pix`, {
      data: {
        eventId: 'evt-stepup-e2e',
        externalReference: chargeBody.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 100.0,
      },
    });
    expect(webhook.status()).toBe(204);

    await page.goto('/console/financial');
    await expect(page.locator('h1')).toHaveText('Financeiro do master');

    // Seleciona pelo value (UUID) — único entre runs (os nomes se repetem).
    await page.getByLabel('Lojista').selectOption(merchantId);
    await page.getByLabel('Valor (R$)').fill('10.00');
    await page.getByLabel('Chave Pix de destino').fill('52998224725');
    await page.getByLabel('Motivo (obrigatório)').fill('Recolhimento de teste');

    const client = dbClient();
    await client.connect();
    await client.query('UPDATE master_sessions SET stepped_up_at = NULL');
    await client.end();

    await page.getByRole('button', { name: 'Realizar saque administrativo' }).click();
    await expect(page.getByRole('dialog')).toContainText('Saque administrativo');
    await page.getByRole('button', { name: 'Confirmar saque' }).click();

    // 403 step_up_required → prompt → step-up (código +30s, anti-replay)
    // → repete → 201 com saldo pós-débito.
    await expect(stepUpDialog(page)).toHaveText(/Reautenticação necessária/);
    await page.getByLabel('Código TOTP').fill(totpAt(adminSecret, 30));
    await stepUpDialog(page).getByRole('button', { name: 'Confirmar' }).click();
    await expect(page.locator('.alert-success')).toContainText('Saque administrativo de', { timeout: 15000 });

    // Cleanup: remove os masters criados (cenário 22 da journey limpa masters).
    const cleanup = dbClient();
    await cleanup.connect();
    await cleanup.query('DELETE FROM master_mfa_methods WHERE master_id IN (SELECT id FROM masters WHERE username LIKE $1)', [
      `stp-%`,
    ]);
    await cleanup.end();
  });
});
