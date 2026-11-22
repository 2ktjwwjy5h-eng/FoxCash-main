// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');

/**
 * Spec 69 — RBAC do master em 2 níveis + step-up MFA (D8.1). Casos E2E novos
 * dos fluxos master sensíveis:
 *
 *   S1. 403 forbidden         — OPERATOR sem permissão (gestão de perfis).
 *   S2. 403 step_up_required  — master com permissão mas sem step-up (write-off).
 *   S3. Fluxo completo        — step-up real (TOTP) → repete a ação → 200.
 *   S4. Janela expirada       — stepped_up_at vencido → 403 step_up_required de novo.
 *   S5. change-password       — SEC-25: sessões revogadas → token antigo morto (401).
 *
 * O master é criado via SQL com a SESSÃO direta (padrão dos cenários 13/14/18
 * da merchant-journey) — evita o rate limit por IP do /auth/master/login (Spec
 * 43) e o MFA é ativado de verdade (TOTP real) para o step-up. A senha é um
 * BCrypt real de PASSWORD (o S5 troca a senha e exige a atual correta).
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

/**
 * TOTP RFC 6238 (SHA-1, 6 dígitos, passo 30s) — mesmo algoritmo do backend
 * (Totp.java, Spec 44) e da auth.spec.js. Usado para o step-up real.
 */
function totpCode(secretBase32, unixSeconds) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const bits = [];
  for (const ch of secretBase32.toUpperCase()) {
    const v = alphabet.indexOf(ch);
    if (v < 0) throw new Error(`base32 inválido: ${ch}`);
    for (let i = 4; i >= 0; i--) bits.push((v >> i) & 1);
  }
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    bytes.push(b);
  }
  const key = Buffer.from(bytes);
  const counter = Math.floor(unixSeconds / 30);
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const hash = nodeCrypto.createHmac('sha1', key).update(msg).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  const binary =
    ((hash[offset] & 0x7f) << 24) |
    ((hash[offset + 1] & 0xff) << 16) |
    ((hash[offset + 2] & 0xff) << 8) |
    (hash[offset + 3] & 0xff);
  return String(binary % 1000000).padStart(6, '0');
}

