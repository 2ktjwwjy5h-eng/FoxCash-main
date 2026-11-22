// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');
const { totpCode, totpAt } = require('./helpers/totp');

/**
 * Suíte de AUTENTICAÇÃO — cobertura E2E dedicada do subsistema de auth
 * (2026-08-07). Complementa a security.spec.js (ataques) e a merchant-journey
 * (fluxo funcional) com o contrato de autenticação em si:
 *
 *   Merchant (portal): login, logout, sessão expirada, senha errada,
 *   5 erros → lockout, Dummy BCrypt (anti-enumeração), troca de senha via
 *   reset (token one-time expirado/reutilizado), API Key com escopos e
 *   revogação.
 *
 *   Master: login, logout, sessão expirada, senha errada, 5 erros → lockout,
 *   Dummy BCrypt, MFA TOTP (ativação 2 passos, recovery codes, TOTP válido/
 *   inválido, replay, clock drift, lockout de TOTP, desativação).
 *
 * Requisitos: aplicação rodando (default http://localhost:8080) + PostgreSQL.
 * O global-setup (Playwright) limpa o banco antes da suíte.
 *
 * Atenção ao rate limit (Spec 43): /merchant-auth/* e /auth/master/login são
 * limitados por IP+rota a 20 req/60s. A suíte mantém cada rota abaixo do
 * teto por janela — entre a parte "master" e a "MFA" há um gap de
 * AUTH_MASTER_GAP_MS (default 65s) para a janela do /auth/master/login
 * expirar (as duas partes somariam >20 na mesma janela fixa de 60s).
 *
 * NOTA (execução local): o rate limit é em memória (store=memory) — ele
 * persiste entre execuções da suíte sem reiniciar a app. Re-rodar em menos de
 * 60s após a execução anterior pode estourar o teto (429) nos testes de
 * lockout (A5/B5). No CI a app sobe fresca e roda 1x — sem carryover. Para
 * re-execução local: reinicie a app (ou aguarde ~60s) antes de rodar.
 */

const BASE = process.env.API_BASE_URL || 'http://localhost:8080';

/** Senha conhecida cujo hash BCrypt está em PASSWORD_BCRYPT (cost 10). */
const PASSWORD = 'Senha-E2E-123';
const PASSWORD_BCRYPT = '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6';

/** Gap entre as descrições master e MFA (rate limit /auth/master/login). */
const MASTER_GAP_MS = Number(process.env.AUTH_MASTER_GAP_MS || 65000);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function dbClient() {
  return new Client({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    user: process.env.DB_USERNAME || 'nyxcash',
    password: process.env.DB_PASSWORD || 'nyxcash',
    database: process.env.DB_NAME || 'nyxcash',
  });
}

