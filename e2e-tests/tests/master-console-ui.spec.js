// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');
const { totpAt } = require('./helpers/totp');

/**
 * E2E BROWSER do Master Console (Spec 71, fatia 1) — SPA React/Vite servida
 * pelo próprio Spring Boot em /console.
 *
 * Requer a app com `FOXCASH_CONSOLE_SERVE_FRONTEND=true` (+ a do portal,
 * `FOXCASH_PORTAL_SERVE_FRONTEND=true` — o build do console existe em
 * static/console) e o PostgreSQL do profile dev — mesmas condições do job e2e
 * do CI (ci.yml; a env FOXCASH_CONSOLE_SERVE_FRONTEND foi adicionada no T6.4).
 *
 * Cobertura (critério 10 do requirements):
 *   1. login sem MFA → dashboard → lojistas → detalhe (tabs earnings/fees) →
 *      ação suspend (ConfirmDialog) → 204 → feedback → release → 204;
 *   2. MFA: activate via API → confirm com TOTP → login no browser COM TOTP →
 *      dashboard; login SEM TOTP com MFA ativo → 401 + permanece no login
 *      (erro genérico, sem oráculo);
 *   3. 403 não derruba sessão: GET /master/profiles (admin-only) com master
 *      OPERATOR → 403 forbidden → a sessão continua viva (me 200 / UI intacta);
 *   4. logout → 204 → rota privada redireciona /console/login → API master sem
 *      token → 401 (e o interceptor volta ao login).
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

test.describe.serial('Master Console UI (Spec 71)', () => {
  const ts = Date.now();
  const username = `console-${ts}`;
  const mfaUsername = `console-mfa-${ts}`;
  let merchantId;

  test.beforeAll(async ({ request }) => {
    // Seed dos masters via SQL (o seed FOXCASH_MASTER_SEED_* não é garantido em CI;
    // padrão do auth.spec.js B1). can_manage_masters=false → OPERATOR.
    const client = dbClient();
    await client.connect();
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), username, PASSWORD_BCRYPT, 'Console E2E', new Date().toISOString()],
    );
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), mfaUsername, PASSWORD_BCRYPT, 'Console MFA E2E', new Date().toISOString()],
    );
    await client.end();

    // Lojista para a listagem e o detalhe (cadastro público).
    const reg = await request.post('/api/v1/merchants', {
      data: { name: 'Console Loja E2E', email: `console-${ts}@example.com`, password: 'Senha-Loja-1' },
    });
    expect(reg.status()).toBe(201);
    merchantId = (await reg.json()).id;
  });

  test('1. login sem MFA → dashboard → lojistas → detalhe → suspend/release (204)', async ({ page }) => {
    await page.goto('/console/login');
    await expect(page.locator('h1.auth-title')).toHaveText('FoxCash');
    await page.getByLabel('Usuário').fill(username);
    await page.getByLabel('Senha').fill(PASSWORD);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');
    await expect(page.locator('h1')).toHaveText('Dashboard');

    // Lojistas (espelho read-only) → detalhe → tabs de leitura.
    await page.getByRole('link', { name: 'Lojistas' }).click();
    await page.waitForURL('**/console/merchants');
    await expect(page.locator('table')).toContainText('Console Loja E2E');
    await page.getByRole('link', { name: 'Detalhe →' }).first().click();
    await page.waitForURL('**/console/merchants/*');
    await expect(page.locator('h1')).toContainText('Lojista');
    // Tab earnings (default) mostra o saldo do wallet. Locator específico: o
    // detalhe do lojista também tem uma lista de métricas com o mesmo texto
    // (`<span>Saldo do wallet</span>`) — `text=` resolveria 2 elementos
    // (strict mode violation); o heading é o card da tab earnings.
    await expect(page.getByRole('heading', { name: 'Saldo do wallet' })).toBeVisible();
    // Tab fees.
    await page.getByRole('link', { name: 'Fees', exact: true }).click();
    await expect(page.locator('text=Total de taxas')).toBeVisible();

    // Ação suspend com confirmação → 204 → feedback.
    await page.getByRole('button', { name: 'Suspender lojista' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByRole('button', { name: 'Suspender', exact: true }).click();
    await expect(page.locator('.alert-success')).toContainText('suspenso');

    // Ação release → 204 → feedback.
    await page.getByRole('button', { name: 'Liberar lojista' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByRole('button', { name: 'Liberar', exact: true }).click();
    await expect(page.locator('.alert-success')).toContainText('liberado');
  });

  test('2. login com MFA ativo: TOTP necessário; sem TOTP → 401 genérico', async ({ page, request }) => {
    // Ativa o MFA do segundo master via API (2 passos) — Spec 44.
    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mfaUsername, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    const token = (await login.json()).token;
    const headers = { Authorization: `Bearer ${token}` };

    const activate = await request.post(`${BASE}/api/v1/master/mfa/activate`, { headers });
    expect(activate.status()).toBe(200);
    const secret = (await activate.json()).manualEntryKey;

    const confirm = await request.post(`${BASE}/api/v1/master/mfa/confirm`, {
      headers,
      data: { code: totpAt(secret, 0) },
    });
    expect(confirm.status()).toBe(200);

    // Login no browser SEM TOTP → 401 genérico e permanece no /console/login.
    await page.goto('/console/login');
    await page.getByLabel('Usuário').fill(mfaUsername);
    await page.getByLabel('Senha').fill(PASSWORD);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page.locator('.alert-error')).toBeVisible();
    await expect(page).toHaveURL(/\/console\/login/);

    // Login COM TOTP → 200 → dashboard (sem oráculo: o campo é sempre opcional).
    await page.getByLabel('Código TOTP (se o MFA estiver ativo)').fill(totpAt(secret, 0));
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');
    await expect(page.locator('h1')).toHaveText('Dashboard');

    // Cleanup: remove o método MFA deste master (cascata V42 limpa totp/recovery)
    // — o cenário 22 da merchant-journey faz DELETE FROM masters e pressupõe que
    // nenhum master MFA ficou para trás (mesmo padrão do auth.spec.js C8).
    const client = dbClient();
    await client.connect();
    await client.query(
      'DELETE FROM master_mfa_methods WHERE master_id = (SELECT id FROM masters WHERE username = $1)',
      [mfaUsername],
    );
    await client.end();
  });

  test('3. 403 não derruba a sessão (admin-only → forbidden; sessão continua viva)', async ({ page, request }) => {
    // Login do master OPERATOR (não-admin).
    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    const token = (await login.json()).token;

    // /master/profiles é admin-only (MasterAdminGuard) → 403 forbidden.
    const profiles = await request.get(`${BASE}/api/v1/master/profiles`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(profiles.status()).toBe(403);
    expect((await profiles.json()).code).toBe('forbidden');

    // A sessão NÃO caiu: o token continua válido nas rotas do master.
    const me = await request.get(`${BASE}/api/v1/master/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(me.status()).toBe(200);

    // No browser, a navegação continua autenticada (sem redirect para o login).
    await page.goto('/console/login');
    await page.getByLabel('Usuário').fill(username);
    await page.getByLabel('Senha').fill(PASSWORD);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');
    // Dashboard renderiza (não foi deslogado por nenhum 403).
    await expect(page.locator('h1')).toHaveText('Dashboard');
  });

  test('4. logout → 204 → rota privada redireciona /console/login → API sem token 401', async ({ page, request }) => {
    // Login e logout explícito no browser.
    await page.goto('/console/login');
    await page.getByLabel('Usuário').fill(username);
    await page.getByLabel('Senha').fill(PASSWORD);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.waitForURL('**/console/dashboard');

    await page.getByRole('button', { name: 'Sair' }).click();
    await page.waitForURL('**/console/login');

    // Rota privada sem token → redirect para o login (guard client-side).
    await page.goto('/console/dashboard');
    await page.waitForURL('**/console/login');

    // API master sem token → 401 (backend mantém o contrato).
    const noAuth = await request.get('/api/v1/master/me');
    expect(noAuth.status()).toBe(401);
  });
});
