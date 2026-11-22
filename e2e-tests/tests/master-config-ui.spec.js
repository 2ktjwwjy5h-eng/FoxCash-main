// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');
const { totpAt } = require('./helpers/totp');

/**
 * E2E BROWSER do Master Console — Config fina por lojista (Spec 100): rota
 * /console/merchants/:id/config com regra de taxa (FIXED/PERCENT), taxas e
 * limites de saque, rate limit, kill switch (activate/deactivate), debitar
 * saldo com step-up TOTP (janela expirada), honeypot, bloquear API e banir
 * com step-up. Requer app com FOXCASH_CONSOLE_SERVE_FRONTEND=true (+ portal)
 * e PostgreSQL dev — mesmas condições do job e2e do CI.
 *
 * Seed: master ADMIN via SQL + MFA TOTP real (padrão master-console-stepup) +
 * lojista via API pública.
 */

const BASE = process.env.API_BASE_URL || 'http://localhost:8080';

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

test.describe.serial('Master Console Config fina (Spec 100)', () => {
  const ts = Date.now();
  const adminUsername = `cfg-adm-${ts}`;
  let adminToken;
  let adminSecret;
  let merchantId;
  let request; // Playwright APIRequestContext (preenchido no beforeAll)

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

  /** Zera a janela de step-up da sessão (força o 403 step_up_required). */
  async function clearStepUpWindow() {
    const client = dbClient();
    await client.connect();
    await client.query('UPDATE master_sessions SET stepped_up_at = NULL');
    await client.end();
  }

  /** Login no browser (o admin tem MFA ativo → preenche o TOTP do login). */
  async function loginAs(page) {
    // Anti-replay: limpa o last_code_hash do TOTP do login (padrão stepup).
    const client = dbClient();
    await client.connect();
    await client.query(
      `UPDATE master_totp t
       SET last_code_hash = NULL, failed_attempts = 0, locked_until = NULL
       FROM master_mfa_methods m JOIN masters ma ON ma.id = m.master_id
       WHERE t.method_id = m.id AND ma.username = $1`,
      [adminUsername],
    );
    await client.end();

    await page.goto('/console/login');
    await page.getByLabel('Usuário').fill(adminUsername);
    await page.getByLabel('Senha').fill(PASSWORD);
    await page.getByLabel('Código TOTP (se o MFA estiver ativo)').fill(totpAt(adminSecret, 0));
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');
  }

  function stepUpDialog(page) {
    // Nome acessível: o prompt de step-up renderiza DENTRO do modal pai (sem
    // portal) — filter(hasText) casaria o modal de débito também (strict mode).
    return page.getByRole('dialog', { name: 'Reautenticação necessária' });
  }

  async function openConfig(page) {
    await page.goto(`/console/merchants/${merchantId}/config`);
    await expect(page.locator('h1')).toContainText('Config de');
  }

  test.beforeAll(async ({ request: req }) => {
    request = req;
    const cleanup = dbClient();
    await cleanup.connect();
    await cleanup.query('DELETE FROM master_mfa_methods');
    await cleanup.query('DELETE FROM master_sessions');
    await cleanup.query('DELETE FROM master_audit_log');
    await cleanup.query('DELETE FROM masters');
    await cleanup.end();

    // Master ADMIN (can_manage_masters=true) + MFA real para o step-up.
    const client = dbClient();
    await client.connect();
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, true, $5)`,
      [nodeCrypto.randomUUID(), adminUsername, PASSWORD_BCRYPT, 'Config E2E', new Date().toISOString()],
    );
    await client.end();

    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: adminUsername, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    adminToken = (await login.json()).token;
    adminSecret = await activateMfa(adminUsername, adminToken);

    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Config Loja E2E', email: `cfg-${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(reg.status()).toBe(201);
    merchantId = (await reg.json()).id;
  });

  test('1. formulário carrega + regra de taxa FIXED/PERCENT + limites + rate limit salvam', async ({ page }) => {
    await loginAs(page);
    await openConfig(page);

    // Status/APIs no topo (cards).
    await expect(page.locator('.card', { hasText: 'Status' }).first()).toContainText('ACTIVE');

    // Regra de taxa FIXED.
    let section = page.locator('.config-section', { hasText: 'Regra de taxa' }).first();
    await section.getByLabel('Tipo de taxa').selectOption('FIXED');
    // getByRole spinbutton: getByLabel('Taxa fixa') também casa o <select>
    // (o texto do <option value="FIXED"> faz parte do label acessível).
    await section.getByRole('spinbutton', { name: 'Taxa fixa (R$)' }).fill('0.75');
    await section.getByRole('button', { name: 'Salvar seção' }).click();
    await expect(section).toContainText('Seção salva.');

    // Regra de taxa PERCENT (threshold).
    section = page.locator('.config-section', { hasText: 'Regra de taxa' }).first();
    await section.getByLabel('Tipo de taxa').selectOption('PERCENT');
    await section.getByRole('spinbutton', { name: 'Percentual (%)' }).fill('1.50');
    await section.getByRole('spinbutton', { name: 'Cobrar percentual acima de (R$)' }).fill('100');
    await section.getByRole('button', { name: 'Salvar seção' }).click();
    await expect(section).toContainText('Seção salva.');

    // Taxas e limites de saque.
    section = page.locator('.config-section', { hasText: 'Taxas e limites de saque' }).first();
    await section.getByLabel('Taxa de saque (R$)').fill('3.00');
    await section.getByLabel('Limite Pix mín (R$)').fill('5');
    await section.getByLabel('Limite Pix máx (R$)').fill('500');
    await section.getByRole('button', { name: 'Salvar seção' }).click();
    await expect(section).toContainText('Seção salva.');

    // Rate limit integrado.
    section = page.locator('.config-section', { hasText: 'Rate limit integrado' }).first();
    await section.getByLabel('Requisições por segundo (req/s)').fill('5');
    await section.getByLabel('Requisições por minuto (req/min)').fill('120');
    await section.getByRole('button', { name: 'Salvar seção' }).click();
    await expect(section).toContainText('Seção salva.');

    // Confirma o round-trip: a config recarregada mantém os valores salvos.
    await page.reload();
    await expect(page.locator('h1')).toContainText('Config de');
    section = page.locator('.config-section', { hasText: 'Rate limit integrado' }).first();
    await expect(section.getByLabel('Requisições por segundo (req/s)')).toHaveValue('5');
  });

  test('2. kill switch: desativar (ConfirmDialog danger) + ativar', async ({ page }) => {
    await loginAs(page);
    await openConfig(page);

    // Desativar → INACTIVE. Os radios são controlados (checked reflete o status
    // real; o clique apenas abre o ConfirmDialog) — .check() exigiria mudança de
    // estado; o correto é .click().
    await page.getByLabel('Inativo (Bloqueado)').click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('Desativar lojista');
    await page.getByRole('button', { name: 'Desativar', exact: true }).click();
    await expect(page.locator('.alert-success')).toContainText('desativado');
    await expect(page.locator('.card', { hasText: 'Status' }).first()).toContainText('INACTIVE');

    // Ativar → ACTIVE. (exact: getByLabel é case-insensitive — 'Ativo' também
    // casa 'Inativo (Bloqueado)'.)
    await page.getByLabel('Ativo', { exact: true }).click();
    const dialog2 = page.getByRole('dialog');
    await expect(dialog2).toContainText('Ativar lojista');
    await page.getByRole('button', { name: 'Ativar', exact: true }).click();
    await expect(page.locator('.alert-success')).toContainText('ativado');
    await expect(page.locator('.card', { hasText: 'Status' }).first()).toContainText('ACTIVE');
  });

  test('3. debitar saldo com step-up TOTP (janela expirada → prompt reabre)', async ({ page }) => {
    await loginAs(page);
    await openConfig(page);
    await clearStepUpWindow();

    await page.getByRole('button', { name: 'Debitar saldo' }).first().click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Debitar saldo de' });
    await expect(modal).toContainText('Saldo atual');
    await modal.getByLabel('Valor (R$)').fill('5.00');
    await modal.getByLabel('Motivo (obrigatório)').fill('Ajuste administrativo E2E');
    await modal.getByRole('button', { name: 'Debitar saldo' }).click();

    // 403 step_up_required → prompt TOTP (código +30s, anti-replay) → repete.
    await expect(stepUpDialog(page)).toHaveText(/Reautenticação necessária/);
    await page.getByLabel('Código TOTP').fill(totpAt(adminSecret, 30));
    await stepUpDialog(page).getByRole('button', { name: 'Confirmar' }).click();
    await expect(page.locator('.alert-success')).toContainText('Débito de', { timeout: 15000 });
    // Novo saldo negativo permitido (decisão Spec 98 — débito administrativo).
    await expect(page.locator('.alert-success')).toContainText('novo saldo');
  });

  test('4. honeypot on/off + bloquear/desbloquear API', async ({ page }) => {
    await loginAs(page);
    await openConfig(page);

    // Honeypot ON (ConfirmDialog com o texto do ambiente controlado). Os toggles
    // são switches (.switch-slider visível; input opacity:0 + controlado — abre
    // o ConfirmDialog, não alterna). Clique no slider, não .check().
    const toggleCard = page.locator('.card', { hasText: 'Honeypot e API' });
    await toggleCard.locator('.switch-slider').first().click();
    const honeypotDialog = page.getByRole('dialog');
    await expect(honeypotDialog).toContainText('Marcar como honeypot');
    await expect(honeypotDialog).toContainText('fluxo de monitoramento controlado');
    await honeypotDialog.getByRole('button', { name: 'Ativar honeypot' }).click();
    await expect(page.locator('.alert-success')).toContainText('Honeypot ativado');
    await expect(page.locator('.card', { hasText: 'Honeypot' }).first()).toContainText('SIM');

    // Honeypot OFF.
    await toggleCard.locator('.switch-slider').first().click();
    const honeypotOff = page.getByRole('dialog');
    await honeypotOff.getByRole('button', { name: 'Desativar honeypot' }).click();
    await expect(page.locator('.alert-success')).toContainText('Honeypot desativado');

    // Bloquear API.
    await toggleCard.locator('.switch-slider').nth(1).click();
    const apiDialog = page.getByRole('dialog');
    await expect(apiDialog).toContainText('Bloquear API do lojista');
    await apiDialog.getByRole('button', { name: 'Bloquear API', exact: true }).click();
    await expect(page.locator('.alert-success')).toContainText('API do lojista bloqueada');
    await expect(page.locator('.card', { hasText: 'API' }).first()).toContainText('BLOQUEADA');
  });

  test('5. banir com step-up TOTP → irreversível; botão some após banido', async ({ page, request }) => {
    await loginAs(page);
    await openConfig(page);
    await clearStepUpWindow();

    // Cria um lojista separado para banir (não quebra o status dos testes 1–4).
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Config Ban E2E', email: `cfg-ban-${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(reg.status()).toBe(201);
    const banMerchantId = (await reg.json()).id;
    await page.goto(`/console/merchants/${banMerchantId}/config`);
    await expect(page.locator('h1')).toContainText('Config de');

    await page.getByRole('button', { name: 'Banir conta' }).first().click();
    const banModal = page.getByRole('dialog').filter({ hasText: 'Banir conta de' });
    await expect(banModal).toContainText('irreversível');
    await banModal.getByLabel('Motivo (obrigatório)').fill('Fraude comprovada E2E');
    await banModal.getByRole('button', { name: 'Banir conta' }).click();

    // 403 step_up_required → prompt TOTP → repete → 204.
    await expect(stepUpDialog(page)).toHaveText(/Reautenticação necessária/);
    await page.getByLabel('Código TOTP').fill(totpAt(adminSecret, 30));
    await stepUpDialog(page).getByRole('button', { name: 'Confirmar' }).click();
    await expect(page.locator('.alert-success')).toContainText('Conta banida', { timeout: 15000 });

    // BANIDO: aviso + botão "Banir conta" some (irreversível).
    await expect(page.locator('.alert-error')).toContainText('Conta banida');
    await expect(page.getByRole('button', { name: 'Banir conta' })).toHaveCount(0);
    await expect(page.locator('.card', { hasText: 'Status' }).first()).toContainText('BANNED');
  });
});