test.describe('Spec 69 — master RBAC 2 níveis + step-up MFA', () => {
  test.describe.configure({ mode: 'serial' });

  let operatorId;
  let operatorToken = '';
  let operatorSecret = '';
  let merchantId;
  let receivableA;
  let receivableB;

  test.beforeAll(async ({ request }) => {
    const suffix = Date.now();
    const masterRawToken = 'mst_' + nodeCrypto.randomBytes(16).toString('hex');
    operatorToken = masterRawToken;

    const client = dbClient();
    await client.connect();

    // Lojista + 2 receivables OPEN (write-off precisa de receivable/merchant reais
    // para o caso de SUCESSO — os 403 do step-up disparam antes das buscas).
    merchantId = nodeCrypto.randomUUID();
    await client.query(
      'INSERT INTO merchants (id, status, created_at) VALUES ($1,\'ACTIVE\',$2)',
      [merchantId, new Date().toISOString()],
    );
    receivableA = nodeCrypto.randomUUID();
    await client.query(
      `INSERT INTO receivables (id, merchant_id, origin, reference_id, amount, paid_amount,
         status, created_at, updated_at)
       VALUES ($1,$2,'CHARGEBACK',$3,80.00,0.00,'OPEN',$4,$4)`,
      [receivableA, merchantId, nodeCrypto.randomUUID(), new Date().toISOString()],
    );
    receivableB = nodeCrypto.randomUUID();
    await client.query(
      `INSERT INTO receivables (id, merchant_id, origin, reference_id, amount, paid_amount,
         status, created_at, updated_at)
       VALUES ($1,$2,'CHARGEBACK',$3,50.00,0.00,'OPEN',$4,$4)`,
      [receivableB, merchantId, nodeCrypto.randomUUID(), new Date().toISOString()],
    );

    // OPERATOR master (can_manage_masters=false) + sessão direta SEM step-up.
    const master = await client.query(
      `INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at)
       VALUES ($1,$2,$3,$4,true,false,$5) RETURNING id`,
      [nodeCrypto.randomUUID(), `stepup-op-${suffix}`, PASSWORD_BCRYPT, 'Master StepUp E2E',
        new Date().toISOString()],
    );
    operatorId = master.rows[0].id;
    await client.query(
      'INSERT INTO master_sessions (id, master_id, token_hash, created_at, expires_at, revoked_at, stepped_up_at) VALUES ($1,$2,$3,$4,$5,NULL,NULL)',
      [
        nodeCrypto.randomUUID(),
        operatorId,
        nodeCrypto.createHash('sha256').update(masterRawToken).digest('hex'),
        new Date().toISOString(),
        new Date(Date.now() + 3600_000).toISOString(),
      ],
    );
    await client.end();

    // Ativa o MFA de verdade (TOTP real) — o step-up exige re-prova de posse.
    const activate = await request.post(`${BASE}/api/v1/master/mfa/activate`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
    });
    expect(activate.status()).toBe(200);
    operatorSecret = (await activate.json()).manualEntryKey;
    expect(operatorSecret).toMatch(/^[A-Z2-7]{32}$/);

    const confirm = await request.post(`${BASE}/api/v1/master/mfa/confirm`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
      data: { code: totpCode(operatorSecret, Math.floor(Date.now() / 1000)) },
    });
    expect(confirm.status()).toBe(200);
  });

  test('S1. OPERATOR recebe 403 forbidden na gestão de perfis (sem can_manage_masters)', async ({ request }) => {
    // Critério 4: gestão de perfis (master.profiles.manage) é só ADMIN — o
    // OPERATOR autenticado recebe 403 forbidden (mesmo code do acesso negado
    // clássico — sem distinção ao atacante).
    const res = await request.post(`${BASE}/api/v1/master/profiles`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
      data: { username: `novo-${Date.now()}`, name: 'Novo' },
    });
    expect(res.status()).toBe(403);
    expect((await res.json()).code).toBe('forbidden');
  });

  test('S2. Ação sensível sem step-up → 403 step_up_required', async ({ request }) => {
    // Critério 6: write-off (receivable.write_off) é SENSÍVEL — o OPERATOR tem a
    // permissão (todo master ativo), mas sem re-prova de posse na sessão → 403
    // step_up_required (não 401 — a sessão é válida).
    const res = await request.post(`${BASE}/api/v1/master/receivables/${receivableA}/write-off`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
      data: { reason: 'Perda não coberta' },
    });
    expect(res.status()).toBe(403);
    expect((await res.json()).code).toBe('step_up_required');
  });

  test('S3. Fluxo completo: step-up (TOTP) → repete a ação → 200', async ({ request }) => {
    // Critério 6/8: POST /master/mfa/step-up valida o 2º fator (verifySecondFactor
    // — TOTP real) e grava stepped_up_at na sessão (janela 15 min); a repetição
    // da mesma ação sensível executa (200).
    const stepUp = await request.post(`${BASE}/api/v1/master/mfa/step-up`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
      data: { code: totpCode(operatorSecret, Math.floor(Date.now() / 1000)) },
    });
    expect(stepUp.status()).toBe(200);

    const writeOff = await request.post(`${BASE}/api/v1/master/receivables/${receivableA}/write-off`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
      data: { reason: 'Perda não coberta' },
    });
    expect(writeOff.status()).toBe(200);
  });

  test('S4. Janela expirada → 403 step_up_required de novo', async ({ request }) => {
    // Critério 7: expira a janela (15 min) via SQL — stepped_up_at no passado →
    // a MESMA ação sensível volta a exigir step-up. O receivableB segue OPEN: o
    // 403 vem do gate, não de estado.
    const client = dbClient();
    await client.connect();
    await client.query(
      "UPDATE master_sessions SET stepped_up_at = now() - interval '16 minutes' WHERE master_id = $1",
      [operatorId],
    );
    await client.end();

    const res = await request.post(`${BASE}/api/v1/master/receivables/${receivableB}/write-off`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
      data: { reason: 'Perda não coberta' },
    });
    expect(res.status()).toBe(403);
    expect((await res.json()).code).toBe('step_up_required');
  });

  test('S5. change-password → sessões revogadas → step-up morto (401)', async ({ request }) => {
    // Critério 9/T3.7: change-password (SEC-25) revoga TODAS as sessões do master
    // e limpa stepped_up_at — o token antigo morre no instante da troca: 401.
    const change = await request.post(`${BASE}/api/v1/auth/master/change-password`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
      data: { currentPassword: PASSWORD, newPassword: 'Nova-Senha-E2E-456' },
    });
    expect(change.status()).toBe(204);

    const res = await request.post(`${BASE}/api/v1/master/receivables/${receivableB}/write-off`, {
      headers: { Authorization: `Bearer ${operatorToken}` },
      data: { reason: 'Perda não coberta' },
    });
    expect(res.status()).toBe(401);
  });
});