test.describe('Auth — merchant: login, sessão, senha e API key', () => {
  test.describe.configure({ mode: 'serial' });

  let merchantId;
  let apiKey;
  let email;
  let password = PASSWORD;

  test.beforeAll(async ({ request }) => {
    email = `auth.e2e.${Date.now()}@example.com`;
    const res = await request.post(`${BASE}/api/v1/merchants`, {
      data: { name: 'Loja Auth E2E', email, password },
    });
    expect(res.status()).toBe(201);
    const body = await res.json();
    merchantId = body.id;
    apiKey = body.apiKey;
    expect(apiKey).toMatch(/^mch_[0-9a-f]{32}$/);
  });

  test('A1. Login OK — token opaco mst_<32hex>, expiresAt ~8h', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.token).toMatch(/^mst_[0-9a-f]{32}$/);
    expect(body.merchantId).toBe(merchantId);
    expect(body.name).toBe('Loja Auth E2E');
    const ttlMs = new Date(body.expiresAt).getTime() - Date.now();
    expect(ttlMs).toBeGreaterThan(7 * 3600 * 1000); // sessão ~8h (Spec 20)
    expect(ttlMs).toBeLessThan(9 * 3600 * 1000);
  });

  test('A2. Logout — 204 e o token fica revogado (401 no portal)', async ({ request }) => {
    const login = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const token = (await login.json()).token;

    const logout = await request.post(`${BASE}/api/v1/merchant-auth/logout`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(logout.status()).toBe(204);

    const me = await request.get(`${BASE}/api/v1/portal/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(me.status()).toBe(401);
  });

  test('A3. Sessão expirada — token válido com expires_at no passado → 401', async ({ request }) => {
    const login = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const token = (await login.json()).token;

    // Expira a sessão no banco (janela real de 8h inesperável no E2E).
    const client = dbClient();
    await client.connect();
    await client.query(
      "UPDATE merchant_sessions SET expires_at = now() - interval '1 minute' WHERE merchant_id = $1",
      [merchantId],
    );
    await client.end();

    const me = await request.get(`${BASE}/api/v1/portal/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(me.status()).toBe(401);
  });

  test('A4. Senha errada — 401 unauthorized uniforme', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password: 'senha-errada-xyz' },
    });
    expect(res.status()).toBe(401);
    expect((await res.json()).code).toBe('unauthorized');
  });

  test('A5. 5 erros → lockout — 6ª tentativa com a senha CORRETA também 401; janela expira e login volta', async ({ request }) => {
    // 5 falhas seguidas → a conta é bloqueada (B-06, V35).
    for (let i = 0; i < 5; i++) {
      const res = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
        data: { email, password: `errada-${i}` },
      });
      expect(res.status()).toBe(401);
    }

    // Durante a janela de lockout, até a senha correta responde 401 uniforme
    // (sem oráculo — o atacante não sabe se a senha está certa).
    const locked = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password },
    });
    expect(locked.status()).toBe(401);
    expect((await locked.json()).code).toBe('unauthorized');

    // Banco: janela de lockout ATIVA. Nota: o contador é ZERADO ao atingir o
    // limite (B-06/V35) — após a janela o lojista tem tentativas novas; a
    // evidência do lockout é o locked_until setado.
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      'SELECT failed_login_attempts, locked_until FROM merchant_credentials WHERE merchant_id = $1',
      [merchantId],
    );
    expect(rows[0].locked_until).toBeTruthy();
    expect(rows[0].failed_login_attempts).toBe(0);

    // Simula a expiração da janela (15min) — o lockout expira sozinho (B-06):
    // a senha correta volta a funcionar.
    await client.query(
      'UPDATE merchant_credentials SET failed_login_attempts = 0, locked_until = NULL WHERE merchant_id = $1',
      [merchantId],
    );
    await client.end();

    const ok = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password },
    });
    expect(ok.status()).toBe(200);
  });

  test('A6. Dummy BCrypt — e-mail inexistente e senha errada: 401 idêntico e TEMPO COMPARÁVEL (não é short-circuit)', async ({ request }) => {
    // B-01: usuário inexistente executa um BCrypt dummy — a resposta não pode
    // ser mais rápida (oráculo de enumeração) nem diferente (code/status).
    const t0 = performance.now();
    const nonexistent = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email: 'nao-existe@auth.e2e', password: 'qualquer-123' },
    });
    const tNonexistent = performance.now() - t0;

    const t1 = performance.now();
    const wrongPass = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password: 'senha-errada-xyz' },
    });
    const tWrong = performance.now() - t1;

    expect(nonexistent.status()).toBe(401);
    expect(wrongPass.status()).toBe(401);
    const b1 = await nonexistent.json();
    const b2 = await wrongPass.json();
    expect(b1.code).toBe('unauthorized');
    expect(b2.code).toBe('unauthorized');

    // BCrypt cost-10 leva ~50-100ms; um short-circuit ("e-mail não existe")
    // retornaria em poucos ms. O dummy garante tempos da MESMA ordem.
    expect(tNonexistent).toBeGreaterThan(10);
    expect(tWrong).toBeGreaterThan(10);
    const ratio = Math.max(tNonexistent, tWrong) / Math.max(1, Math.min(tNonexistent, tWrong));
    expect(ratio).toBeLessThan(5);
  });

  test('A7. Token de reset expirado — forgot ok, expires_at no passado → 400 invalid_token', async ({ request }) => {
    const forgot = await request.post(`${BASE}/api/v1/merchant-auth/forgot-password`, {
      data: { email },
    });
    expect(forgot.status()).toBe(200); // dev: expose-reset-token=true
    const resetToken = (await forgot.json()).resetToken;
    expect(resetToken).toMatch(/^rst_[0-9a-f]{32}$/);

    // Expira o token (TTL real de 30min inesperável no E2E).
    const client = dbClient();
    await client.connect();
    await client.query(
      "UPDATE password_reset_tokens SET expires_at = now() - interval '1 minute' WHERE merchant_id = $1",
      [merchantId],
    );
    await client.end();

    const reset = await request.post(`${BASE}/api/v1/merchant-auth/reset-password`, {
      data: { token: resetToken, newPassword: 'Nova-Senha-Auth-456' },
    });
    expect(reset.status()).toBe(400);
    expect((await reset.json()).code).toBe('invalid_token');
  });

  test('A8. Reset de senha — troca, token single-use, sessões revogadas e hash BCrypt novo no banco', async ({ request }) => {
    const newPassword = 'Nova-Senha-Auth-789';

    // Sessão ativa (será revogada pelo reset).
    const oldLogin = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password },
    });
    expect(oldLogin.status()).toBe(200);
    const oldToken = (await oldLogin.json()).token;

    const forgot = await request.post(`${BASE}/api/v1/merchant-auth/forgot-password`, {
      data: { email },
    });
    expect(forgot.status()).toBe(200);
    const resetToken = (await forgot.json()).resetToken;

    // Banco: só o hash SHA-256 do token (nunca em claro).
    const client = dbClient();
    await client.connect();
    const hashRows = await client.query(
      'SELECT token_hash FROM password_reset_tokens WHERE merchant_id = $1',
      [merchantId],
    );
    expect(hashRows.rows[0].token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(hashRows.rows[0].token_hash).not.toContain(resetToken);
    const credBefore = await client.query(
      'SELECT password_hash FROM merchant_credentials WHERE merchant_id = $1',
      [merchantId],
    );
    const oldPasswordHash = credBefore.rows[0].password_hash;
    await client.end();

    // Reset → 204.
    const reset = await request.post(`${BASE}/api/v1/merchant-auth/reset-password`, {
      data: { token: resetToken, newPassword },
    });
    expect(reset.status()).toBe(204);

    // Senha antiga → 401; nova → 200.
    const oldPass = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password },
    });
    expect(oldPass.status()).toBe(401);
    const newPass = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password: newPassword },
    });
    expect(newPass.status()).toBe(200);

    // Token reutilizado (single-use) → 400 invalid_token.
    const reuse = await request.post(`${BASE}/api/v1/merchant-auth/reset-password`, {
      data: { token: resetToken, newPassword },
    });
    expect(reuse.status()).toBe(400);
    expect((await reuse.json()).code).toBe('invalid_token');

    // Sessão antiga revogada → 401 (Spec 40: reset revoga todas as sessões).
    const oldSession = await request.get(`${BASE}/api/v1/portal/me`, {
      headers: { Authorization: `Bearer ${oldToken}` },
    });
    expect(oldSession.status()).toBe(401);

    // Hash BCrypt trocado no banco (diferente do anterior).
    const client2 = dbClient();
    await client2.connect();
    const credAfter = await client2.query(
      'SELECT password_hash FROM merchant_credentials WHERE merchant_id = $1',
      [merchantId],
    );
    await client2.end();
    expect(credAfter.rows[0].password_hash).toMatch(/^\$2[aby]\$\d{2}\$/); // BCrypt
    expect(credAfter.rows[0].password_hash).not.toBe(oldPasswordHash);
    expect(credAfter.rows[0].password_hash).not.toContain(newPassword);

    // Próximos testes usam a senha nova.
    password = newPassword;
  });

  test('A9. API Key — escopos (transactions ok / charges 403) e revogação → 401', async ({ request }) => {
    // Login (senha nova após A8) p/ gerir credenciais no portal.
    const login = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const portalToken = (await login.json()).token;
    const portalHeaders = { Authorization: `Bearer ${portalToken}` };

    // Cria chave com escopo RESTRITO (transactions) — Spec 24.
    const create = await request.post(`${BASE}/api/v1/portal/credentials`, {
      headers: portalHeaders,
      data: { label: 'Auth scoped', scopes: ['transactions'] },
    });
    expect(create.status()).toBe(201);
    const created = await create.json();
    expect(created.secret).toMatch(/^mch_[0-9a-f]{32}$/);
    const scopedKey = created.secret;

    // Escopo transactions → GET /transactions/merchant/{id} 200.
    const tx = await request.get(`${BASE}/api/v1/transactions/merchant/${merchantId}`, {
      headers: { 'X-Api-Key': scopedKey },
    });
    expect(tx.status()).toBe(200);

    // Sem escopo charges → POST /foxcash/pix/cob 403 forbidden (no filtro, antes do corpo).
    const cob = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': scopedKey },
      data: { merchantId, amount: '10.00', idempotencyKey: `auth-scope-${Date.now()}` },
    });
    expect(cob.status()).toBe(403);
    expect((await cob.json()).code).toBe('forbidden');

    // Revoga → 204; a chave revogada → 401 (Spec 24).
    const revoke = await request.post(`${BASE}/api/v1/portal/credentials/${created.id}/revoke`, {
      headers: portalHeaders,
    });
    expect(revoke.status()).toBe(204);

    const afterRevoke = await request.get(`${BASE}/api/v1/transactions/merchant/${merchantId}`, {
      headers: { 'X-Api-Key': scopedKey },
    });
    expect(afterRevoke.status()).toBe(401);

    // A chave do cadastro (escopo total) continua válida (Spec 24).
    const legacy = await request.get(`${BASE}/api/v1/transactions/merchant/${merchantId}`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(legacy.status()).toBe(200);
  });
});

