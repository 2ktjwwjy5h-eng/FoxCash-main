// @ts-check
const { test, expect } = require('@playwright/test');
const { totpAt } = require('./helpers/totp');

/**
 * Spec 94 — MFA TOTP no portal do lojista (auditoria de segurança 2026-08-14,
 * lacuna #1). Fluxo completo via API real:
 *
 *   M1. Login sem MFA → 200; `me.mfaActive === false`.
 *   M2. Ativar em 2 passos: activate → manualEntryKey; confirm com TOTP real →
 *       8 recovery codes `mrc_`.
 *   M3. Login com MFA ativo: sem TOTP → 401; TOTP errado → 401; TOTP correto
 *       → 200; recovery code → 200 (e reuso do mesmo recovery → 401).
 *   M4. `me.mfaActive === true`; desativar (senha + TOTP) → 204; logout →
 *       login só senha → 200; `me.mfaActive === false`.
 *
 * Requisitos: aplicação rodando (default http://localhost:8080) + PostgreSQL.
 * O global-setup limpa o banco antes da suíte (inclui as tabelas do MFA do
 * lojista desde a Spec 94).
 */

const BASE = process.env.API_BASE_URL || 'http://localhost:8080';

/** Senha do cadastro (Spec 39 — PasswordPolicy 8–72, maiúsc+minúsc+dígito). */
const PASSWORD = 'Senha-MFA-123';

test.describe('Spec 94 — MFA TOTP no portal do lojista', () => {
  test.describe.configure({ mode: 'serial' });

  let email;
  let token = '';
  let merchantId = '';
  let secret = '';
  /** @type {string[]} */
  let recoveryCodes = [];

  /** Faz login e guarda o token. */
  async function doLogin(request, totpCode) {
    const res = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      data: { email, password: PASSWORD, totpCode },
    });
    if (res.status() === 200) {
      token = (await res.json()).token;
    }
    return res;
  }

  test.beforeAll(async ({ request }) => {
    email = `mfa.e2e.${Date.now()}@example.com`;
    const res = await request.post(`${BASE}/api/v1/merchants`, {
      data: { name: 'Loja MFA E2E', email, password: PASSWORD },
    });
    expect(res.status()).toBe(201);
    const body = await res.json();
    merchantId = body.id;
  });

  test('M1. Login sem MFA — 200 e me.mfaActive=false', async ({ request }) => {
    const res = await doLogin(request);
    expect(res.status()).toBe(200);

    const me = await request.get(`${BASE}/api/v1/portal/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(me.status()).toBe(200);
    const meBody = await me.json();
    expect(meBody.merchantId).toBe(merchantId);
    expect(meBody.mfaActive).toBe(false);
  });

  test('M2. Ativar em 2 passos — activate → confirm com TOTP → 8 recovery codes', async ({ request }) => {
    const headers = { Authorization: `Bearer ${token}` };

    const activate = await request.post(`${BASE}/api/v1/portal/mfa/activate`, { headers });
    expect(activate.status()).toBe(200);
    const activateBody = await activate.json();
    secret = activateBody.manualEntryKey;
    expect(secret).toMatch(/^[A-Z2-7]{32}$/);
    expect(activateBody.otpauthUri).toContain('otpauth://totp/FoxCash:');
    expect(activateBody.otpauthUri).toContain(`secret=${secret}`);

    const confirm = await request.post(`${BASE}/api/v1/portal/mfa/confirm`, {
      headers,
      data: { code: totpAt(secret, 0) },
    });
    expect(confirm.status()).toBe(200);
    const confirmBody = await confirm.json();
    recoveryCodes = confirmBody.recoveryCodes;
    expect(recoveryCodes).toHaveLength(8);
    expect(recoveryCodes.every((c) => /^mrc_[0-9a-f]{32}$/.test(c))).toBe(true);
  });

  test('M3. Login com MFA ativo — sem/errado 401, TOTP certo e recovery 200', async ({ request }) => {
    // 3.1 Sem TOTP → 401 uniforme (sem oráculo).
    const noCode = await doLogin(request);
    expect(noCode.status()).toBe(401);

    // 3.2 TOTP errado → 401.
    const wrong = await doLogin(request, '000000');
    expect(wrong.status()).toBe(401);

    // 3.3 TOTP correto (janela ±1) → 200.
    const good = await doLogin(request, totpAt(secret, 0));
    expect(good.status()).toBe(200);

    // 3.4 Recovery code → 200 (single-use consumido atomicamente — SEC-20).
    const recovery = await doLogin(request, recoveryCodes[0]);
    expect(recovery.status()).toBe(200);

    // 3.5 Reuso do MESMO recovery code → 401 (single-use respeitado).
    const replay = await doLogin(request, recoveryCodes[0]);
    expect(replay.status()).toBe(401);
  });

  test('M4. me.mfaActive=true → desativar (senha + TOTP) → login só senha 200', async ({ request }) => {
    const meActive = await request.get(`${BASE}/api/v1/portal/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(meActive.status()).toBe(200);
    expect((await meActive.json()).mfaActive).toBe(true);

    // Desativar exige senha + 2º fator (posse) → 204.
    const deactivate = await request.post(`${BASE}/api/v1/portal/mfa/deactivate`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { code: totpAt(secret, 0), password: PASSWORD },
    });
    expect(deactivate.status()).toBe(204);

    // Logout → login só com senha volta a funcionar.
    await request.post(`${BASE}/api/v1/merchant-auth/logout`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    token = '';
    const relogin = await doLogin(request);
    expect(relogin.status()).toBe(200);

    const meOff = await request.get(`${BASE}/api/v1/portal/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(meOff.status()).toBe(200);
    expect((await meOff.json()).mfaActive).toBe(false);
  });
});