test.describe('Auth — master: login, sessão e lockout', () => {
  test.describe.configure({ mode: 'serial' });

  let masterId;
  let username;

  test.beforeAll(async () => {
    // Master criado via SQL (o seed FOXCASH_MASTER_SEED_* não é garantido em CI;
    // padrão dos cenários 13/14/27 da journey). BCrypt real de PASSWORD.
    username = `auth-master-${Date.now()}`;
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5) RETURNING id`,
      [nodeCrypto.randomUUID(), username, PASSWORD_BCRYPT, 'Master Auth E2E', new Date().toISOString()],
    );
    masterId = rows[0].id;
    await client.end();
  });

  test('B1. Login OK — token mst_, expiresAt ~8h', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: PASSWORD },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.token).toMatch(/^mst_[0-9a-f]{32}$/);
    expect(body.masterId).toBe(masterId);
    const ttlMs = new Date(body.expiresAt).getTime() - Date.now();
    expect(ttlMs).toBeGreaterThan(7 * 3600 * 1000);
    expect(ttlMs).toBeLessThan(9 * 3600 * 1000);
  });

  test('B2. Logout — 204 e o token fica revogado (401 em rota master)', async ({ request }) => {
    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    const token = (await login.json()).token;

    const logout = await request.post(`${BASE}/api/v1/auth/master/logout`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(logout.status()).toBe(204);

    const merchants = await request.get(`${BASE}/api/v1/master/merchants`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(merchants.status()).toBe(401);
  });

  test('B3. Sessão expirada — expires_at no passado → 401', async ({ request }) => {
    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    const token = (await login.json()).token;

    const client = dbClient();
    await client.connect();
    await client.query(
      "UPDATE master_sessions SET expires_at = now() - interval '1 minute' WHERE master_id = $1",
      [masterId],
    );
    await client.end();

    const merchants = await request.get(`${BASE}/api/v1/master/merchants`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(merchants.status()).toBe(401);
  });

  test('B4. Senha errada — 401 unauthorized uniforme', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: 'senha-errada-xyz' },
    });
    expect(res.status()).toBe(401);
    expect((await res.json()).code).toBe('unauthorized');
  });

  test('B5. 5 erros → lockout — 6ª tentativa com a senha CORRETA também 401; janela expira e login volta', async ({ request }) => {
    for (let i = 0; i < 5; i++) {
      const res = await request.post(`${BASE}/api/v1/auth/master/login`, {
        data: { username, password: `errada-${i}` },
      });
      expect(res.status()).toBe(401);
    }

    // Durante a janela, até a senha correta responde 401 uniforme (B-08).
    const locked = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: PASSWORD },
    });
    expect(locked.status()).toBe(401);
    expect((await locked.json()).code).toBe('unauthorized');

    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      'SELECT failed_login_attempts, locked_until FROM masters WHERE id = $1',
      [masterId],
    );
    // Mesmo padrão B-06: o contador zera ao atingir o limite; a evidência do
    // lockout é o locked_until setado.
    expect(rows[0].locked_until).toBeTruthy();
    expect(rows[0].failed_login_attempts).toBe(0);

    // Expiração da janela (15min) — o lockout expira sozinho (B-08).
    await client.query(
      'UPDATE masters SET failed_login_attempts = 0, locked_until = NULL WHERE id = $1',
      [masterId],
    );
    await client.end();

    const ok = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: PASSWORD },
    });
    expect(ok.status()).toBe(200);
  });

  test('B6. Dummy BCrypt — usuário inexistente e senha errada: 401 idêntico e TEMPO COMPARÁVEL', async ({ request }) => {
    const t0 = performance.now();
    const nonexistent = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: 'nao-existe-auth', password: 'qualquer-123' },
    });
    const tNonexistent = performance.now() - t0;

    const t1 = performance.now();
    const wrongPass = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username, password: 'senha-errada-xyz' },
    });
    const tWrong = performance.now() - t1;

    expect(nonexistent.status()).toBe(401);
    expect(wrongPass.status()).toBe(401);
    const b1 = await nonexistent.json();
    const b2 = await wrongPass.json();
    expect(b1.code).toBe('unauthorized');
    expect(b2.code).toBe('unauthorized');

    // B-01: o BCrypt dummy roda TAMBÉM para usuário inexistente — tempos da
    // mesma ordem (sem oráculo de enumeração por timing).
    expect(tNonexistent).toBeGreaterThan(10);
    expect(tWrong).toBeGreaterThan(10);
    const ratio = Math.max(tNonexistent, tWrong) / Math.max(1, Math.min(tNonexistent, tWrong));
    expect(ratio).toBeLessThan(5);
  });
});

test.describe('Auth — master MFA: TOTP, recovery, replay, clock drift e lockout', () => {
  test.describe.configure({ mode: 'serial' });

  let mainMasterId;
  let mainUsername;
  let mainSecret = '';
  let mainToken = '';
  let mainRecoveryCodes = [];
  let lockoutUsername;
  let lockoutSecret = '';

  test.beforeAll(async ({ request }) => {
    // O gap de 65s + setup excede o timeout default do hook (30s).
    test.setTimeout(120_000);

    // Gap p/ o rate limit do /auth/master/login (20 req/60s, Spec 43): a parte
    // "master" (B) e a "MFA" (C) somariam >20 na mesma janela fixa — a janela
    // expira 60s após a 1ª requisição, então um sono de 65s zera o contador.
    await sleep(MASTER_GAP_MS);

    const suffix = Date.now();
    mainUsername = `auth-mfa-main-${suffix}`;
    lockoutUsername = `auth-mfa-lock-${suffix}`;

    const client = dbClient();
    await client.connect();
    const main = await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5) RETURNING id`,
      [nodeCrypto.randomUUID(), mainUsername, PASSWORD_BCRYPT, 'Master MFA E2E', new Date().toISOString()],
    );
    mainMasterId = main.rows[0].id;
    await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1, $2, $3, $4, true, false, $5)`,
      [nodeCrypto.randomUUID(), lockoutUsername, PASSWORD_BCRYPT, 'Master TOTP Lockout E2E', new Date().toISOString()],
    );
    await client.end();

    // Login real do master principal (sem MFA ainda) → token p/ ativar o MFA.
    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD },
    });
    expect(login.status()).toBe(200);
    mainToken = (await login.json()).token;

    // Ativação passo 1: secret + URI do QR (devolvidos 1x).
    const activate = await request.post(`${BASE}/api/v1/master/mfa/activate`, {
      headers: { Authorization: `Bearer ${mainToken}` },
    });
    expect(activate.status()).toBe(200);
    const act = await activate.json();
    expect(act.otpauthUri).toContain('otpauth://totp/FoxCash:');
    expect(act.manualEntryKey).toMatch(/^[A-Z2-7]{32}$/);
    mainSecret = act.manualEntryKey;

    // Ativação passo 2: TOTP calculado no teste → confirma posse + 8 recovery.
    const confirm = await request.post(`${BASE}/api/v1/master/mfa/confirm`, {
      headers: { Authorization: `Bearer ${mainToken}` },
      data: { code: totpCode(mainSecret, Math.floor(Date.now() / 1000)) },
    });
    expect(confirm.status()).toBe(200);
    mainRecoveryCodes = (await confirm.json()).recoveryCodes;
    expect(mainRecoveryCodes).toHaveLength(8);

    // Mesma ativação para o master de lockout de TOTP (independente — o
    // lockout não pode contaminar o master principal).
    const lockLogin = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: lockoutUsername, password: PASSWORD },
    });
    expect(lockLogin.status()).toBe(200);
    const lockToken = (await lockLogin.json()).token;
    const lockAct = await request.post(`${BASE}/api/v1/master/mfa/activate`, {
      headers: { Authorization: `Bearer ${lockToken}` },
    });
    expect(lockAct.status()).toBe(200);
    lockoutSecret = (await lockAct.json()).manualEntryKey;
    const lockConfirm = await request.post(`${BASE}/api/v1/master/mfa/confirm`, {
      headers: { Authorization: `Bearer ${lockToken}` },
      data: { code: totpCode(lockoutSecret, Math.floor(Date.now() / 1000)) },
    });
    expect(lockConfirm.status()).toBe(200);
  });

  test('C1. Secret TOTP criptografado em repouso (v1:) no banco — nunca em claro', async () => {
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      'SELECT m.status, t.secret_encrypted FROM master_mfa_methods m JOIN master_totp t ON t.method_id = m.id WHERE m.master_id = $1',
      [mainMasterId],
    );
    await client.end();
    expect(rows[0].status).toBe('ACTIVE');
    expect(rows[0].secret_encrypted).toMatch(/^v1:/);
    expect(rows[0].secret_encrypted).not.toContain(mainSecret);
  });

  test('C2. Login SEM código — senha correta não basta → 401', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD },
    });
    expect(res.status()).toBe(401);
    expect((await res.json()).code).toBe('unauthorized');
  });

  test('C3. Login com TOTP válido → 200', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: totpAt(mainSecret, 0) },
    });
    expect(res.status()).toBe(200);
    expect((await res.json()).token).toMatch(/^mst_/);
  });

  test('C4. Login com TOTP inválido → 401 uniforme (sem oráculo)', async ({ request }) => {
    const valid = totpAt(mainSecret, 0);
    const invalid = valid === '000000' ? '000001' : '000000';
    const res = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: invalid },
    });
    expect(res.status()).toBe(401);
    expect((await res.json()).code).toBe('unauthorized');
  });

  test('C5. Recovery code — single-use: 200 na 1ª, 401 no reuso; usado no banco', async ({ request }) => {
    const recovery = mainRecoveryCodes[0];
    expect(recovery).toMatch(/^mrc_[0-9a-f]{32}$/);

    const ok = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: recovery },
    });
    expect(ok.status()).toBe(200);
    expect((await ok.json()).token).toMatch(/^mst_/);

    const reuse = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: recovery },
    });
    expect(reuse.status()).toBe(401);

    // Banco: recovery code marcado como usado (SHA-256 do plaintext).
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      'SELECT used_at FROM master_recovery_codes WHERE code_hash = $1',
      [nodeCrypto.createHash('sha256').update(recovery).digest('hex')],
    );
    await client.end();
    expect(rows[0].used_at).toBeTruthy();
  });

  test('C6. TOTP replay — mesmo código usado 2x → 401 (anti-replay por last_code_hash)', async ({ request }) => {
    // Código do PASSO ATUAL (offset 0): fica dentro da janela ±1 mesmo se a
    // requisição cruzar a fronteira de passo entre a geração e a validação
    // (com -30 o código caía 2 passos atrás do passo de validação → 401
    // flaky). Roda ANTES do clock-drift (C7), então last_code_hash ainda está
    // nulo (C5 usou recovery code) → sem risco de colisão com código anterior;
    // o 2º envio do MESMO código é replay → 401.
    const code = totpAt(mainSecret, 0);
    const first = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: code },
    });
    expect(first.status()).toBe(200);

    // O MESMO código (mesmo passo) de novo → replay → 401.
    const second = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: code },
    });
    expect(second.status()).toBe(401);
    expect((await second.json()).code).toBe('unauthorized');
  });

  test('C7. Clock drift — +1 passo (skew pequeno) aceito; ±3 passos rejeitados', async ({ request }) => {
    // +30s (1 passo à frente) → dentro da janela ±1 (RFC 6238) → 200. O
    // last_code_hash do C6 é do passo ATUAL, sempre 1 passo atrás do +30 →
    // nunca replay.
    const ahead = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: totpAt(mainSecret, 30) },
    });
    expect(ahead.status()).toBe(200);

    // +90s (3 passos à frente) e -90s (3 passos atrás) → fora da janela → 401.
    const farAhead = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: totpAt(mainSecret, 90) },
    });
    expect(farAhead.status()).toBe(401);

    const farBehind = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: totpAt(mainSecret, -90) },
    });
    expect(farBehind.status()).toBe(401);
  });

  test('C8. Lockout de TOTP — 5 erros → 6ª tentativa com código VÁLIDO também 401', async ({ request }) => {
    // 5 códigos errados seguidos no master de lockout.
    for (let i = 0; i < 5; i++) {
      const res = await request.post(`${BASE}/api/v1/auth/master/login`, {
        data: { username: lockoutUsername, password: PASSWORD, totpCode: String(100000 + i) },
      });
      expect(res.status()).toBe(401);
    }

    // Durante o lockout, até o código correto responde 401 uniforme.
    const locked = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: lockoutUsername, password: PASSWORD, totpCode: totpAt(lockoutSecret, 30) },
    });
    expect(locked.status()).toBe(401);
    expect((await locked.json()).code).toBe('unauthorized');

    // Banco: 5 falhas + janela de lockout de TOTP ativa.
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      `SELECT t.failed_attempts, t.locked_until
       FROM master_totp t JOIN master_mfa_methods m ON m.id = t.method_id
       WHERE m.master_id = (SELECT id FROM masters WHERE username = $1)`,
      [lockoutUsername],
    );
    expect(rows[0].failed_attempts).toBe(5);
    expect(rows[0].locked_until).toBeTruthy();

    // Expira a janela (15min) — lockout de TOTP expira sozinho (sem unlock
    // manual, Spec 44). Limpeza silenciosa p/ não afetar as suítes seguintes.
    await client.query(
      `UPDATE master_totp SET failed_attempts = 0, locked_until = NULL
       WHERE method_id = (SELECT id FROM master_mfa_methods m WHERE m.master_id =
         (SELECT id FROM masters WHERE username = $1))`,
      [lockoutUsername],
    );
    // Remove o método MFA deste master (cascata V42 limpa totp/recovery): sem
    // isso, o cenário 22 da merchant-journey (DELETE FROM masters) viola a FK
    // master_mfa_methods.master_id — a journey pressupõe que todo master MFA
    // foi desativado (cascata) antes da limpeza.
    await client.query(
      'DELETE FROM master_mfa_methods WHERE master_id = (SELECT id FROM masters WHERE username = $1)',
      [lockoutUsername],
    );
    await client.end();
  });

  test('C9. Desativação — senha + TOTP → 204; login volta ao fluxo simples', async ({ request }) => {
    // Reset do anti-replay: login com recovery code (single-use) zera o
    // last_code_hash deixado pelo C7 (clock drift, passo +30). Sem o reset, o
    // TOTP do deactivate colidiria com esse hash quando C7 e C9 rodam no mesmo
    // passo (replay falso → 401). Depois do reset, o código do PASSO ATUAL
    // (offset 0) é aceito sem replay e é imune à corrida de fronteira de passo.
    const reset = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD, totpCode: mainRecoveryCodes[1] },
    });
    expect(reset.status()).toBe(200);

    const deactivate = await request.post(`${BASE}/api/v1/master/mfa/deactivate`, {
      headers: { Authorization: `Bearer ${mainToken}` },
      data: { code: totpAt(mainSecret, 0), password: PASSWORD },
    });
    expect(deactivate.status()).toBe(204);

    // Sem MFA ativo, login volta a exigir só a senha.
    const login = await request.post(`${BASE}/api/v1/auth/master/login`, {
      data: { username: mainUsername, password: PASSWORD },
    });
    expect(login.status()).toBe(200);

    // Banco: método removido em cascata (V42).
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      'SELECT COUNT(*) AS n FROM master_mfa_methods WHERE master_id = $1',
      [mainMasterId],
    );
    await client.end();
    expect(Number(rows[0].n)).toBe(0);
  });
});
