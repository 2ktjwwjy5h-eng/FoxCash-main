// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const nodeCrypto = require('crypto');
const http = require('http');

/**
 * Jornada completa do lojista (E2E): cadastro -> cobranca Pix -> webhook de
 * pagamento -> previsao da fatura -> geracao da fatura.
 *
 * Requisitos: aplicacao rodando (http://localhost:8080) e PostgreSQL acessivel.
 * O global-setup limpa o banco antes de rodar.
 *
 * Autenticacao (Spec 12): o cadastro emite a chave de API (header X-Api-Key)
 * nas chamadas subsequentes. O webhook (/webhooks/pix) e publico (assinatura
 * HMAC e a autenticacao do PSP).
 */

const currentMonth = new Date().toISOString().slice(0, 7); // yyyy-MM (UTC)

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
 * (Totp.java, Spec 44). Usado no cenário MFA para calcular o código esperado
 * a partir do manualEntryKey devolvido no activate.
 */
function totpCode(secretBase32, unixSeconds) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  // base32 → bytes (RFC 4648, sem padding).
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

test.describe('Jornada do lojista (FoxCash)', () => {
  test.describe.configure({ mode: 'serial' });

  let merchantId;
  let apiKey;
  let email;
  let password;
  let transactionId;
  let externalReference;

  test('1. Cadastro de lojista — e-mail/senha escolhida pelo usuário + apiKey emitida (hash no banco)', async ({ request }) => {
    email = 'loja.e2e@example.com';
    password = 'Senha-E2E-123';

    const res = await request.post('/api/v1/merchants', {
      data: { name: 'Loja E2E', email, password },
    });
    expect(res.status()).toBe(201);

    const body = await res.json();
    expect(body.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
    merchantId = body.id;

    // Spec 12: a chave de API e devolvida UMA unica vez no cadastro (mch_<32 hex>)
    expect(body.apiKey).toMatch(/^mch_[0-9a-f]{32}$/);
    apiKey = body.apiKey;

    // Spec 39: a senha é escolhida pelo usuário no cadastro — nunca é devolvida.
    expect(body.email).toBe(email);
    expect(body.password).toBeUndefined();

    // Validacao direta no banco: novo cadastro SEM documento (Spec 39 — opcional),
    // e-mail normalizado em minusculas e apenas o hash da chave persistido.
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      'SELECT document, email, api_key_hash FROM merchants WHERE id = $1',
      [merchantId],
    );
    // Spec 39: a senha escolhida pelo usuario fica apenas como hash BCrypt na
    // credencial do portal (nunca o texto claro).
    const cred = await client.query(
      'SELECT password_hash FROM merchant_credentials WHERE merchant_id = $1',
      [merchantId],
    );
    // Duplicado por e-mail (identificador único) → 409 merchant_already_exists.
    const dup = await request.post('/api/v1/merchants', {
      data: { name: 'Loja E2E Duplicada', email, password },
    });
    expect(dup.status()).toBe(409);
    expect((await dup.json()).code).toBe('merchant_already_exists');
    await client.end();

    expect(rows).toHaveLength(1);
    expect(rows[0].document).toBeNull();                     // novo cadastro sem CPF/CNPJ
    expect(rows[0].email).toBe(email);                       // normalizado em minusculas
    expect(rows[0].api_key_hash).toMatch(/^[0-9a-f]{64}$/);  // SHA-256 hex
    expect(rows[0].api_key_hash).not.toBe(apiKey);           // nunca o texto claro
    expect(cred.rows).toHaveLength(1);
    expect(cred.rows[0].password_hash).toMatch(/^\$2[aby]\$\d{2}\$/); // BCrypt
    expect(cred.rows[0].password_hash).not.toContain(password);       // nunca em claro
  });

  test('2. Gera cobranca Pix — QR Code e Pix Copia e Cola (sem dados do comprador)', async ({ request }) => {
    const res = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: {
        merchantId,
        amount: 150.0,
        buyerId: 'buyer-e2e-001', // pseudonimo opaco do bot (sem PII; apenas hash SHA-256 persistido)
        expirationSeconds: 3600,
        idempotencyKey: 'e2e-cob-001', // Spec 28: obrigatoria e unica por lojista
      },
    });
    expect(res.status()).toBe(201);

    const body = await res.json();
    expect(body.transactionId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(body.externalReference).toBeTruthy();
    expect(body.externalReference).toMatch(/^E[0-9A-F]{32}$/); // endToEndId fake
    expect(body.pixQrCode).toBeTruthy();                       // base64
    expect(body.pixCopyPaste).toContain('000201');             // BR Code
    expect(body.status).toBe('PENDING');
    expect(body.createdAt).toBeTruthy();

    transactionId = body.transactionId;
    externalReference = body.externalReference;
  });

  test('2b. Idempotência (Spec 28) — replay da mesma chave devolve a MESMA cobrança', async ({ request }) => {
    // Replay com a mesma idempotencyKey do cenário 2 → mesma transação (nunca duplica).
    const replay = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: {
        merchantId,
        amount: 150.0,
        buyerId: 'buyer-e2e-001',
        expirationSeconds: 3600,
        idempotencyKey: 'e2e-cob-001',
      },
    });
    expect(replay.status()).toBe(201);
    const replayBody = await replay.json();
    expect(replayBody.transactionId).toBe(transactionId);

    // Chave ausente → 400 (contrato obrigatório da Spec 28).
    const missing = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: { merchantId, amount: 10.0, expirationSeconds: 3600 },
    });
    expect(missing.status()).toBe(400);
    expect((await missing.json()).code).toBe('validation_error');

    // Mesma chave em OUTRO lojista → permitida (unicidade é por lojista).
    const other = await request.post('/api/v1/merchants', {
      data: { name: 'Loja E2E B', email: 'loja.e2eb@example.com', password },
    });
    expect(other.status()).toBe(201);
    const otherBody = await other.json();
    const otherCharge = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': otherBody.apiKey },
      data: {
        merchantId: otherBody.id,
        amount: 5.0,
        expirationSeconds: 3600,
        idempotencyKey: 'e2e-cob-001', // MESMA chave do lojista A → permitida (por lojista)
      },
    });
    expect(otherCharge.status()).toBe(201);
    const otherChargeBody = await otherCharge.json();
    expect(otherChargeBody.transactionId).not.toBe(transactionId);
  });

  test('3. Confirma pagamento via webhook — status vira APPROVED', async ({ request }) => {
    // Webhook e publico (nao usa X-Api-Key) — a assinatura HMAC e a autenticacao.
    const res = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-e2e-1',
        externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 150.0,
      },
    });
    expect(res.status()).toBe(204);

    const tx = await (await request.get(`/api/v1/transactions/${transactionId}`, {
      headers: { 'X-Api-Key': apiKey },
    })).json();
    expect(tx.status).toBe('APPROVED'); // PAID (PSP) normalizado para APPROVED
    expect(tx.paidAt).toBeTruthy();
    expect(tx.endToEndId).toBe(externalReference);
  });

  test('4. Previa da fatura reflete as transacoes confirmadas', async ({ request }) => {
    const res = await request.get(`/api/v1/billing/preview?merchantId=${merchantId}&month=${currentMonth}`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(res.status()).toBe(200);

    const body = await res.json();
    expect(body.totalTransactions).toBeGreaterThanOrEqual(1);
    expect(body.variableFee).toBe(Number((body.totalTransactions * 0.5).toFixed(2)));
    expect(body.fixedFee).toBe(69.95);
    expect(body.totalDue).toBe(Number((body.variableFee + body.fixedFee).toFixed(2)));
    expect(body.calculation).toContain('=');
  });

  test('5. Gera a fatura do periodo com valores corretos (job manual)', async ({ request }) => {
    const res = await request.post(`/api/v1/merchants/${merchantId}/invoices`, {
      headers: { 'X-Api-Key': apiKey },
      data: { period: currentMonth },
    });
    expect(res.status()).toBe(201);

    const body = await res.json();
    expect(body.transactionCount).toBeGreaterThanOrEqual(1);
    expect(Number(body.transactionFeesTotal)).toBe(Number((body.transactionCount * 0.5).toFixed(2)));
    expect(Number(body.monthlyFee)).toBe(69.95);
    expect(Number(body.totalAmount)).toBe(
      Number((Number(body.transactionFeesTotal) + Number(body.monthlyFee)).toFixed(2)),
    );
    expect(body.status).toBe('PENDING');
    expect(body.billingPeriod).toBe(currentMonth);
  });

  test('6. Seguranca (Spec 18) — autorizacao por recurso, cadastro invalido e webhook idempotente', async ({ request }) => {
    // 6.1 Cadastro com e-mail invalido -> 400 (validacao de entrada)
    const invalid = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Invalida', email: 'email-invalido', password },
    });
    expect(invalid.status()).toBe(400);

    // 6.2 Segundo lojista (B) com e-mail valido -> 201 + chave propria
    const resB = await request.post('/api/v1/merchants', {
      data: { name: 'Loja B', email: 'loja.b@example.com', password },
    });
    expect(resB.status()).toBe(201);
    const merchantB = await resB.json();
    const apiKeyB = merchantB.apiKey;

    // 6.3 IDOR leitura: B tenta ler os dados do lojista A -> 403
    const readOther = await request.get(`/api/v1/merchants/${merchantId}`, {
      headers: { 'X-Api-Key': apiKeyB },
    });
    expect(readOther.status()).toBe(403);

    // 6.4 IDOR escrita: B tenta criar transacao no merchantId de A -> 403
    const writeOther = await request.post('/api/v1/transactions', {
      headers: { 'X-Api-Key': apiKeyB },
      data: { merchantId, amount: 999.99, pixKey: 'pix@loja.com', idempotencyKey: 'e2e-idor-write' },
    });
    expect(writeOther.status()).toBe(403);

    // 6.5 IDOR transacao: B tenta ler a transacao de A -> 403
    const txOther = await request.get(`/api/v1/transactions/${transactionId}`, {
      headers: { 'X-Api-Key': apiKeyB },
    });
    expect(txOther.status()).toBe(403);

    // 6.6 Webhook idempotente: replay de PAID na transacao ja APPROVED -> 204 (nao 409)
    const replay = await request.post('/webhooks/pix', {
      data: { eventId: 'evt-e2e-replay', externalReference, status: 'PAID' },
    });
    expect(replay.status()).toBe(204);

    // 6.7 A transacao continua APPROVED apos o replay
    const tx = await (await request.get(`/api/v1/transactions/${transactionId}`, {
      headers: { 'X-Api-Key': apiKey },
    })).json();
    expect(tx.status).toBe('APPROVED');
  });

  test('7. Antifraude (Spec 19) — chargeback bloqueia o comprador, sem afetar outros', async ({ request }) => {
    // 7.1 O lojista/bot registra chargeback na transacao aprovada -> 201
    const cb = await request.post(`/api/v1/merchants/${merchantId}/chargebacks`, {
      headers: { 'X-Api-Key': apiKey },
      data: { transactionId, reason: 'Cliente reportou fraude ao banco' },
    });
    expect(cb.status()).toBe(201);
    const chargeback = await cb.json();
    expect(chargeback.transactionId).toBe(transactionId);

    // 7.2 Mesmo buyerId tenta comprar de novo -> 409 fraud_buyer_blocked
    const blocked = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: {
        merchantId,
        amount: 150.0,
        buyerId: 'buyer-e2e-001', // mesmo comprador que deu chargeback
        expirationSeconds: 3600,
        idempotencyKey: 'e2e-cob-fraud',
      },
    });
    expect(blocked.status()).toBe(409);
    const blockedBody = await blocked.json();
    expect(blockedBody.code).toBe('fraud_buyer_blocked');

    // 7.3 Duplicidade: registrar chargeback de novo na mesma transacao -> 409
    const dup = await request.post(`/api/v1/merchants/${merchantId}/chargebacks`, {
      headers: { 'X-Api-Key': apiKey },
      data: { transactionId, reason: 'duplicado' },
    });
    expect(dup.status()).toBe(409);

    // 7.4 Outro comprador (buyerId diferente, sem historico) compra normalmente -> 201
    const ok = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: {
        merchantId,
        amount: 99.9,
        buyerId: 'buyer-e2e-002',
        expirationSeconds: 3600,
        idempotencyKey: 'e2e-cob-ok',
      },
    });
    expect(ok.status()).toBe(201);
  });

  test('7b. Estorno consistente (Spec 29) — chargeback marca REFUNDED e webhook pós-chargeback não debita de novo', async ({ request }) => {
    // A transação do chargeback (7.1) DEVE estar REFUNDED — fonte única de débito.
    const tx = await (await request.get(`/api/v1/transactions/${transactionId}`, {
      headers: { 'X-Api-Key': apiKey },
    })).json();
    expect(tx.status).toBe('REFUNDED');

    // Saldo antes do replay do webhook REFUNDED (consultado direto no banco).
    const client = dbClient();
    await client.connect();
    const { rows: before } = await client.query(
      'SELECT wallet_balance FROM merchants WHERE id = $1', [merchantId]);
    const balanceBefore = Number(before[0].wallet_balance);

    // Webhook REFUNDED pós-chargeback → 204 no-op, sem segundo débito.
    const replayRefund = await request.post('/webhooks/pix', {
      data: { eventId: 'evt-e2e-refund-after-cb', externalReference, status: 'REFUNDED' },
    });
    expect(replayRefund.status()).toBe(204);

    const { rows: after } = await client.query(
      'SELECT wallet_balance FROM merchants WHERE id = $1', [merchantId]);
    await client.end();
    expect(Number(after[0].wallet_balance)).toBe(balanceBefore); // saldo estável

    // Chargeback duplicado (sequencial) → 409 chargeback_already_exists (Spec 29).
    const dup = await request.post(`/api/v1/merchants/${merchantId}/chargebacks`, {
      headers: { 'X-Api-Key': apiKey },
      data: { transactionId, reason: 'duplicado de novo' },
    });
    expect(dup.status()).toBe(409);
    expect((await dup.json()).code).toBe('chargeback_already_exists');
  });

  test('8. Portal do lojista (Spec 20/23) — login, /portal/me, cobrança avulsa e Gift PIX', async ({ request }) => {
    // 8.1 Login no portal: e-mail + senha escolhida no cadastro (Spec 39).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const loginBody = await login.json();
    expect(loginBody.merchantId).toBe(merchantId);
    expect(loginBody.token).toMatch(/^[a-z]{3}_[0-9a-f]{32}$/); // token opaco (apenas hash no banco)
    const portalHeaders = { Authorization: `Bearer ${loginBody.token}` };

    // 8.2 Perfil do lojista logado (sessão do portal, sem API key).
    const me = await request.get('/api/v1/portal/me', { headers: portalHeaders });
    expect(me.status()).toBe(200);
    const meBody = await me.json();
    expect(meBody.merchantId).toBe(merchantId);
    expect(Number.isFinite(Number(meBody.walletBalance))).toBe(true);

    // 8.3 Cobrança avulsa do portal -> 201 (mesmo contrato do /foxcash/pix/cob).
    const charge = await request.post('/api/v1/portal/charges', {
      headers: portalHeaders,
      data: { amount: 33.33, description: 'Cobranca portal', expirationSeconds: 3600, idempotencyKey: 'e2e-portal-1' },
    });
    expect(charge.status()).toBe(201);
    const chargeBody = await charge.json();
    expect(chargeBody.status).toBe('PENDING');
    expect(chargeBody.pixCopyPaste).toContain('000201');

    // 8.4 Catálogo de Gift PIX: defaults R$ 5/10/20/50 criados no cadastro.
    const presetsRes = await request.get('/api/v1/portal/charges/gift-presets', { headers: portalHeaders });
    expect(presetsRes.status()).toBe(200);
    const presets = await presetsRes.json();
    expect(presets).toHaveLength(4);
    const amounts = presets.map((p) => Number(p.amount)).sort((a, b) => a - b);
    expect(amounts).toEqual([5, 10, 20, 50]);
    const activePreset = presets.find((p) => p.active);
    expect(activePreset).toBeTruthy();

    // 8.5 Cobrança Gift por preset (um clique) -> 201.
    const gift = await request.post('/api/v1/portal/charges/gift', {
      headers: portalHeaders,
      data: { presetId: activePreset.id, expirationSeconds: 3600, idempotencyKey: 'e2e-gift-1' },
    });
    expect(gift.status()).toBe(201);

    // 8.6 Desativar preset -> 204; cobrança por preset inativo -> 409 gift_preset_inactive.
    const disable = await request.put(`/api/v1/portal/charges/gift-presets/${activePreset.id}`, {
      headers: portalHeaders,
      data: { active: false },
    });
    expect(disable.status()).toBe(204);
    const giftInactive = await request.post('/api/v1/portal/charges/gift', {
      headers: portalHeaders,
      data: { presetId: activePreset.id, expirationSeconds: 3600, idempotencyKey: 'e2e-gift-2' },
    });
    expect(giftInactive.status()).toBe(409);
    expect((await giftInactive.json()).code).toBe('gift_preset_inactive');

    // 8.7 Logout revoga a sessão -> o token deixa de autenticar (401).
    const logout = await request.post('/api/v1/merchant-auth/logout', { headers: portalHeaders });
    expect(logout.status()).toBe(204);
    const afterLogout = await request.get('/api/v1/portal/me', { headers: portalHeaders });
    expect(afterLogout.status()).toBe(401);
  });

  test('9. Credenciais API (Spec 24) — criar/listar/revogar chave e escopo por rota', async ({ request }) => {
    // 9.1 Login no portal (mesmo lojista do cenário 1).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const loginBody = await login.json();
    const portalHeaders = { Authorization: `Bearer ${loginBody.token}` };

    // 9.2 Listar chaves: a do cadastro aparece (escopo total) sem o segredo.
    const list = await request.get('/api/v1/portal/credentials', { headers: portalHeaders });
    expect(list.status()).toBe(200);
    const keys = await list.json();
    expect(keys.length).toBeGreaterThanOrEqual(1);
    const cadastroKey = keys.find((k) => k.label === 'Chave do cadastro');
    expect(cadastroKey).toBeTruthy();
    expect(cadastroKey.prefix).toMatch(/^mch_/);
    expect(JSON.stringify(cadastroKey)).not.toContain('secret');

    // 9.3 Criar chave com escopo restrito (transactions) -> 201 com secret UMA vez.
    const create = await request.post('/api/v1/portal/credentials', {
      headers: portalHeaders,
      data: { label: 'Bot de vendas', scopes: ['transactions'] },
    });
    expect(create.status()).toBe(201);
    const created = await create.json();
    expect(created.secret).toMatch(/^mch_[0-9a-f]{32}$/);
    const scopedApiKey = created.secret;

    // 9.4 Chave com escopo transactions acessa /transactions (200) mas não /foxcash/pix (403).
    const txOk = await request.get(`/api/v1/transactions/merchant/${merchantId}`, {
      headers: { 'X-Api-Key': scopedApiKey },
    });
    expect(txOk.status()).toBe(200);
    const chargeDenied = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': scopedApiKey },
      data: { merchantId, amount: 10.0, expirationSeconds: 3600, idempotencyKey: 'e2e-scope-charge' },
    });
    expect(chargeDenied.status()).toBe(403);
    expect((await chargeDenied.json()).code).toBe('forbidden');

    // 9.5 Escopo inválido -> 400 invalid_argument.
    const invalid = await request.post('/api/v1/portal/credentials', {
      headers: portalHeaders,
      data: { label: 'Invalida', scopes: ['hacker'] },
    });
    expect(invalid.status()).toBe(400);

    // 9.6 Revogar -> 204; chave revogada não autentica mais (401).
    const revoke = await request.post(`/api/v1/portal/credentials/${created.id}/revoke`, {
      headers: portalHeaders,
    });
    expect(revoke.status()).toBe(204);
    const afterRevoke = await request.get(`/api/v1/transactions/merchant/${merchantId}`, {
      headers: { 'X-Api-Key': scopedApiKey },
    });
    expect(afterRevoke.status()).toBe(401);

    // 9.7 Limite de 5 chaves ativas -> 409 too_many_keys (a do cadastro + 1 nova).
    for (let i = 0; i < 5; i += 1) {
      const r = await request.post('/api/v1/portal/credentials', {
        headers: portalHeaders,
        data: { label: `Chave ${i}`, scopes: ['charges'] },
      });
      if (r.status() === 409) {
        expect((await r.json()).code).toBe('too_many_keys');
        break;
      }
    }

    // 9.8 Seguranca (fix 2026-08-04): a sessao do PORTAL nao acessa a API REST —
    // rotas de API exigem role de API (403, antes o Bearer do portal valia como
    // chave em /merchants/** e /foxcash/pix/** — broken access control corrigido).
    const portalOnApi = await request.get(`/api/v1/merchants/${merchantId}`, {
      headers: portalHeaders,
    });
    expect(portalOnApi.status()).toBe(403);
    const portalOnCharge = await request.post('/api/v1/foxcash/pix/cob', {
      headers: portalHeaders,
      data: { merchantId, amount: 10.0, expirationSeconds: 3600, idempotencyKey: 'e2e-portal-on-api' },
    });
    expect(portalOnCharge.status()).toBe(403);
    // ... mas o PORTAL continua sacando legitimamente (Spec 14/32: tela de saque).
    const portalOnWithdraw = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 1.0, destinationId: crypto.randomUUID() },
    });
    expect([400, 404, 409]).toContain(portalOnWithdraw.status()); // 403 NAO (autorizacao passa)

    // 9.9 Logout.
    const logout = await request.post('/api/v1/merchant-auth/logout', { headers: portalHeaders });
    expect(logout.status()).toBe(204);
  });

  test('10. Vendas (Spec 25) — listagem com filtros, métricas e detalhe com payload Pix', async ({ request }) => {
    // 10.1 Login no portal.
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const loginBody = await login.json();
    const portalHeaders = { Authorization: `Bearer ${loginBody.token}` };

    // 10.1b Cria e paga uma venda aprovada: a 150,00 do cenário 3 foi estornada
    // pelo chargeback do cenário 7 — Spec 29 marca a transação REFUNDED (não é
    // mais "concluída"; a venda estornada não conta como venda no portal).
    const sale = await request.post('/api/v1/foxcash/pix/cob', {
      headers: { 'X-Api-Key': apiKey },
      data: {
        merchantId,
        amount: 77.77,
        buyerId: 'buyer-e2e-010',
        expirationSeconds: 3600,
        idempotencyKey: 'e2e-cob-10',
      },
    });
    expect(sale.status()).toBe(201);
    const saleBody = await sale.json();
    const pay = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-e2e-10-pay',
        externalReference: saleBody.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 77.77,
      },
    });
    expect(pay.status()).toBe(204);

    // 10.2 Listagem paginada: itens com contrato completo; líquido = bruto − 0,50.
    const list = await request.get('/api/v1/portal/sales', { headers: portalHeaders });
    expect(list.status()).toBe(200);
    const listBody = await list.json();
    expect(Array.isArray(listBody.items)).toBe(true);
    expect(listBody.totalElements).toBeGreaterThanOrEqual(1);
    const approved = listBody.items.find((i) => i.status === 'APPROVED');
    expect(approved).toBeTruthy();
    expect(approved.method).toBe('PIX');
    expect(approved.transactionId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(Number(approved.netAmount)).toBe(Number((Number(approved.grossAmount) - 0.5).toFixed(2)));
    expect(approved.buyerIdHash).toMatch(/^[0-9a-f]{64}$/); // rótulo opaco (Spec 19)
    const portalCharge = listBody.items.find((i) => Number(i.grossAmount) === 33.33);
    expect(portalCharge).toBeTruthy();
    expect(portalCharge.buyerIdHash).toBeNull(); // sem buyerId do bot → "—"

    // 10.3 Filtro Concluídas (APPROVED) → apenas aprovadas.
    const approvedOnly = await request.get('/api/v1/portal/sales?status=APPROVED', { headers: portalHeaders });
    expect(approvedOnly.status()).toBe(200);
    const approvedItems = (await approvedOnly.json()).items;
    expect(approvedItems.length).toBe(1);
    expect(approvedItems.every((i) => i.status === 'APPROVED')).toBe(true);

    // 10.4 Filtro Pendentes (PENDING) → apenas pendentes.
    const pendingOnly = await request.get('/api/v1/portal/sales?status=PENDING', { headers: portalHeaders });
    expect(pendingOnly.status()).toBe(200);
    const pendingItems = (await pendingOnly.json()).items;
    expect(pendingItems.length).toBeGreaterThanOrEqual(1);
    expect(pendingItems.every((i) => i.status === 'PENDING')).toBe(true);

    // 10.5 Métricas de topo: volume 30d (soma APPROVED), concluídas, pendentes, conversão.
    const metrics = await request.get('/api/v1/portal/sales/metrics?days=30', { headers: portalHeaders });
    expect(metrics.status()).toBe(200);
    const metricsBody = await metrics.json();
    expect(Number(metricsBody.volume30d)).toBe(77.77); // única aprovada (a 150 foi estornada — Spec 29)
    expect(metricsBody.completedSales).toBe(1);
    expect(metricsBody.pendingSales).toBeGreaterThanOrEqual(1);
    expect(Number(metricsBody.conversionRate)).toBeGreaterThan(0);

    // 10.6 Detalhe de venda PENDING → payload Pix completo (QR Code + Copia e Cola).
    const detail = await request.get(`/api/v1/portal/sales/${pendingItems[0].transactionId}`, {
      headers: portalHeaders,
    });
    expect(detail.status()).toBe(200);
    const detailBody = await detail.json();
    expect(detailBody.status).toBe('PENDING');
    expect(detailBody.method).toBe('PIX');
    expect(detailBody.pixCopyPaste).toContain('000201');
    expect(detailBody.pixQrCode).toBeTruthy();
    expect(Number(detailBody.netAmount)).toBe(Number((Number(detailBody.grossAmount) - 0.5).toFixed(2)));

    // 10.7 Detalhe inexistente → 404 transaction_not_found.
    const missing = await request.get(
      `/api/v1/portal/sales/${crypto.randomUUID()}`,
      { headers: portalHeaders },
    );
    expect(missing.status()).toBe(404);
    expect((await missing.json()).code).toBe('transaction_not_found');

    // 10.8 Parâmetros inválidos → 400 invalid_argument (status e from).
    const badStatus = await request.get('/api/v1/portal/sales?status=HACK', { headers: portalHeaders });
    expect(badStatus.status()).toBe(400);
    expect((await badStatus.json()).code).toBe('invalid_argument');
    const badFrom = await request.get('/api/v1/portal/sales?from=abc', { headers: portalHeaders });
    expect(badFrom.status()).toBe(400);
    expect((await badFrom.json()).code).toBe('invalid_argument');

    // 10.9 Estorna a venda deste cenário (Spec 29) — o chargeback marca a
    // transação REFUNDED (fonte única de débito) e zera o saldo, preservando o
    // ponto de partida dos cenários 13/14 (saldo 0 — como no fluxo original).
    const cb10 = await request.post(`/api/v1/merchants/${merchantId}/chargebacks`, {
      headers: { 'X-Api-Key': apiKey },
      data: { transactionId: saleBody.transactionId, reason: 'Estorno pos-cenario 10' },
    });
    expect(cb10.status()).toBe(201);
    const meAfterCb = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(meAfterCb.walletBalance)).toBe(0.0);

    // 10.10 Logout.
    const logout = await request.post('/api/v1/merchant-auth/logout', { headers: portalHeaders });
    expect(logout.status()).toBe(204);
  });

  test('11. Ledger do wallet (Spec 27) — movimentações auditáveis no portal e proteção do master', async ({ request }) => {
    // 11.1 Login no portal.
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const loginBody = await login.json();
    const portalHeaders = { Authorization: `Bearer ${loginBody.token}` };

    // 11.2 Ledger do próprio lojista no portal: SALE_CREDIT (150.00 → saldo 150.00,
    // cenário 3) e CHARGEBACK_DEBIT (150.00 → saldo 0.00, cenário 7); o cenário
    // 10 adicionou SALE_CREDIT 77.77 (saldo 77.77) — a venda estornada (Spec 29)
    // e a venda aprovada registram UMA entry cada, com balance_after consistente.
    const ledger = await request.get('/api/v1/portal/wallet/ledger', { headers: portalHeaders });
    expect(ledger.status()).toBe(200);
    const ledgerBody = await ledger.json();
    expect(Array.isArray(ledgerBody.items)).toBe(true);
    expect(ledgerBody.totalElements).toBeGreaterThanOrEqual(3);

    // Venda aprovada do cenário 3 (150.00 → saldo 150.00).
    const saleCredit150 = ledgerBody.items.find(
      (e) => e.type === 'SALE_CREDIT' && Number(e.amount) === 150.0,
    );
    expect(saleCredit150).toBeTruthy();
    expect(Number(saleCredit150.balanceAfter)).toBe(150.0);
    expect(saleCredit150.referenceType).toBe('TRANSACTION');

    // Venda aprovada do cenário 10 (77.77 → saldo 77.77).
    const saleCredit7777 = ledgerBody.items.find(
      (e) => e.type === 'SALE_CREDIT' && Number(e.amount) === 77.77,
    );
    expect(saleCredit7777).toBeTruthy();
    expect(Number(saleCredit7777.balanceAfter)).toBe(77.77);

    const chargebackDebit150 = ledgerBody.items.find(
      (e) => e.type === 'CHARGEBACK_DEBIT' && Number(e.amount) === 150.0,
    );
    expect(chargebackDebit150).toBeTruthy();
    expect(Number(chargebackDebit150.balanceAfter)).toBe(0.0); // 150 - 150
    expect(chargebackDebit150.referenceType).toBe('CHARGEBACK');

    // O estorno do cenário 10 (77.77) também registrou CHARGEBACK_DEBIT (saldo 0).
    const chargebackDebit7777 = ledgerBody.items.find(
      (e) => e.type === 'CHARGEBACK_DEBIT' && Number(e.amount) === 77.77,
    );
    expect(chargebackDebit7777).toBeTruthy();
    expect(Number(chargebackDebit7777.balanceAfter)).toBe(0.0);

    // 11.3 Filtro por tipo e por período.
    const filtered = await request.get(
      '/api/v1/portal/wallet/ledger?type=SALE_CREDIT&from=2020-01-01T00:00:00Z',
      { headers: portalHeaders },
    );
    expect(filtered.status()).toBe(200);
    const filteredItems = (await filtered.json()).items;
    expect(filteredItems.length).toBe(2); // 150 (cenário 3) + 77.77 (cenário 10)
    expect(filteredItems[0].type).toBe('SALE_CREDIT');

    // 11.4 Tipo inválido → 400 invalid_argument.
    const badType = await request.get('/api/v1/portal/wallet/ledger?type=HACK', {
      headers: portalHeaders,
    });
    expect(badType.status()).toBe(400);
    expect((await badType.json()).code).toBe('invalid_argument');

    // 11.5 Sem token → 401; o ledger do master exige ROLE_MASTER (401 sem sessão).
    const noToken = await request.get('/api/v1/portal/wallet/ledger');
    expect(noToken.status()).toBe(401);
    const masterLedger = await request.get(`/api/v1/master/merchants/${merchantId}/ledger`);
    expect(masterLedger.status()).toBe(401);

    // 11.6 Logout.
    const logout = await request.post('/api/v1/merchant-auth/logout', { headers: portalHeaders });
    expect(logout.status()).toBe(204);
  });

  test('12. Destinos de saque (Spec 31) — privados, mascarados e criptografados', async ({ request }) => {
    // 12.1 Login no portal.
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const loginBody = await login.json();
    const portalHeaders = { Authorization: `Bearer ${loginBody.token}` };

    // 12.2 Criar destinos válidos (CPF + banco) → 201 com resposta mascarada.
    const cpf = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_CPF', value: '52998224725', label: 'Conta pessoal' },
    });
    expect(cpf.status()).toBe(201);
    const cpfBody = await cpf.json();
    expect(cpfBody.type).toBe('PIX_CPF');
    expect(cpfBody.status).toBe('ACTIVE');
    expect(cpfBody.mask).toBe('529.***.***-25');
    expect(JSON.stringify(cpfBody)).not.toContain('52998224725'); // nunca em claro

    const bank = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'BANK_ACCOUNT', bankCode: '341', agency: '0001', account: '12345-6', label: 'Conta PJ' },
    });
    expect(bank.status()).toBe(201);
    const bankBody = await bank.json();
    expect(bankBody.mask).toContain('Banco 341');
    expect(bankBody.mask).toContain('conta ***-');

    // 12.3 Tipo/forma inválidos → 400 invalid_argument (CPF '123' e celular '123').
    const badCpf = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_CPF', value: '123' },
    });
    expect(badCpf.status()).toBe(400);
    expect((await badCpf.json()).code).toBe('invalid_argument');
    const badPhone = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_PHONE', value: '123' },
    });
    expect(badPhone.status()).toBe(400);
    const badBank = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'BANK_ACCOUNT', bankCode: '341', agency: '0001' }, // sem conta
    });
    expect(badBank.status()).toBe(400);

    // 12.4 Listagem: apenas os do lojista, sempre mascarados.
    const list = await request.get('/api/v1/portal/destinations', { headers: portalHeaders });
    expect(list.status()).toBe(200);
    const destinations = await list.json();
    expect(destinations).toHaveLength(2);
    expect(destinations.every((d) => d.mask)).toBe(true);
    expect(JSON.stringify(destinations)).not.toContain('52998224725');

    // 12.5 Banco criptografado em repouso (prefixo v1: — nunca texto claro).
    const client = dbClient();
    await client.connect();
    const { rows } = await client.query(
      'SELECT value, bank_code, account FROM merchant_withdrawal_destinations WHERE merchant_id = $1',
      [merchantId],
    );
    await client.end();
    expect(rows).toHaveLength(2);
    expect(rows[0].value).toMatch(/^v1:/);
    expect(rows[1].account).toMatch(/^v1:/);
    expect(rows[0].value).not.toBe('52998224725');

    // 12.6 Desativar/reativar → 204 e status refletido na listagem.
    const deactivate = await request.post(`/api/v1/portal/destinations/${cpfBody.id}/deactivate`, {
      headers: portalHeaders,
    });
    expect(deactivate.status()).toBe(204);
    const afterDeactivate = await (await request.get('/api/v1/portal/destinations', { headers: portalHeaders })).json();
    expect(afterDeactivate.find((d) => d.id === cpfBody.id).status).toBe('INACTIVE');
    const reactivate = await request.post(`/api/v1/portal/destinations/${cpfBody.id}/activate`, {
      headers: portalHeaders,
    });
    expect(reactivate.status()).toBe(204);

    // 12.7 Atualizar rótulo → 200.
    const patch = await request.patch(`/api/v1/portal/destinations/${cpfBody.id}`, {
      headers: portalHeaders,
      data: { label: 'Conta principal' },
    });
    expect(patch.status()).toBe(200);
    expect((await patch.json()).label).toBe('Conta principal');

    // 12.8 Privacidade cross-loja: lojista B não acessa destino do A (404, sem vazar existência).
    const resB = await request.post('/api/v1/merchants', {
      data: { name: 'Loja B2', email: 'loja.b2@example.com', password },
    });
    expect(resB.status()).toBe(201);
    const merchantB = await resB.json();
    const loginB = await request.post('/api/v1/merchant-auth/login', {
      data: { email: merchantB.email, password },
    });
    const portalB = { Authorization: `Bearer ${(await loginB.json()).token}` };
    const cross = await request.patch(`/api/v1/portal/destinations/${cpfBody.id}`, {
      headers: portalB,
      data: { label: 'hack' },
    });
    expect(cross.status()).toBe(404);
    expect((await cross.json()).code).toBe('destination_not_found');

    // 12.9 Limite de ativos (5) → 409 too_many_destinations.
    await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_CNPJ', value: '11222333000181' },
    });
    await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_PHONE', value: '+5511987654321' },
    });
    await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_EVP', value: 'e6a2a9f0-8f00-4a9a-9b9c-1d2e3f4a5b6c' },
    });
    const limit = await request.post('/api/v1/portal/destinations', {
      headers: portalHeaders,
      data: { type: 'PIX_CPF', value: '52998224725' },
    });
    expect(limit.status()).toBe(409);
    expect((await limit.json()).code).toBe('too_many_destinations');

    // 12.10 Auth: sem token → 401; com API key (não portal) → 403.
    const noToken = await request.get('/api/v1/portal/destinations');
    expect(noToken.status()).toBe(401);
    const withApiKey = await request.get('/api/v1/portal/destinations', {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(withApiKey.status()).toBe(403);

    // 12.11 Logout.
    const logout = await request.post('/api/v1/merchant-auth/logout', { headers: portalHeaders });
    expect(logout.status()).toBe(204);
  });

  test('13. Saque com destino (Spec 32) — destino obrigatório, bloqueio lacrado e saque administrativo do master', async ({ request }) => {
    // 13.1 Login no portal (lojista A — saldo 0 após o chargeback do cenário 7/11).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const loginBody = await login.json();
    const portalHeaders = { Authorization: `Bearer ${loginBody.token}` };

    // 13.2 Credita saldo: cobrança 200 no portal + webhook PAID → saldo 200.
    const charge = await request.post('/api/v1/portal/charges', {
      headers: portalHeaders,
      data: { amount: 200.0, description: 'Saldo para saque', expirationSeconds: 3600, idempotencyKey: 'e2e-portal-saldo' },
    });
    expect(charge.status()).toBe(201);
    const chargeBody = await charge.json();
    const webhook = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-e2e-13-credit',
        externalReference: chargeBody.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 200.0,
      },
    });
    expect(webhook.status()).toBe(204);
    const me = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me.walletBalance)).toBe(200.0);

    // 13.3 Saque SEM destinationId → 400 validation_error.
    const noDest = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 10.0 },
    });
    expect(noDest.status()).toBe(400);
    expect((await noDest.json()).code).toBe('validation_error');

    // 13.4 Destino de OUTRO lojista → 404 destination_not_found (não revela existência).
    const resB = await request.post('/api/v1/merchants', {
      data: { name: 'Loja B3', email: 'loja.b3@example.com', password },
    });
    expect(resB.status()).toBe(201);
    const merchantB = await resB.json();
    const loginB = await request.post('/api/v1/merchant-auth/login', {
      data: { email: merchantB.email, password },
    });
    expect(loginB.status()).toBe(200);
    const portalB = { Authorization: `Bearer ${(await loginB.json()).token}` };
    const destB = await request.post('/api/v1/portal/destinations', {
      headers: portalB,
      data: { type: 'PIX_CPF', value: '39053344705', label: 'Conta B3' },
    });
    expect(destB.status()).toBe(201);
    const destBId = (await destB.json()).id;
    const cross = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 10.0, destinationId: destBId },
    });
    expect(cross.status()).toBe(404);
    expect((await cross.json()).code).toBe('destination_not_found');

    // 13.5 Destino inexistente → 404.
    const missing = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 10.0, destinationId: crypto.randomUUID() },
    });
    expect(missing.status()).toBe(404);
    expect((await missing.json()).code).toBe('destination_not_found');

    // 13.6 Destino inativo → 409 destination_inactive (desativa o CPF do lojista A).
    const list = await (await request.get('/api/v1/portal/destinations', { headers: portalHeaders })).json();
    const cpfDest = list.find((d) => d.type === 'PIX_CPF' && d.status === 'ACTIVE');
    expect(cpfDest).toBeTruthy();
    const bankDest = list.find((d) => d.type === 'BANK_ACCOUNT');
    expect(bankDest).toBeTruthy();
    await request.post(`/api/v1/portal/destinations/${cpfDest.id}/deactivate`, { headers: portalHeaders });
    const inactive = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 10.0, destinationId: cpfDest.id },
    });
    expect(inactive.status()).toBe(409);
    expect((await inactive.json()).code).toBe('destination_inactive');
    await request.post(`/api/v1/portal/destinations/${cpfDest.id}/activate`, { headers: portalHeaders });

    // 13.7 Saque válido com destino (banco) → 201 PROCESSING (Spec 36: nasce
    // PROCESSING; o PSP processa), type MERCHANT, destino mascarado, saldo 150.
    const ok = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 50.0, destinationId: bankDest.id },
    });
    expect(ok.status()).toBe(201);
    const okBody = await ok.json();
    expect(okBody.status).toBe('PROCESSING');
    expect(okBody.externalReference).toMatch(/^PO[0-9A-F]{32}$/); // referencia do PSP mock
    expect(Number(okBody.amount)).toBe(50.0);
    expect(okBody.type).toBe('MERCHANT');
    expect(Number(okBody.walletBalance)).toBe(147.50); // 200 − 50 − taxa 2,50 (Spec 88)
    expect(okBody.destination).toContain('Banco 341');
    expect(okBody.destination).toContain('conta ***-');
    expect(JSON.stringify(okBody)).not.toContain('12345-6'); // nunca em claro

    // 13.7b Spec 36: webhook de saida PAID confirma COMPLETED (idempotente).
    const payout = await request.post('/webhooks/payouts', {
      data: { externalReference: okBody.externalReference, status: 'PAID' },
    });
    expect(payout.status()).toBe(204);
    const payoutReplay = await request.post('/webhooks/payouts', {
      data: { externalReference: okBody.externalReference, status: 'PAID' },
    });
    expect(payoutReplay.status()).toBe(204); // re-entrega -> no-op

    // 13.8 Saque acima do saldo → 409 insufficient_balance.
    const over = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 500.0, destinationId: bankDest.id },
    });
    expect(over.status()).toBe(409);
    expect((await over.json()).code).toBe('insufficient_balance');

    // 13.9 Master autentica (seed direto no banco — global-setup limpa masters) e bloqueia.
    const masterRawToken = 'mst_' + nodeCrypto.randomBytes(16).toString('hex');
    const masterId = crypto.randomUUID();
    const client = dbClient();
    await client.connect();
    await client.query(
      'INSERT INTO masters (id, username, password_hash, name, active, created_at) VALUES ($1,$2,$3,$4,true,$5)',
      [masterId, 'e2e-master', 'x'.repeat(60), 'Master E2E', new Date().toISOString()],
    );
    await client.query(
      // Spec 69: write-off/administrative sao acoes SENSIVEIS — seed de teste com
      // stepped_up_at = now (sessao elevada; janela 15 min), padrao D8.1.
      'INSERT INTO master_sessions (id, master_id, token_hash, created_at, expires_at, revoked_at, stepped_up_at) VALUES ($1,$2,$3,$4,$5,NULL,$6)',
      [
        crypto.randomUUID(),
        masterId,
        nodeCrypto.createHash('sha256').update(masterRawToken).digest('hex'),
        new Date().toISOString(),
        new Date(Date.now() + 3600_000).toISOString(),
        new Date().toISOString(),
      ],
    );
    await client.end();
    const masterHeaders = { Authorization: `Bearer ${masterRawToken}` };

    const block = await request.post(`/api/v1/master/merchants/${merchantId}/withdrawals/block`, {
      headers: masterHeaders,
    });
    expect(block.status()).toBe(204);

    // Bloqueio LACRADO: o vendedor recebe 409 withdrawals_blocked (nenhuma rota dele contorna).
    const blocked = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 10.0, destinationId: bankDest.id },
    });
    expect(blocked.status()).toBe(409);
    expect((await blocked.json()).code).toBe('withdrawals_blocked');

    // 13.10 Saque administrativo do master com bloqueio ativo → 201 (saldo 150 - 30 = 120).
    const admin = await request.post(`/api/v1/master/merchants/${merchantId}/withdrawals/administrative`, {
      headers: masterHeaders,
      data: { amount: 30.0, destinationKey: '52998224725', reason: 'Recolhimento de taxa' },
    });
    expect(admin.status()).toBe(201);
    const adminBody = await admin.json();
    expect(adminBody.type).toBe('ADMINISTRATIVE');
    expect(Number(adminBody.walletBalance)).toBe(117.50); // admin sem taxa: 147,50 − 30
    expect(adminBody.destination).toBe('***'); // chave do master nunca exposta em claro
    expect(JSON.stringify(adminBody)).not.toContain('52998224725');

    // 13.11 Saque administrativo sem reason / sem destinationKey → 400.
    const noReason = await request.post(`/api/v1/master/merchants/${merchantId}/withdrawals/administrative`, {
      headers: masterHeaders,
      data: { amount: 10.0, destinationKey: '52998224725' },
    });
    expect(noReason.status()).toBe(400);
    const noKey = await request.post(`/api/v1/master/merchants/${merchantId}/withdrawals/administrative`, {
      headers: masterHeaders,
      data: { amount: 10.0, reason: 'Recolhimento' },
    });
    expect(noKey.status()).toBe(400);

    // 13.12 Saque administrativo acima do saldo → 409 insufficient_balance (nunca negativo).
    const adminOver = await request.post(`/api/v1/master/merchants/${merchantId}/withdrawals/administrative`, {
      headers: masterHeaders,
      data: { amount: 9999.0, destinationKey: '52998224725', reason: 'Recolhimento' },
    });
    expect(adminOver.status()).toBe(409);
    expect((await adminOver.json()).code).toBe('insufficient_balance');
    const meAfter = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(meAfter.walletBalance)).toBe(117.50); // inalterado após o 409

    // 13.13 O master vê o recolhimento com o tipo na listagem de saques.
    const masterList = await request.get(`/api/v1/master/withdrawals?merchantId=${merchantId}`, {
      headers: masterHeaders,
    });
    expect(masterList.status()).toBe(200);
    const masterItems = (await masterList.json()).items;
    const adminItem = masterItems.find((w) => w.type === 'ADMINISTRATIVE');
    expect(adminItem).toBeTruthy();
    expect(Number(adminItem.amount)).toBe(30.0);
    // Spec 36: o saque do vendedor (13.7) foi COMPLETADO via webhook de saida;
    // o administrativo do master segue PROCESSING (aguardando o PSP).
    const merchantItem = masterItems.find((w) => w.type === 'MERCHANT');
    expect(merchantItem).toBeTruthy();
    expect(merchantItem.status).toBe('COMPLETED');
    expect(adminItem.status).toBe('PROCESSING');

    // 13.14 Logout do portal.
    const logout = await request.post('/api/v1/merchant-auth/logout', { headers: portalHeaders });
    expect(logout.status()).toBe(204);
  });

  test('14. Faturamento automatico (Spec 33) — cobranca total/parcial, divida formal e bloqueio por inadimplencia', async ({ request }) => {
    // 14.1 Login no portal (lojista A — saldo 120 do cenário 13) + master via SQL.
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    const masterRawToken = 'mst_' + nodeCrypto.randomBytes(16).toString('hex');
    const masterId = crypto.randomUUID();
    const client = dbClient();
    await client.connect();
    await client.query(
      'INSERT INTO masters (id, username, password_hash, name, active, created_at) VALUES ($1,$2,$3,$4,true,$5)',
      [masterId, 'e2e-master-14', 'x'.repeat(60), 'Master E2E', new Date().toISOString()],
    );
    await client.query(
      // Spec 69: invoice collect e acao SENSIVEL — seed de teste com
      // stepped_up_at = now (janela 15 min), padrao D8.1.
      'INSERT INTO master_sessions (id, master_id, token_hash, created_at, expires_at, revoked_at, stepped_up_at) VALUES ($1,$2,$3,$4,$5,NULL,$6)',
      [
        crypto.randomUUID(),
        masterId,
        nodeCrypto.createHash('sha256').update(masterRawToken).digest('hex'),
        new Date().toISOString(),
        new Date(Date.now() + 3600_000).toISOString(),
        new Date().toISOString(),
      ],
    );

    // 14.2 Fatura vencida (periodo 2026-06, due 05/07) pendente de pagamento.
    const invoiceA = crypto.randomUUID();
    await client.query(
      `INSERT INTO invoices (id, merchant_id, billing_period, transaction_count,
         transaction_fees_total, monthly_fee, total_amount, status, due_date, created_at, paid_amount)
       VALUES ($1,$2,'2026-06',2,1.00,69.95,70.95,'PENDING','2026-07-05',$3,0.00)`,
      [invoiceA, merchantId, new Date().toISOString()],
    );
    await client.end();
    const masterHeaders = { Authorization: `Bearer ${masterRawToken}` };

    // 14.2 Libera o bloqueio master de saques deixado pelo cenário 13 — o teste
    // 14 foca no bloqueio por INADIMPLENCIA (outstanding_debt), nao no master.
    const release = await request.post(`/api/v1/master/merchants/${merchantId}/withdrawals/release`, {
      headers: masterHeaders,
    });
    expect(release.status()).toBe(204);

    // 14.3 Fatura vencida (periodo 2026-06, due 05/07) pendente de pagamento.
    const collectA = await request.post(
      `/api/v1/master/merchants/${merchantId}/invoices/${invoiceA}/collect`,
      { headers: masterHeaders },
    );
    expect(collectA.status()).toBe(200);
    const collectABody = await collectA.json();
    expect(Number(collectABody.debit)).toBeCloseTo(70.95, 2);
    expect(Number(collectABody.remaining)).toBeCloseTo(0.0, 2);
    const meA = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(meA.walletBalance)).toBeCloseTo(117.50 - 70.95, 2);

    // 14.4 Idempotencia: re-executar collect na fatura quitada -> no-op (debit 0).
    const collectA2 = await request.post(
      `/api/v1/master/merchants/${merchantId}/invoices/${invoiceA}/collect`,
      { headers: masterHeaders },
    );
    expect(collectA2.status()).toBe(200);
    const collectA2Body = await collectA2.json();
    expect(Number(collectA2Body.debit)).toBeCloseTo(0.0, 2);
    expect(Number(collectA2Body.remaining)).toBeCloseTo(0.0, 2);

    // 14.5 Fatura vencida maior que o saldo -> cobranca PARCIAL (49.05), OVERDUE + receivable OPEN.
    const invoiceB = crypto.randomUUID();
    const client2 = dbClient();
    await client2.connect();
    await client2.query(
      `INSERT INTO invoices (id, merchant_id, billing_period, transaction_count,
         transaction_fees_total, monthly_fee, total_amount, status, due_date, created_at, paid_amount)
       VALUES ($1,$2,'2026-05',0,0.00,100.00,100.00,'PENDING','2026-06-05',$3,0.00)`,
      [invoiceB, merchantId, new Date().toISOString()],
    );
    await client2.end();

    const collectB = await request.post(
      `/api/v1/master/merchants/${merchantId}/invoices/${invoiceB}/collect`,
      { headers: masterHeaders },
    );
    expect(collectB.status()).toBe(200);
    const collectBBody = await collectB.json();
    expect(Number(collectBBody.debit)).toBeCloseTo(117.50 - 70.95, 2); // 46.55 (todo o saldo)
    expect(Number(collectBBody.remaining)).toBeCloseTo(100 - (117.50 - 70.95), 2); // 53.45

    // 14.6 Receivable INVOICE OPEN com o saldo devedor registrado.
    const recList = await request.get(`/api/v1/master/merchants/${merchantId}/receivables`, {
      headers: masterHeaders,
    });
    expect(recList.status()).toBe(200);
    const openRec = (await recList.json()).items.find(
      (r) => r.origin === 'INVOICE' && r.status === 'OPEN',
    );
    expect(openRec).toBeTruthy();
    expect(Number(openRec.amount)).toBeCloseTo(53.45, 2);

    // 14.7 Saque do vendedor bloqueado por inadimplencia -> 409 outstanding_debt.
    const dests = await (await request.get('/api/v1/portal/destinations', { headers: portalHeaders })).json();
    const bankDest = dests.find((d) => d.type === 'BANK_ACCOUNT');
    expect(bankDest).toBeTruthy();
    const blocked = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 10.0, destinationId: bankDest.id },
    });
    expect(blocked.status()).toBe(409);
    expect((await blocked.json()).code).toBe('outstanding_debt');

    // 14.8 Master recolhe mesmo com divida (saque administrativo): credita 100, recolhe 20.
    const charge = await request.post('/api/v1/portal/charges', {
      headers: portalHeaders,
      data: { amount: 100.0, description: 'Saldo para recolhimento', expirationSeconds: 3600, idempotencyKey: 'e2e-portal-recolhe' },
    });
    expect(charge.status()).toBe(201);
    const chargeBody = await charge.json();
    const webhook = await request.post('/webhooks/pix', {
      data: {
        eventId: 'evt-e2e-14-credit',
        externalReference: chargeBody.externalReference,
        status: 'PAID',
        settledAt: new Date().toISOString(),
        amount: 100.0,
      },
    });
    expect(webhook.status()).toBe(204);
    const admin = await request.post(`/api/v1/master/merchants/${merchantId}/withdrawals/administrative`, {
      headers: masterHeaders,
      data: { amount: 20.0, destinationKey: '52998224725', reason: 'Recolhimento com divida' },
    });
    expect(admin.status()).toBe(201);
    expect(Number((await admin.json()).walletBalance)).toBeCloseTo(80.0, 2);

    // 14.9 Re-cobranca da OVERDUE quando o saldo volta -> debita 50.95, fatura PAID, receivable quitado.
    const collectB2 = await request.post(
      `/api/v1/master/merchants/${merchantId}/invoices/${invoiceB}/collect`,
      { headers: masterHeaders },
    );
    expect(collectB2.status()).toBe(200);
    const collectB2Body = await collectB2.json();
    expect(Number(collectB2Body.debit)).toBeCloseTo(53.45, 2);
    expect(Number(collectB2Body.remaining)).toBeCloseTo(0.0, 2);
    const recAfter = await request.get(`/api/v1/master/merchants/${merchantId}/receivables`, {
      headers: masterHeaders,
    });
    const paidRec = (await recAfter.json()).items.find(
      (r) => r.origin === 'INVOICE' && r.status === 'PAID',
    );
    expect(paidRec).toBeTruthy();
    // O receivable capturou o saldo devedor de 50.95 e foi quitado com a re-cobranca.
    expect(Number(paidRec.amount)).toBeCloseTo(53.45, 2);

    // 14.10 Saque liberado apos quitar a divida -> 201 (saldo 80 - 50.95 = 29.05; saca 20).
    const ok = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 20.0, destinationId: bankDest.id },
    });
    expect(ok.status()).toBe(201);
    expect(Number((await ok.json()).walletBalance)).toBeCloseTo(80 - 53.45 - 20 - 2.50, 2); // 4.05 (taxa de saque Spec 88)

    // 14.11 Ledger (Spec 27): cobranca da fatura registrou INVOICE_FEE_DEBIT no wallet.
    const ledger = await request.get('/api/v1/portal/wallet/ledger?type=INVOICE_FEE_DEBIT', {
      headers: portalHeaders,
    });
    expect(ledger.status()).toBe(200);
    const feeEntries = (await ledger.json()).items;
    expect(feeEntries.length).toBeGreaterThanOrEqual(1);
    expect(feeEntries.every((e) => e.type === 'INVOICE_FEE_DEBIT')).toBe(true);

    // 14.12 GET /api/v1/master/receivables (global): paginado com filtros,
    // 400 invalid_argument em inválido e 401 sem auth (criterio 6).
    const globalOpen = await request.get('/api/v1/master/receivables?origin=INVOICE&status=PAID', {
      headers: masterHeaders,
    });
    expect(globalOpen.status()).toBe(200);
    const globalItems = (await globalOpen.json()).items;
    expect(globalItems.some((r) => r.origin === 'INVOICE' && r.status === 'PAID')).toBe(true);

    const badFilter = await request.get('/api/v1/master/receivables?status=INVALIDO', {
      headers: masterHeaders,
    });
    expect(badFilter.status()).toBe(400);
    expect((await badFilter.json()).code).toBe('invalid_argument');

    const noAuth = await request.get('/api/v1/master/receivables');
    expect(noAuth.status()).toBe(401);
  });

  test('15. Webhook de saida (Spec 35) — entrega assinada SALE_PAID e retry com backoff', async ({ request }) => {
    // O dispatcher de notificacoes roda a cada 30s: o cenario usa polling paciente.
    test.setTimeout(240_000);

    // 15.1 Login no portal (mesmo lojista da jornada).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 15.2 Receptor local: captura os POSTs assinados do FoxCash e pode devolver
    // 500 sob demanda (para exercitar o retry do dispatcher).
    const deliveries = [];
    let failNext = false;
    const receiver = http.createServer((req, res) => {
      let body = '';
      req.on('data', (chunk) => (body += chunk));
      req.on('end', () => {
        deliveries.push({
          method: req.method,
          url: req.url,
          headers: req.headers,
          body,
          responseStatus: failNext ? 500 : 200,
        });
        res.statusCode = failNext ? 500 : 200;
        res.end();
      });
    });
    await new Promise((resolve) => receiver.listen(0, '127.0.0.1', resolve));
    const webhookUrl = `http://127.0.0.1:${receiver.address().port}/hook`;

    const outboxRow = async (eventId) => {
      const client = dbClient();
      await client.connect();
      const { rows } = await client.query(
        'SELECT status, attempts, last_error, next_attempt_at FROM notification_outbox WHERE id = $1',
        [eventId],
      );
      await client.end();
      return rows[0];
    };

    try {
      // 15.3 Drena eventos antigos do outbox: o receptor so recebe os eventos
      // deste cenario (cenarios anteriores podem ter deixado PENDING no ar).
      const drain = dbClient();
      await drain.connect();
      await drain.query("UPDATE notification_outbox SET status = 'SENT' WHERE status = 'PENDING'");
      await drain.end();

      // 15.4 CRUD: cria webhook ativo — secret devolvido UMA unica vez no 201;
      // a listagem nunca expoe o secret (apenas hasSecret).
      const created = await request.post('/api/v1/portal/webhooks', {
        headers: portalHeaders,
        data: { url: webhookUrl, events: ['SALE_PAID'] },
      });
      expect(created.status()).toBe(201);
      const webhook = await created.json();
      expect(webhook.secret).toMatch(/^[0-9a-f]{64}$/);
      expect(webhook.active).toBe(true);
      expect(webhook.events).toEqual(['SALE_PAID']);

      const list = await request.get('/api/v1/portal/webhooks', { headers: portalHeaders });
      expect(list.status()).toBe(200);
      const listed = (await list.json()).find((w) => w.id === webhook.id);
      expect(listed).toBeTruthy();
      expect(listed.hasSecret).toBe(true);
      expect(listed.secret).toBeUndefined(); // secret nunca em claro

      // 15.5 Evento invalido → 400 invalid_event.
      const invalid = await request.post('/api/v1/portal/webhooks', {
        headers: portalHeaders,
        data: { url: webhookUrl, events: ['EVENTO_INEXISTENTE'] },
      });
      expect(invalid.status()).toBe(400);
      expect((await invalid.json()).code).toBe('invalid_event');

      // 15.6 Teste de entrega: ping assinado para a URL (nao persiste nada).
      const ping = await request.post('/api/v1/portal/webhooks/test', {
        headers: portalHeaders,
        data: { url: webhookUrl, secret: webhook.secret, eventType: 'SALE_PAID' },
      });
      expect(ping.status()).toBe(200);
      expect((await ping.json()).delivered).toBe(true);

      // 15.7 Pagamento real → outbox ganha SALE_PAID e o dispatcher (a cada 30s)
      // entrega um POST assinado no receptor.
      const charge = await request.post('/api/v1/foxcash/pix/cob', {
        headers: { 'X-Api-Key': apiKey },
        data: { merchantId, amount: 50.0, buyerId: 'buyer-e2e-15', expirationSeconds: 3600, idempotencyKey: 'e2e-cob-webhook' },
      });
      expect(charge.status()).toBe(201);
      const paid = await request.post('/webhooks/pix', {
        data: {
          eventId: 'evt-e2e-15-sale',
          externalReference: (await charge.json()).externalReference,
          status: 'PAID',
          settledAt: new Date().toISOString(),
          amount: 50.0,
        },
      });
      expect(paid.status()).toBe(204);

      await expect.poll(
        () => deliveries.filter((d) => d.headers['x-foxcash-event'] === 'SALE_PAID').length,
        { timeout: 60_000, message: 'SALE_PAID nao foi entregue no receptor' },
      ).toBeGreaterThanOrEqual(1);

      const sale = deliveries.find((d) => d.headers['x-foxcash-event'] === 'SALE_PAID');
      expect(sale.method).toBe('POST');
      expect(sale.url).toBe('/hook');
      const envelope = JSON.parse(sale.body);
      expect(envelope.type).toBe('SALE_PAID');
      expect(envelope.merchantId).toBe(merchantId);
      expect(envelope.eventId).toBeTruthy();
      expect(envelope.data).toBeTruthy();
      // Assinatura HMAC-SHA256 do raw body reproduzivel com o segredo (criterio 2).
      expect(sale.headers['x-foxcash-signature'])
        .toBe(nodeCrypto.createHmac('sha256', webhook.secret).update(sale.body).digest('hex'));
      expect(sale.headers['x-foxcash-event-id']).toBeTruthy();
      const firstEventId = sale.headers['x-foxcash-event-id'];

      // 15.8 Outbox confirmou a entrega (SENT).
      await expect.poll(() => outboxRow(firstEventId).then((r) => r && r.status), {
        timeout: 60_000,
      }).toBe('SENT');

      // 15.9 Retry: receptor devolve 500 → dispatcher registra falha (attempts=1)
      // e agenda a proxima tentativa com backoff.
      failNext = true;
      const charge2 = await request.post('/api/v1/foxcash/pix/cob', {
        headers: { 'X-Api-Key': apiKey },
        data: { merchantId, amount: 30.0, buyerId: 'buyer-e2e-15-retry', expirationSeconds: 3600, idempotencyKey: 'e2e-cob-retry' },
      });
      expect(charge2.status()).toBe(201);
      const paid2 = await request.post('/webhooks/pix', {
        data: {
          eventId: 'evt-e2e-15-retry',
          externalReference: (await charge2.json()).externalReference,
          status: 'PAID',
          settledAt: new Date().toISOString(),
          amount: 30.0,
        },
      });
      expect(paid2.status()).toBe(204);

      const retryDelivered = () => deliveries.filter(
        (d) => d.headers['x-foxcash-event'] === 'SALE_PAID'
          && d.headers['x-foxcash-event-id'] !== firstEventId,
      );
      await expect.poll(() => retryDelivered().length, { timeout: 60_000 })
        .toBeGreaterThanOrEqual(1);
      const retryEventId = retryDelivered()[0].headers['x-foxcash-event-id'];
      expect(retryDelivered()[0].responseStatus).toBe(500);

      // Apos a falha: PENDING com attempts=1, last_error com 500 e proxima tentativa agendada.
      await expect.poll(
        () => outboxRow(retryEventId).then(
          (r) => r && r.status === 'PENDING' && r.attempts === 1 && r.next_attempt_at,
        ),
        { timeout: 60_000 },
      ).toBeTruthy();
      const afterFail = await outboxRow(retryEventId);
      expect(afterFail.last_error).toContain('500');

      // 15.10 Forca a proxima tentativa para o passado (o backoff real de ~60s
      // nao cabe no teste) e o receptor volta a responder 200 → re-entrega.
      const bump = dbClient();
      await bump.connect();
      await bump.query(
        "UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = $1",
        [retryEventId],
      );
      await bump.end();
      failNext = false;

      await expect.poll(() => outboxRow(retryEventId).then((r) => r && r.status), {
        timeout: 60_000,
      }).toBe('SENT');
      expect((await outboxRow(retryEventId)).attempts).toBe(1);

      // O receptor viu duas entregas do MESMO evento: a que falhou (500) e a
      // re-entrega (200) — ambas assinadas com o mesmo segredo.
      const sameEvent = deliveries.filter((d) => d.headers['x-foxcash-event-id'] === retryEventId);
      expect(sameEvent).toHaveLength(2);
      expect(sameEvent.some((d) => d.responseStatus === 500)).toBe(true);
      const retried = sameEvent.find((d) => d.responseStatus === 200);
      expect(retried).toBeTruthy();
      expect(retried.headers['x-foxcash-signature'])
        .toBe(nodeCrypto.createHmac('sha256', webhook.secret).update(retried.body).digest('hex'));
    } finally {
      receiver.closeAllConnections();
      await new Promise((resolve) => receiver.close(resolve));
    }
  });

  test('16. Saque via PSP (Spec 36) — PROCESSING → COMPLETED via webhook de saida', async ({ request }) => {
    // 16.1 Login no portal (mesmo lojista — saldo herdado dos cenarios anteriores).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 16.2 Saldo atual (referencia) + destino ativo.
    const me0 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    const balanceBefore = Number(me0.walletBalance);
    const destList = await (await request.get('/api/v1/portal/destinations', { headers: portalHeaders })).json();
    const dest = destList.find((d) => d.status === 'ACTIVE');
    expect(dest).toBeTruthy();

    // 16.3 Saque 30 → nasce PROCESSING com externalReference; debito imediato.
    const wd = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 30.0, destinationId: dest.id },
    });
    expect(wd.status()).toBe(201);
    const wdBody = await wd.json();
    expect(wdBody.status).toBe('PROCESSING');
    expect(wdBody.externalReference).toMatch(/^PO[0-9A-F]{32}$/);
    expect(Number(wdBody.walletBalance)).toBeCloseTo(balanceBefore - 30 - 2.50, 2); // taxa de saque Spec 88

    // 16.4 Webhook de saida PAID → COMPLETED (saldo inalterado, debito ja feito).
    const payout = await request.post('/webhooks/payouts', {
      data: { externalReference: wdBody.externalReference, status: 'PAID' },
    });
    expect(payout.status()).toBe(204);

    const list = await (await request.get(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const settled = list.find((w) => w.id === wdBody.id);
    expect(settled).toBeTruthy();
    expect(settled.status).toBe('COMPLETED');
    expect(settled.completedAt).toBeTruthy();

    const me1 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me1.walletBalance)).toBeCloseTo(balanceBefore - 30 - 2.50, 2);
  });

  test('17. Saque via PSP (Spec 36) — FAILED → estorno automatico (WITHDRAWAL_REVERSAL)', async ({ request }) => {
    // 17.1 Login no portal.
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    const me0 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    const balanceBefore = Number(me0.walletBalance);
    const destList = await (await request.get('/api/v1/portal/destinations', { headers: portalHeaders })).json();
    const dest = destList.find((d) => d.status === 'ACTIVE');
    expect(dest).toBeTruthy();

    // 17.2 Saque 20 → PROCESSING; debito imediato.
    const wd = await request.post(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
      data: { amount: 20.0, destinationId: dest.id },
    });
    expect(wd.status()).toBe(201);
    const wdBody = await wd.json();
    expect(wdBody.status).toBe('PROCESSING');
    expect(Number(wdBody.walletBalance)).toBeCloseTo(balanceBefore - 20 - 2.50, 2); // taxa de saque Spec 88

    // 17.3 Webhook de saida FAILED → estorno automatico do debito ao saldo.
    const failed = await request.post('/webhooks/payouts', {
      data: {
        externalReference: wdBody.externalReference,
        status: 'FAILED',
        failedReason: 'Chave Pix invalida no PSP',
      },
    });
    expect(failed.status()).toBe(204);

    const list = await (await request.get(`/api/v1/merchants/${merchantId}/withdrawals`, {
      headers: portalHeaders,
    })).json();
    const failedWd = list.find((w) => w.id === wdBody.id);
    expect(failedWd).toBeTruthy();
    expect(failedWd.status).toBe('FAILED');
    expect(failedWd.failedAt).toBeTruthy();
    expect(failedWd.failureReason).toBe('Chave Pix invalida no PSP');

    // Saldo devolvido (estorno) e ledger WITHDRAWAL_REVERSAL registrado.
    const me1 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me1.walletBalance)).toBeCloseTo(balanceBefore, 2);

    const ledger = await (await request.get('/api/v1/portal/wallet/ledger?type=WITHDRAWAL_REVERSAL', {
      headers: portalHeaders,
    })).json();
    const reversals = ledger.items.filter((e) => e.type === 'WITHDRAWAL_REVERSAL');
    expect(reversals.length).toBeGreaterThanOrEqual(1);

    // 17.4 Idempotencia: re-envio do FAILED → 204 no-op (sem duplo estorno).
    const replay = await request.post('/webhooks/payouts', {
      data: {
        externalReference: wdBody.externalReference,
        status: 'FAILED',
        failedReason: 'Chave Pix invalida no PSP',
      },
    });
    expect(replay.status()).toBe(204);
    const me2 = await (await request.get('/api/v1/portal/me', { headers: portalHeaders })).json();
    expect(Number(me2.walletBalance)).toBeCloseTo(balanceBefore, 2);
  });

  test('18. Auditoria do master (Spec 37) + write-off e receita (Spec 34) — trilha consultavel, motivo obrigatorio e CHARGEBACK_LOSS', async ({ request }) => {
    // 18.1 Master via SQL (mesmo padrao dos cenarios 13/14).
    const masterRawToken = 'mst_' + nodeCrypto.randomBytes(16).toString('hex');
    const masterId = crypto.randomUUID();
    const client = dbClient();
    await client.connect();
    await client.query(
      'INSERT INTO masters (id, username, password_hash, name, active, created_at) VALUES ($1,$2,$3,$4,true,$5)',
      [masterId, 'e2e-master-18', 'x'.repeat(60), 'Master E2E', new Date().toISOString()],
    );
    await client.query(
      // Spec 69: write-off e acao SENSIVEL — seed de teste com stepped_up_at = now
      // (janela 15 min), padrao D8.1.
      'INSERT INTO master_sessions (id, master_id, token_hash, created_at, expires_at, revoked_at, stepped_up_at) VALUES ($1,$2,$3,$4,$5,NULL,$6)',
      [
        crypto.randomUUID(),
        masterId,
        nodeCrypto.createHash('sha256').update(masterRawToken).digest('hex'),
        new Date().toISOString(),
        new Date(Date.now() + 3600_000).toISOString(),
        new Date().toISOString(),
      ],
    );

    // 18.2 Receivable CHARGEBACK OPEN (estorno nao coberto — Spec 33/29) para write-off.
    const receivableId = crypto.randomUUID();
    await client.query(
      `INSERT INTO receivables (id, merchant_id, origin, reference_id, amount, paid_amount,
         status, created_at, updated_at)
       VALUES ($1,$2,'CHARGEBACK',$3,80.00,0.00,'OPEN',$4,$4)`,
      [receivableId, merchantId, crypto.randomUUID(), new Date().toISOString()],
    );
    await client.end();
    const masterHeaders = { Authorization: `Bearer ${masterRawToken}` };

    // 18.3 Block gera trilha WITHDRAWALS_BLOCK com before/after (criterio 2).
    const block = await request.post(`/api/v1/master/merchants/${merchantId}/withdrawals/block`, {
      headers: masterHeaders,
    });
    expect(block.status()).toBe(204);

    const auditBlock = await request.get(`/api/v1/master/audit?merchantId=${merchantId}&action=WITHDRAWALS_BLOCK`, {
      headers: masterHeaders,
    });
    expect(auditBlock.status()).toBe(200);
    const blockEntries = (await auditBlock.json()).items;
    expect(blockEntries.length).toBeGreaterThanOrEqual(1);
    const blockEntry = blockEntries[0];
    expect(blockEntry.actorId).toBe(masterId);
    expect(blockEntry.action).toBe('WITHDRAWALS_BLOCK');
    expect(JSON.parse(blockEntry.before).withdrawalsBlocked).toBe(false);
    expect(JSON.parse(blockEntry.after).withdrawalsBlocked).toBe(true);
    expect(blockEntry.ip).toBeTruthy();

    // 18.4 Write-off sem reason → 400 reason_required (criterio 3).
    const noReason = await request.post(`/api/v1/master/receivables/${receivableId}/write-off`, {
      headers: masterHeaders,
      data: { reason: '   ' },
    });
    expect(noReason.status()).toBe(400);
    expect((await noReason.json()).code).toBe('reason_required');

    // 18.4b Baseline de losses ANTES do write-off — specs anteriores podem ter
    // registrado perdas globais (ex.: master-console-stepup 1 faz write-off de
    // 10.00), então 18.7 assere o DELTA, não um valor absoluto (flakiness
    // pré-existente documentada nas auditorias das Specs 71/73).
    const baselineSummary = await request.get('/api/v1/master/revenue/summary', { headers: masterHeaders });
    expect(baselineSummary.status()).toBe(200);
    const baselineLosses = Number((await baselineSummary.json()).losses);

    // 18.5 Write-off com reason → receivable PAID + CHARGEBACK_LOSS + trilha.
    const writeOff = await request.post(`/api/v1/master/receivables/${receivableId}/write-off`, {
      headers: masterHeaders,
      data: { reason: 'Divida incobravel' },
    });
    expect(writeOff.status()).toBe(200);
    expect(Number((await writeOff.json()).loss)).toBeCloseTo(80.0, 2);

    const auditWriteOff = await request.get(`/api/v1/master/audit?action=RECEIVABLE_WRITE_OFF`, {
      headers: masterHeaders,
    });
    expect(auditWriteOff.status()).toBe(200);
    const writeOffEntries = (await auditWriteOff.json()).items;
    expect(writeOffEntries.some((e) => e.merchantId === merchantId && e.reason === 'Divida incobravel')).toBe(true);

    // 18.6 Receivable baixado consultavel no GET /receivables (status PAID).
    const recs = await request.get(`/api/v1/master/merchants/${merchantId}/receivables`, {
      headers: masterHeaders,
    });
    const writtenOff = (await recs.json()).items.find((r) => r.id === receivableId);
    expect(writtenOff).toBeTruthy();
    expect(writtenOff.status).toBe('PAID');

    // 18.7 Receita (Spec 34): summary reflete fees das faturas pagas + recovery
    // do saque administrativo (cenario 14) + CHARGEBACK_LOSS do write-off.
    const summary = await request.get('/api/v1/master/revenue/summary', { headers: masterHeaders });
    expect(summary.status()).toBe(200);
    const summaryBody = await summary.json();
    // Fatura A (1 transacao + mensalidade 69.95) + fatura B (mensalidade 100.00).
    expect(Number(summaryBody.transactionFees)).toBeGreaterThanOrEqual(1.0);
    expect(Number(summaryBody.monthlyFees)).toBeGreaterThanOrEqual(169.95);
    expect(Number(summaryBody.losses)).toBeCloseTo(baselineLosses + 80.0, 2);
    // Spec 88: as taxas de saque da JORNADA (13.7/14.10/16.3/17.2 = 10,00 −
    // reversão da 17.3 = 2,50 → 7,50) aparecem no summary GLOBAL do master.
    // O summary é agregado de todos os lojistas e o baseline (18.4b) é lido
    // DEPOIS dos testes 13–17 (jornada serial) — o delta desta etapa é 0 e
    // specs anteriores (ex.: auto-withdraw, Spec 70) podem ter gerado
    // WITHDRAWAL_FEE. Asserção robusta: pelo menos 7,50 (a jornada contribuiu).
    expect(Number(summaryBody.withdrawalFees)).toBeGreaterThanOrEqual(7.5);
    expect(Number(summaryBody.net)).toBeCloseTo(
      Number(summaryBody.transactionFees) + Number(summaryBody.monthlyFees)
      + Number(summaryBody.recoveries) + Number(summaryBody.adminRecoveries)
      + Number(summaryBody.withdrawalFees)
      - (baselineLosses + 80.0), 2);

    // 18.8 Revenue ledger paginado + filtro por tipo (CHARGEBACK_LOSS presente).
    const ledger = await request.get('/api/v1/master/revenue/ledger?type=CHARGEBACK_LOSS', {
      headers: masterHeaders,
    });
    expect(ledger.status()).toBe(200);
    const lossEntries = (await ledger.json()).items;
    expect(lossEntries.length).toBeGreaterThanOrEqual(1);
    expect(lossEntries.every((e) => e.type === 'CHARGEBACK_LOSS')).toBe(true);

    // 18.9 Imutavel: nenhuma rota de mutacao existe (POST /audit → 405/403).
    const auditPost = await request.post('/api/v1/master/audit', {
      headers: masterHeaders,
      data: {},
    });
    expect([403, 405]).toContain(auditPost.status());

    // 18.10 401 sem auth e 403 com API key do lojista em /audit.
    const noAuth = await request.get('/api/v1/master/audit');
    expect(noAuth.status()).toBe(401);
    const merchantAuth = await request.get('/api/v1/master/audit', { headers: { 'X-Api-Key': apiKey } });
    expect(merchantAuth.status()).toBe(403);

    // 18.11 Reconciliação (Spec 30): run manual → resumo; issue simulada registrada.
    const reconRun = await request.post('/api/v1/master/reconciliation/run', { headers: masterHeaders });
    expect(reconRun.status()).toBe(200);
    const reconBody = await reconRun.json();
    expect(Number.isInteger(reconBody.checked)).toBe(true);
    expect(Number.isInteger(reconBody.ok)).toBe(true);
    expect(Number.isInteger(reconBody.issues)).toBe(true);
    expect(Number.isInteger(reconBody.orphanCharges)).toBe(true);
    expect(reconBody.checked).toBeGreaterThanOrEqual(1);

    const noAuthRun = await request.post('/api/v1/master/reconciliation/run');
    expect(noAuthRun.status()).toBe(401);

    // Simula divergência: UPDATE direto em wallet_balance SEM ledger (é o fim da
    // suite — nenhum teste posterior depende do saldo) → issue OPEN registrada.
    const client2 = dbClient();
    await client2.connect();
    await client2.query('UPDATE merchants SET wallet_balance = wallet_balance + 5.00 WHERE id = $1', [merchantId]);
    await client2.end();

    const reconRun2 = await request.post('/api/v1/master/reconciliation/run', { headers: masterHeaders });
    expect(reconRun2.status()).toBe(200);
    const reconBody2 = await reconRun2.json();
    expect(reconBody2.issues).toBeGreaterThanOrEqual(1);

    const issues = await request.get(
      `/api/v1/master/reconciliation/issues?merchantId=${merchantId}&status=OPEN`,
      { headers: masterHeaders },
    );
    expect(issues.status()).toBe(200);
    const issueItems = (await issues.json()).items;
    expect(issueItems.length).toBeGreaterThanOrEqual(1);
    const myIssue = issueItems.find((i) => i.merchantId === merchantId && i.status === 'OPEN');
    expect(myIssue).toBeTruthy();
    expect(Number(myIssue.actualBalance)).toBeGreaterThan(Number(myIssue.expectedBalance));

    // Re-execução não duplica: ainda 1 issue OPEN por lojista (idempotente).
    const rerun = await request.post('/api/v1/master/reconciliation/run', { headers: masterHeaders });
    expect(rerun.status()).toBe(200);
    const issuesAfterRerun = await request.get(
      `/api/v1/master/reconciliation/issues?merchantId=${merchantId}&status=OPEN`,
      { headers: masterHeaders },
    );
    const openAfterRerun = (await issuesAfterRerun.json()).items
      .filter((i) => i.merchantId === merchantId && i.status === 'OPEN');
    expect(openAfterRerun.length).toBeLessThanOrEqual(1);

    // Parâmetro inválido → 400 invalid_argument.
    const badStatus = await request.get('/api/v1/master/reconciliation/issues?status=hacker', {
      headers: masterHeaders,
    });
    expect(badStatus.status()).toBe(400);
    expect((await badStatus.json()).code).toBe('invalid_argument');
  });

  test('21. Recuperação de senha (Spec 40) — forgot/reset one-time, sessões revogadas e anti-enumeração', async ({ request }) => {
    // 21.1 Login com a senha atual (cenário 1) → sessão ativa que será revogada.
    const oldPassword = password;
    const newPassword = 'Nova-Senha-E2E-456';
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: oldPassword },
    });
    expect(login.status()).toBe(200);
    const oldSessionToken = (await login.json()).token;

    // 21.2 Forgot-password (dev: expose-reset-token=true) → 200 com resetToken rst_<32 hex>.
    const forgot = await request.post('/api/v1/merchant-auth/forgot-password', {
      data: { email },
    });
    expect(forgot.status()).toBe(200);
    const resetToken = (await forgot.json()).resetToken;
    expect(resetToken).toMatch(/^rst_[0-9a-f]{32}$/);

    // 21.3 No banco: apenas o hash SHA-256 do token (nunca o texto claro).
    const client = dbClient();
    await client.connect();
    const tokenRows = await client.query(
      'SELECT token_hash, used_at FROM password_reset_tokens WHERE merchant_id = $1',
      [merchantId],
    );
    expect(tokenRows.rows).toHaveLength(1);
    expect(tokenRows.rows[0].token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(tokenRows.rows[0].token_hash).not.toContain(resetToken); // não é o token em claro
    expect(tokenRows.rows[0].used_at).toBeNull();                   // ainda ativo
    const credBefore = await client.query(
      'SELECT password_hash FROM merchant_credentials WHERE merchant_id = $1',
      [merchantId],
    );
    const oldPasswordHash = credBefore.rows[0].password_hash;

    // 21.4 Reset com o token → 204.
    const reset = await request.post('/api/v1/merchant-auth/reset-password', {
      data: { token: resetToken, newPassword },
    });
    expect(reset.status()).toBe(204);

    // 21.5 Login com a senha antiga → 401; com a nova → 200.
    const oldLogin = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: oldPassword },
    });
    expect(oldLogin.status()).toBe(401);
    const newLogin = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: newPassword },
    });
    expect(newLogin.status()).toBe(200);

    // 21.6 Token reusado (single-use) → 400 invalid_token.
    const reuse = await request.post('/api/v1/merchant-auth/reset-password', {
      data: { token: resetToken, newPassword },
    });
    expect(reuse.status()).toBe(400);
    expect((await reuse.json()).code).toBe('invalid_token');

    // 21.7 Sessão antiga (antes do reset) revogada → 401.
    const oldSession = await request.get('/api/v1/portal/me', {
      headers: { Authorization: `Bearer ${oldSessionToken}` },
    });
    expect(oldSession.status()).toBe(401);

    // 21.8 Hash BCrypt da senha trocado no banco (e diferente do antigo).
    const credAfter = await client.query(
      'SELECT password_hash FROM merchant_credentials WHERE merchant_id = $1',
      [merchantId],
    );
    await client.end();
    expect(credAfter.rows[0].password_hash).toMatch(/^\$2[aby]\$\d{2}\$/); // BCrypt
    expect(credAfter.rows[0].password_hash).not.toBe(oldPasswordHash);
    expect(credAfter.rows[0].password_hash).not.toContain(newPassword);

    // 21.9 Anti-enumeração: e-mail inexistente → 204 (sem token, sem vazar existência).
    const unknown = await request.post('/api/v1/merchant-auth/forgot-password', {
      data: { email: 'nao.existe@example.com' },
    });
    expect(unknown.status()).toBe(204);

    // 21.10 Novo forgot invalida o token anterior (um ativo por lojista) e
    //       senha curta → 400 validation_error.
    const forgot2 = await request.post('/api/v1/merchant-auth/forgot-password', {
      data: { email },
    });
    expect(forgot2.status()).toBe(200);
    const short = await request.post('/api/v1/merchant-auth/reset-password', {
      data: { token: (await forgot2.json()).resetToken, newPassword: '123' },
    });
    expect(short.status()).toBe(400);
    expect((await short.json()).code).toBe('validation_error');
  });

  test('22. Gestão de perfis master + relatório de atividades (Spec 41) — admin-only, limite 4 e trilha unificada', async ({ request }) => {
    // 22.1 Master ADMIN via SQL (can_manage_masters=true) + sessão direta.
    const adminRawToken = 'mst_' + nodeCrypto.randomBytes(16).toString('hex');
    const adminMasterId = crypto.randomUUID();
    const client = dbClient();
    await client.connect();
    // Limpa os masters criados via SQL nos cenários anteriores (13/14/18) para
    // testar o limite de 4 do zero — dependências primeiro (FKs). Este é o fim
    // da suite: nenhum teste posterior depende deles.
    await client.query('DELETE FROM master_sessions');
    await client.query('DELETE FROM master_audit_log');
    // master_mfa_methods (V42 cascata p/ master_totp/recovery_codes) — o spec
    // master-console-stepup ativa MFA nos masters dele; sem limpar, o DELETE
    // de masters abaixo viola a FK master_mfa_methods_master_id_fkey
    // (flakiness cross-spec observada 2026-08-13 em suíte completa).
    await client.query('DELETE FROM master_mfa_methods');
    await client.query('DELETE FROM masters');
    await client.query(
      'INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at) VALUES ($1,$2,$3,$4,true,true,$5)',
      [adminMasterId, 'e2e-admin', 'x'.repeat(60), 'Admin E2E', new Date().toISOString()],
    );
    await client.query(
      // Spec 69: gestao de perfis e acao SENSIVEL — seed de teste com
      // stepped_up_at = now (janela 15 min), padrao D8.1.
      'INSERT INTO master_sessions (id, master_id, token_hash, created_at, expires_at, revoked_at, stepped_up_at) VALUES ($1,$2,$3,$4,$5,NULL,$6)',
      [
        crypto.randomUUID(),
        adminMasterId,
        nodeCrypto.createHash('sha256').update(adminRawToken).digest('hex'),
        new Date().toISOString(),
        new Date(Date.now() + 3600_000).toISOString(),
        new Date().toISOString(),
      ],
    );
    const adminHeaders = { Authorization: `Bearer ${adminRawToken}` };

    // 22.2 Admin cria perfil → 201 com temporaryPassword mst_<32 hex> (senha 1x;
    // SEC-25 2026-08-11 elevou de 8 para 16 bytes — 128 bits).
    const create1 = await request.post('/api/v1/master/profiles', {
      headers: adminHeaders,
      data: { username: 'operador1', name: 'Operador Um' },
    });
    expect(create1.status()).toBe(201);
    const profile1 = await create1.json();
    expect(profile1.temporaryPassword).toMatch(/^mst_[0-9a-f]{32}$/);
    expect(profile1.canManageMasters).toBe(false);

    // 22.3 Apenas o hash BCrypt no banco (nunca a senha em claro) + não-admin.
    const masterRows = await client.query(
      "SELECT password_hash, can_manage_masters FROM masters WHERE username = 'operador1'",
    );
    expect(masterRows.rows).toHaveLength(1);
    expect(masterRows.rows[0].can_manage_masters).toBe(false);
    expect(masterRows.rows[0].password_hash).not.toContain(profile1.temporaryPassword);

    // 22.4 Login com o novo perfil (senha gerada) → 200 (a senha funciona).
    const operadorLogin = await request.post('/api/v1/auth/master/login', {
      data: { username: 'operador1', password: profile1.temporaryPassword },
    });
    expect(operadorLogin.status()).toBe(200);
    const operadorHeaders = { Authorization: `Bearer ${(await operadorLogin.json()).token}` };

    // 22.5 Perfil não-admin NÃO acessa a gestão → 403 forbidden.
    const denied = await request.post('/api/v1/master/profiles', {
      headers: operadorHeaders,
      data: { username: 'hacker', name: 'Hacker' },
    });
    expect(denied.status()).toBe(403);

    // 22.6 Deactivate de si mesmo → 409 cannot_deactivate_self.
    const self = await request.post(`/api/v1/master/profiles/${adminMasterId}/deactivate`, {
      headers: adminHeaders,
    });
    expect(self.status()).toBe(409);
    expect((await self.json()).code).toBe('cannot_deactivate_self');

    // 22.7 Limite de 4 perfis: admin + operador1 + operador2 + operador3 = 4 → 5º 409.
    const create2 = await request.post('/api/v1/master/profiles', {
      headers: adminHeaders,
      data: { username: 'operador2', name: 'Operador Dois' },
    });
    expect(create2.status()).toBe(201);
    const create3 = await request.post('/api/v1/master/profiles', {
      headers: adminHeaders,
      data: { username: 'operador3', name: 'Operador Tres' },
    });
    expect(create3.status()).toBe(201);
    const create4 = await request.post('/api/v1/master/profiles', {
      headers: adminHeaders,
      data: { username: 'operador4', name: 'Operador Quatro' },
    });
    expect(create4.status()).toBe(409);
    expect((await create4.json()).code).toBe('too_many_masters');

    // 22.8 Listar perfis (nunca expõe password_hash).
    const list = await request.get('/api/v1/master/profiles', { headers: adminHeaders });
    expect(list.status()).toBe(200);
    const profiles = await list.json();
    expect(profiles.length).toBe(4);
    expect(profiles.every((p) => p.passwordHash === undefined && p.password_hash === undefined)).toBe(true);

    // 22.9 Relatório de atividades (admin): eventos reais registrados na suite.
    const all = await request.get('/api/v1/master/report/activity?size=100', { headers: adminHeaders });
    expect(all.status()).toBe(200);
    const allActions = new Set((await all.json()).items.map((i) => i.action));
    // Eventos gerados pelo PRÓPRIO cenário (auto-suficiente): criação de perfis
    // master + login do operador; e gera um MERCHANT_LOGIN real do lojista.
    expect(allActions.has('MASTER_CREATED')).toBe(true);
    expect(allActions.has('MASTER_LOGIN')).toBe(true);
    const merchantLogin = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: 'Nova-Senha-E2E-456' }, // senha redefinida no cenário 21
    });
    expect(merchantLogin.status()).toBe(200);

    // 22.10 Filtro por action + lojista combinados (MERCHANT_LOGIN do lojista).
    const filtered = await request.get(
      `/api/v1/master/report/activity?action=MERCHANT_LOGIN&merchantId=${merchantId}`,
      { headers: adminHeaders },
    );
    expect(filtered.status()).toBe(200);
    const filteredItems = (await filtered.json()).items;
    expect(filteredItems.length).toBeGreaterThanOrEqual(1);
    expect(filteredItems.every((i) => i.action === 'MERCHANT_LOGIN' && i.merchantId === merchantId)).toBe(true);

    // 22.11 400 em parâmetro inválido; 401 sem token; 403 sem a flag (relatório).
    const badAction = await request.get('/api/v1/master/report/activity?action=nao-existe', {
      headers: adminHeaders,
    });
    expect(badAction.status()).toBe(400);
    expect((await badAction.json()).code).toBe('invalid_argument');
    const noAuth = await request.get('/api/v1/master/report/activity');
    expect(noAuth.status()).toBe(401);
    const nonAdmin = await request.get('/api/v1/master/report/activity', { headers: operadorHeaders });
    expect(nonAdmin.status()).toBe(403);

    // 22.12 Auditoria (Spec 37): MASTER_CREATED no master_audit_log.
    const audit = await request.get('/api/v1/master/audit?action=MASTER_CREATED', {
      headers: adminHeaders,
    });
    expect(audit.status()).toBe(200);
    expect((await audit.json()).items.length).toBeGreaterThanOrEqual(1);

    await client.end();
  });

  test('23. Loja do vendedor + storefront (Spec 48) — criar/atualizar no portal, storefront via API key, 409 duplicada e 404 sem loja', async ({ request }) => {
    // 23.1 Login no portal (senha redefinida no cenário 21 — mesmo lojista).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: 'Nova-Senha-E2E-456' },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 23.2 Sem loja ainda → GET do portal retorna 404 store_not_found.
    const emptyGet = await request.get('/api/v1/portal/store', { headers: portalHeaders });
    expect(emptyGet.status()).toBe(404);
    expect((await emptyGet.json()).code).toBe('store_not_found');

    // 23.3 Criar a loja → 201 com identidade completa (1 loja por lojista).
    const create = await request.post('/api/v1/portal/store', {
      headers: portalHeaders,
      data: {
        name: 'Bella Premium',
        logoUrl: 'https://cdn.example.com/logo.png',
        bannerUrl: 'https://cdn.example.com/banner.png',
        description: 'Conteudo exclusivo',
        socialLinks: [
          { label: 'Instagram', url: 'https://instagram.com/bella' },
          { label: 'Telegram', url: 'https://t.me/bellapremium' },
        ],
        color: '#9B59B6',
        emoji: '⭐',
        title: 'Bella Premium VIP',
        footer: 'Feito com carinho',
      },
    });
    expect(create.status()).toBe(201);
    const created = await create.json();
    expect(created.name).toBe('Bella Premium');
    expect(created.color).toBe('#9B59B6');
    expect(created.socialLinks).toHaveLength(2);
    expect(created.id).toMatch(/^[0-9a-f-]{36}$/i);
    const storeId = created.id;

    // 23.4 Segundo POST da loja para o mesmo lojista → 409 store_already_exists.
    const dup = await request.post('/api/v1/portal/store', {
      headers: portalHeaders,
      data: { name: 'Bella Duplicada' },
    });
    expect(dup.status()).toBe(409);
    expect((await dup.json()).code).toBe('store_already_exists');

    // 23.5 PATCH atualiza qualquer campo de identidade → 200 (campos nulos mantêm).
    const patch = await request.patch('/api/v1/portal/store', {
      headers: portalHeaders,
      data: { name: 'Bella Nova', color: '#8E44AD' },
    });
    expect(patch.status()).toBe(200);
    const patched = await patch.json();
    expect(patched.name).toBe('Bella Nova');
    expect(patched.color).toBe('#8E44AD');
    expect(patched.description).toBe('Conteudo exclusivo'); // não-nulo mantém
    expect(patched.updatedAt).not.toBe(created.updatedAt);

    // 23.6 GET do portal → 200 com o estado atual.
    const get = await request.get('/api/v1/portal/store', { headers: portalHeaders });
    expect(get.status()).toBe(200);
    expect((await get.json()).id).toBe(storeId);

    // 23.7 Storefront via API key do lojista → 200 com identidade + seções vazias
    // (buttons/plans/messages entram nas specs 49/50/51).
    const front = await request.get('/api/v1/foxcash/storefront', {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(front.status()).toBe(200);
    const frontBody = await front.json();
    expect(frontBody.store.name).toBe('Bella Nova');
    expect(frontBody.store.color).toBe('#8E44AD');
    expect(frontBody.store.socialLinks).toHaveLength(2);
    expect(Array.isArray(frontBody.buttons)).toBe(true);
    expect(Array.isArray(frontBody.plans)).toBe(true);
    expect(typeof frontBody.messages).toBe('object');

    // 23.8 Storefront sem token → 401; com chave de API em rota do portal → 403.
    const noAuth = await request.get('/api/v1/foxcash/storefront');
    expect(noAuth.status()).toBe(401);
    const portalWithApiKey = await request.get('/api/v1/portal/store', {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(portalWithApiKey.status()).toBe(403);

    // 23.9 Novo lojista (sem loja) → storefront 404 store_not_found (não vaza dados).
    const other = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Sem Store', email: 'sem-store@example.com', password: 'Senha-E2E-123' },
    });
    expect(other.status()).toBe(201);
    const otherApiKey = (await other.json()).apiKey;
    const otherFront = await request.get('/api/v1/foxcash/storefront', {
      headers: { 'X-Api-Key': otherApiKey },
    });
    expect(otherFront.status()).toBe(404);
    expect((await otherFront.json()).code).toBe('store_not_found');

    // 23.10 Banco: stores com merchant_id correto e auditoria STORE_CREATED/STORE_UPDATED.
    const client = dbClient();
    await client.connect();
    const stores = await client.query('SELECT merchant_id, name, social_links FROM stores WHERE id = $1', [storeId]);
    expect(stores.rows).toHaveLength(1);
    expect(stores.rows[0].merchant_id).toBe(merchantId);
    expect(stores.rows[0].name).toBe('Bella Nova');
    const social = JSON.parse(stores.rows[0].social_links);
    expect(social).toHaveLength(2);
    expect(social[0].label).toBe('Instagram');
    const activity = await client.query(
      "SELECT action FROM platform_activity_log WHERE merchant_id = $1 AND action IN ('STORE_CREATED','STORE_UPDATED') ORDER BY created_at",
      [merchantId],
    );
    const actions = activity.rows.map((r) => r.action);
    expect(actions).toContain('STORE_CREATED');
    expect(actions).toContain('STORE_UPDATED');
    await client.end();
  });

  test('24. Planos + entrega por ações (Spec 49) — plano 3 ações → purchase → PAID → progresso → FAILED → retry → DELIVERED', async ({ request }) => {
    // 24.1 Login no portal (mesmo lojista do cenário 23 — tem loja).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: 'Nova-Senha-E2E-456' },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 24.2 Criar plano no portal → 201 (card visual).
    const plan = await request.post('/api/v1/portal/plans', {
      headers: portalHeaders,
      data: {
        name: 'VIP Ouro E2E',
        price: 49.9,
        description: 'Acesso VIP completo',
        embedColor: '#8E44AD',
        featured: true,
      },
    });
    expect(plan.status()).toBe(201);
    const planBody = await plan.json();
    const planId = planBody.id;
    expect(planBody.active).toBe(true);

    // 24.3 Três ações de entrega (grant_role, send_message, send_link).
    const actions = [
      { seq: 0, type: 'GRANT_ROLE', payload: { roleId: '123456789012345678' } },
      { seq: 1, type: 'SEND_MESSAGE', payload: { text: 'Bem-vindo ao VIP! 💜' } },
      { seq: 2, type: 'SEND_LINK', payload: { url: 'https://t.me/+abc', label: 'Grupo VIP' } },
    ];
    for (const action of actions) {
      const created = await request.post(`/api/v1/portal/plans/${planId}/actions`, {
        headers: portalHeaders,
        data: action,
      });
      expect(created.status()).toBe(201);
      expect((await created.json()).payload).toEqual(action.payload);
    }

    // 24.4 Listagem de planos NÃO devolve o payload (só hasDeliveryActions).
    const list = await request.get('/api/v1/portal/plans', { headers: portalHeaders });
    const listed = (await list.json()).find((p) => p.id === planId);
    expect(listed.hasDeliveryActions).toBe(true);
    expect(listed.payload).toBeUndefined();

    // 24.5 Purchase via API key → 201 com planId + QR/Copia-e-Cola.
    const purchase = await request.post(`/api/v1/foxcash/plans/${planId}/purchase`, {
      headers: { 'X-Api-Key': apiKey },
      data: { platform: 'DISCORD', platformUserId: 'buyer-discord-24', buyerId: 'buyer-24', idempotencyKey: 'e2e-plan-24' },
    });
    expect(purchase.status()).toBe(201);
    const purchaseBody = await purchase.json();
    expect(purchaseBody.planId).toBe(planId);
    expect(purchaseBody.pixQrCode).toBeTruthy();
    expect(purchaseBody.pixCopyPaste).toBeTruthy();
    const txId = purchaseBody.transactionId;

    // 24.6 Replay da mesma chave → MESMA transação (idempotência Spec 28).
    const replay = await request.post(`/api/v1/foxcash/plans/${planId}/purchase`, {
      headers: { 'X-Api-Key': apiKey },
      data: { platform: 'DISCORD', platformUserId: 'buyer-discord-24', idempotencyKey: 'e2e-plan-24' },
    });
    expect(replay.status()).toBe(201);
    expect((await replay.json()).transactionId).toBe(txId);

    // 24.7 Webhook PAID → entrega criada com 3 ações PENDING (MESMA transação).
    const paid = await request.post('/webhooks/pix', {
      data: { externalReference: purchaseBody.externalReference, status: 'PAID', amount: 49.9 },
    });
    expect(paid.status()).toBe(204);

    // 24.8 Pending → entrega PENDING com destinatário + 3 ações PENDING.
    const pending = await request.get('/api/v1/foxcash/deliveries/pending', {
      headers: { 'X-Api-Key': apiKey },
    });
    const pendingBody = await pending.json();
    const delivery = pendingBody.find((d) => d.transactionId === txId);
    expect(delivery).toBeTruthy();
    expect(delivery.status).toBe('PENDING');
    expect(delivery.platform).toBe('DISCORD');
    expect(delivery.platformUserId).toBe('buyer-discord-24');
    expect(delivery.actions).toHaveLength(3);
    expect(delivery.actions.map((a) => a.status)).toEqual(['PENDING', 'PENDING', 'PENDING']);
    expect(delivery.actions[0].payload.roleId).toBe('123456789012345678');
    const deliveryId = delivery.id;

    // 24.9 Executa 1 e 2 (DONE) → entrega segue PENDING; falha a 3 → FAILED.
    for (const seq of [0, 1]) {
      const done = await request.post(`/api/v1/foxcash/deliveries/${deliveryId}/actions/${seq}/complete`, {
        headers: { 'X-Api-Key': apiKey },
        data: { status: 'DONE' },
      });
      expect(done.status()).toBe(204);
    }
    const fail = await request.post(`/api/v1/foxcash/deliveries/${deliveryId}/actions/2/complete`, {
      headers: { 'X-Api-Key': apiKey },
      data: { status: 'FAILED', note: 'grupo cheio' },
    });
    expect(fail.status()).toBe(204);

    // 24.10 by-transaction → entrega FAILED (qualquer ação FAILED pendura? não — FAILED).
    const byTx = await request.get(`/api/v1/foxcash/deliveries/by-transaction/${txId}`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(byTx.status()).toBe(200);
    expect((await byTx.json()).status).toBe('FAILED');

    // 24.11 Replay da ação já concluída → 204 idempotente (gate).
    const replayDone = await request.post(`/api/v1/foxcash/deliveries/${deliveryId}/actions/0/complete`, {
      headers: { 'X-Api-Key': apiKey },
      data: { status: 'DONE' },
    });
    expect(replayDone.status()).toBe(204);

    // 24.12 Retry → reabre FAILED como PENDING e entrega volta a PENDING.
    const retry = await request.post(`/api/v1/foxcash/deliveries/${deliveryId}/retry`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(retry.status()).toBe(200);
    const retried = await retry.json();
    expect(retried.status).toBe('PENDING');
    expect(retried.actions[2].status).toBe('PENDING');

    // 24.13 Completa a última → DELIVERED automaticamente.
    const lastDone = await request.post(`/api/v1/foxcash/deliveries/${deliveryId}/actions/2/complete`, {
      headers: { 'X-Api-Key': apiKey },
      data: { status: 'DONE' },
    });
    expect(lastDone.status()).toBe(204);
    const delivered = await request.get(`/api/v1/foxcash/deliveries/by-transaction/${txId}`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect((await delivered.json()).status).toBe('DELIVERED');

    // 24.14 404 cross-loja: outro lojista (COM loja própria) não vê a entrega.
    const otherEmail = `outro24-${Date.now()}@example.com`;
    const other = await request.post('/api/v1/merchants', {
      data: { name: 'Outro Lojista 24', email: otherEmail, password: 'Senha-E2E-123' },
    });
    const otherApiKey = (await other.json()).apiKey;
    const otherLogin = await request.post('/api/v1/merchant-auth/login', {
      data: { email: otherEmail, password: 'Senha-E2E-123' },
    });
    expect(otherLogin.status()).toBe(200);
    const otherPortal = { Authorization: `Bearer ${(await otherLogin.json()).token}` };
    const otherStore = await request.post('/api/v1/portal/store', {
      headers: otherPortal,
      data: { name: 'Loja do Outro' },
    });
    expect(otherStore.status()).toBe(201);
    const otherView = await request.get(`/api/v1/foxcash/deliveries/by-transaction/${txId}`, {
      headers: { 'X-Api-Key': otherApiKey },
    });
    expect(otherView.status()).toBe(404);
    expect((await otherView.json()).code).toBe('delivery_not_found');

    // 24.15 Plano desativado → purchase 409 plan_inactive.
    const deactivate = await request.post(`/api/v1/portal/plans/${planId}/deactivate`, { headers: portalHeaders });
    expect(deactivate.status()).toBe(204);
    const inactive = await request.post(`/api/v1/foxcash/plans/${planId}/purchase`, {
      headers: { 'X-Api-Key': apiKey },
      data: { platform: 'DISCORD', platformUserId: 'buyer-2', idempotencyKey: 'e2e-plan-24-inactive' },
    });
    expect(inactive.status()).toBe(409);
    expect((await inactive.json()).code).toBe('plan_inactive');

    // 24.16 Banco: entrega + exec com payload/platform_user_id criptografados (v1:).
    const client = dbClient();
    await client.connect();
    const deliveryRow = await client.query('SELECT platform_user_id FROM plan_deliveries WHERE id = $1', [deliveryId]);
    expect(deliveryRow.rows).toHaveLength(1);
    expect(deliveryRow.rows[0].platform_user_id).toMatch(/^v1:/);
    const execRow = await client.query('SELECT payload_snapshot FROM plan_delivery_actions_exec WHERE delivery_id = $1 ORDER BY seq', [deliveryId]);
    expect(execRow.rows).toHaveLength(3);
    for (const row of execRow.rows) {
      expect(row.payload_snapshot).toMatch(/^v1:/);
    }
    const txRow = await client.query('SELECT plan_id, platform_user_id FROM pix_transactions WHERE id = $1', [txId]);
    expect(txRow.rows[0].plan_id).toBe(planId);
    expect(txRow.rows[0].platform_user_id).toMatch(/^v1:/);
    await client.end();
  });

  test('25. Botões do menu (Spec 50) — CRUD + reordenação no portal, storefront com botões ativos, 400/404/409 e payload criptografado', async ({ request }) => {
    // 25.1 Login no portal (mesmo lojista dos cenários 23/24 — tem loja).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: 'Nova-Senha-E2E-456' },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 25.2 Lista vazia antes de criar → 200 [].
    const empty = await request.get('/api/v1/portal/store/buttons', { headers: portalHeaders });
    expect(empty.status()).toBe(200);
    expect(await empty.json()).toEqual([]);

    // 25.3 Criar botões (BUY sem payload; SOCIAL/LINK/FAQ com payload tipado) → 201.
    const buy = await request.post('/api/v1/portal/store/buttons', {
      headers: portalHeaders,
      data: { label: 'Comprar', action: 'BUY' },
    });
    expect(buy.status()).toBe(201);
    const buyBody = await buy.json();
    expect(buyBody.position).toBe(0);
    expect(buyBody.active).toBe(true);

    const social = await request.post('/api/v1/portal/store/buttons', {
      headers: portalHeaders,
      data: { label: 'Instagram', action: 'SOCIAL', payload: { url: 'https://instagram.com/bella', label: 'Instagram' } },
    });
    expect(social.status()).toBe(201);
    const socialBody = await social.json();
    expect(socialBody.position).toBe(1);
    const socialId = socialBody.id;

    const faq = await request.post('/api/v1/portal/store/buttons', {
      headers: portalHeaders,
      data: { label: 'Dúvidas', action: 'FAQ', payload: { text: 'Como recebo o acesso?' } },
    });
    expect(faq.status()).toBe(201);
    const faqBody = await faq.json();
    expect(faqBody.position).toBe(2);
    const faqId = faqBody.id;

    // 25.4 Botão inválido → 400 (action inexistente; payload sem url obrigatória).
    const badAction = await request.post('/api/v1/portal/store/buttons', {
      headers: portalHeaders,
      data: { label: 'X', action: 'NAO_EXISTE' },
    });
    expect(badAction.status()).toBe(400);
    expect((await badAction.json()).code).toBe('invalid_argument');

    const badPayload = await request.post('/api/v1/portal/store/buttons', {
      headers: portalHeaders,
      data: { label: 'Link', action: 'LINK', payload: { label: 'sem url' } },
    });
    expect(badPayload.status()).toBe(400);

    // 25.5 Lista → 3 botões em ordem de position.
    const list = await request.get('/api/v1/portal/store/buttons', { headers: portalHeaders });
    const listed = await list.json();
    expect(listed).toHaveLength(3);
    expect(listed.map((b) => b.label)).toEqual(['Comprar', 'Instagram', 'Dúvidas']);
    expect(listed[1].payload.url).toBe('https://instagram.com/bella');

    // 25.6 PATCH atualiza label/action/payload (nulos mantêm) → 200.
    const patch = await request.patch(`/api/v1/portal/store/buttons/${faqId}`, {
      headers: portalHeaders,
      data: { label: 'FAQ', action: 'FAQ', payload: { text: 'Acesso em até 5 min' } },
    });
    expect(patch.status()).toBe(200);
    expect((await patch.json()).label).toBe('FAQ');

    // 25.7 Desativar um botão → não sai no storefront (GET por id ainda mostra active=false).
    const deactivate = await request.patch(`/api/v1/portal/store/buttons/${faqId}`, {
      headers: portalHeaders,
      data: { active: false },
    });
    expect(deactivate.status()).toBe(200);
    expect((await deactivate.json()).active).toBe(false);

    // 25.8 Storefront via API key → apenas botões ATIVOS em ordem (FAQ inativo fora).
    const front = await request.get('/api/v1/foxcash/storefront', {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(front.status()).toBe(200);
    const frontBody = await front.json();
    expect(Array.isArray(frontBody.buttons)).toBe(true);
    expect(frontBody.buttons.map((b) => b.label)).toEqual(['Comprar', 'Instagram']);
    expect(frontBody.buttons[1].action).toBe('SOCIAL');
    expect(frontBody.buttons[1].payload.url).toBe('https://instagram.com/bella');

    // 25.9 Reordenação: [Instagram, Comprar, FAQ] → 204; lista reflete nova ordem.
    const order = await request.put('/api/v1/portal/store/buttons/order', {
      headers: portalHeaders,
      data: { ids: [socialId, buyBody.id, faqId] },
    });
    expect(order.status()).toBe(204);
    const reordered = await request.get('/api/v1/portal/store/buttons', { headers: portalHeaders });
    const reorderedBody = await reordered.json();
    expect(reorderedBody.map((b) => b.label)).toEqual(['Instagram', 'Comprar', 'FAQ']);
    expect(reorderedBody[0].position).toBe(0);

    // 25.10 Reordenação com id de outro lojista → 404 button_not_found (sem vazar).
    const foreign = await request.put('/api/v1/portal/store/buttons/order', {
      headers: portalHeaders,
      data: { ids: [crypto.randomUUID(), buyBody.id, faqId] },
    });
    expect(foreign.status()).toBe(404);
    expect((await foreign.json()).code).toBe('button_not_found');

    // 25.11 DELETE de botão inexistente (cross-loja) → 404; DELETE válido → 204.
    const del404 = await request.delete(`/api/v1/portal/store/buttons/${crypto.randomUUID()}`, {
      headers: portalHeaders,
    });
    expect(del404.status()).toBe(404);
    const del = await request.delete(`/api/v1/portal/store/buttons/${faqId}`, {
      headers: portalHeaders,
    });
    expect(del.status()).toBe(204);

    // 25.12 Banco: payload criptografado (v1:) e auditoria BUTTON_CREATED/UPDATED/DELETED.
    const client = dbClient();
    await client.connect();
    const rows = await client.query('SELECT label, action, payload FROM store_buttons WHERE store_id = (SELECT id FROM stores WHERE merchant_id = $1) ORDER BY position', [merchantId]);
    expect(rows.rows).toHaveLength(2); // FAQ removido
    for (const row of rows.rows) {
      if (row.action === 'SOCIAL' || row.action === 'LINK' || row.action === 'FAQ' || row.action === 'PREVIEW') {
        expect(row.payload).toMatch(/^v1:/); // criptografado em repouso
      }
    }
    const socialRow = rows.rows.find((r) => r.action === 'SOCIAL');
    expect(socialRow.payload).toMatch(/^v1:/);
    const activity = await client.query(
      "SELECT DISTINCT action FROM platform_activity_log WHERE merchant_id = $1 AND action IN ('BUTTON_CREATED','BUTTON_UPDATED','BUTTON_DELETED')",
      [merchantId],
    );
    const actions = activity.rows.map((r) => r.action);
    expect(actions).toContain('BUTTON_CREATED');
    expect(actions).toContain('BUTTON_UPDATED');
    expect(actions).toContain('BUTTON_DELETED');
    await client.end();
  });

  test('26. Mensagens automáticas + agendamento (Spec 51) — upsert por etapa no portal, storefront com mensagens ativas, bot busca vencidas com recipients e confirma envio', async ({ request }) => {
    // 26.1 Login no portal (mesmo lojista dos cenários 23–25 — tem loja).
    const login = await request.post('/api/v1/merchant-auth/login', {
      data: { email, password: 'Nova-Senha-E2E-456' },
    });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 26.2 Lista vazia antes de criar → 200 [].
    const empty = await request.get('/api/v1/portal/store/messages', { headers: portalHeaders });
    expect(empty.status()).toBe(200);
    expect(await empty.json()).toEqual([]);

    // 26.3 Upsert por event: cria WELCOME (201) e FAQ (201) — 1 mensagem por etapa.
    const welcome = await request.post('/api/v1/portal/store/messages', {
      headers: portalHeaders,
      data: { event: 'welcome', text: 'Olá 👋 Bem-vindo à Bella Premium.' },
    });
    expect(welcome.status()).toBe(201);
    const welcomeBody = await welcome.json();
    expect(welcomeBody.event).toBe('WELCOME');
    expect(welcomeBody.active).toBe(true);
    const welcomeId = welcomeBody.id;

    const faq = await request.post('/api/v1/portal/store/messages', {
      headers: portalHeaders,
      data: { event: 'faq', text: 'Como recebo o acesso? Em até 5 minutos.' },
    });
    expect(faq.status()).toBe(201);
    const faqId = (await faq.json()).id;

    // 26.4 Mesmo event de novo → 200 (atualização — nunca duplica a etapa).
    const update = await request.post('/api/v1/portal/store/messages', {
      headers: portalHeaders,
      data: { event: 'WELCOME', text: 'Olá 👋 Bem-vindo à Bella Premium! 💜' },
    });
    expect(update.status()).toBe(200);
    expect((await update.json()).text).toContain('💜');

    // 26.5 Validações → 400 (event inválido; text vazio).
    const badEvent = await request.post('/api/v1/portal/store/messages', {
      headers: portalHeaders,
      data: { event: 'NAO_EXISTE', text: 'x' },
    });
    expect(badEvent.status()).toBe(400);
    expect((await badEvent.json()).code).toBe('invalid_argument');
    const blankText = await request.post('/api/v1/portal/store/messages', {
      headers: portalHeaders,
      data: { event: 'promo', text: '' },
    });
    expect(blankText.status()).toBe(400);

    // 26.6 PATCH atualiza text/active → 200; PATCH de id inexistente → 404 message_not_found.
    const patch = await request.patch(`/api/v1/portal/store/messages/${faqId}`, {
      headers: portalHeaders,
      data: { text: 'Acesso liberado em até 5 minutos.', active: false },
    });
    expect(patch.status()).toBe(200);
    expect((await patch.json()).active).toBe(false);
    const patch404 = await request.patch(`/api/v1/portal/store/messages/${crypto.randomUUID()}`, {
      headers: portalHeaders,
      data: { text: 'x' },
    });
    expect(patch404.status()).toBe(404);
    expect((await patch404.json()).code).toBe('message_not_found');

    // 26.7 Lista → 2 mensagens (WELCOME ativa, FAQ inativa).
    const list = await request.get('/api/v1/portal/store/messages', { headers: portalHeaders });
    const listed = await list.json();
    expect(listed).toHaveLength(2);
    expect(listed.find((m) => m.event === 'FAQ').active).toBe(false);
    expect(listed.find((m) => m.id === welcomeId).text).toContain('💜');

    // 26.8 Storefront → mensagens ATIVAS por event (FAQ inativa NÃO sai; welcome sim).
    const front = await request.get('/api/v1/foxcash/storefront', {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(front.status()).toBe(200);
    const frontBody = await front.json();
    expect(frontBody.messages.welcome).toContain('Bem-vindo');
    expect(frontBody.messages.faq).toBeUndefined();

    // 26.9 DELETE da mensagem FAQ → 204 (e sai da lista).
    const del = await request.delete(`/api/v1/portal/store/messages/${faqId}`, {
      headers: portalHeaders,
    });
    expect(del.status()).toBe(204);
    const afterDelete = await request.get('/api/v1/portal/store/messages', { headers: portalHeaders });
    expect((await afterDelete.json()).map((m) => m.event)).toEqual(['WELCOME']);

    // 26.10 Agendamento one-shot no passado (já vencida) → 201.
    const past = new Date(Date.now() - 60_000).toISOString();
    const schedule = await request.post('/api/v1/portal/store/messages/schedule', {
      headers: portalHeaders,
      data: { text: '🔥 Promoção de sexta! 50% OFF', scheduledAt: past, audience: 'BUYERS' },
    });
    expect(schedule.status()).toBe(201);
    const scheduledBody = await schedule.json();
    expect(scheduledBody.status).toBe('SCHEDULED');
    const scheduledId = scheduledBody.id;

    // 26.11 Agendamento recorrente (cron a cada minuto) → 201; validações → 400.
    const cron = await request.post('/api/v1/portal/store/messages/schedule', {
      headers: portalHeaders,
      data: { text: 'Lembrete automático', cron: '0 * * * * *' },
    });
    expect(cron.status()).toBe(201);
    const cronId = (await cron.json()).id;

    const noSchedule = await request.post('/api/v1/portal/store/messages/schedule', {
      headers: portalHeaders,
      data: { text: 'x' }, // nenhum de scheduledAt/cron → 400
    });
    expect(noSchedule.status()).toBe(400);
    const badCron = await request.post('/api/v1/portal/store/messages/schedule', {
      headers: portalHeaders,
      data: { text: 'x', cron: '99 99 99 99 99 99' },
    });
    expect(badCron.status()).toBe(400);
    const badAudience = await request.post('/api/v1/portal/store/messages/schedule', {
      headers: portalHeaders,
      data: { text: 'x', scheduledAt: past, audience: 'ALL' }, // ALL = evolução nesta versão
    });
    expect(badAudience.status()).toBe(400);

    // 26.12 Bot busca as vencidas (dueBefore = agora + 2min) → one-shot (passado)
    // + cron (próximo disparo dentro de 2 min), com recipients = compradores
    // entregues da loja (cenário 24: 'buyer-discord-24' DELIVERED).
    const dueBefore = new Date(Date.now() + 120_000).toISOString();
    const due = await request.get(
      `/api/v1/foxcash/scheduled-messages?dueBefore=${encodeURIComponent(dueBefore)}`,
      { headers: { 'X-Api-Key': apiKey } },
    );
    expect(due.status()).toBe(200);
    const dueBody = await due.json();
    expect(dueBody.length).toBeGreaterThanOrEqual(2);
    const dueOneShot = dueBody.find((m) => m.id === scheduledId);
    expect(dueOneShot).toBeTruthy();
    expect(dueOneShot.text).toContain('Promoção');
    expect(dueOneShot.audience).toBe('BUYERS');
    expect(dueOneShot.recipients).toContain('buyer-discord-24');
    expect(dueBody.find((m) => m.id === cronId)).toBeTruthy();

    // 26.13 Confirma o envio → 204; replay idempotente → 204; some das vencidas.
    const sent = await request.post(`/api/v1/foxcash/scheduled-messages/${scheduledId}/sent`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(sent.status()).toBe(204);
    const sentReplay = await request.post(`/api/v1/foxcash/scheduled-messages/${scheduledId}/sent`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(sentReplay.status()).toBe(204);
    const dueAfter = await request.get(
      `/api/v1/foxcash/scheduled-messages?dueBefore=${encodeURIComponent(dueBefore)}`,
      { headers: { 'X-Api-Key': apiKey } },
    );
    expect((await dueAfter.json()).find((m) => m.id === scheduledId)).toBeUndefined();

    // 26.14 dueBefore inválido → 400 invalid_argument.
    const badDue = await request.get('/api/v1/foxcash/scheduled-messages?dueBefore=nao-e-data', {
      headers: { 'X-Api-Key': apiKey },
    });
    expect(badDue.status()).toBe(400);

    // 26.15 404 cross-loja: outro lojista (com loja própria) não confirma nem vê.
    const otherEmail = `outro26-${Date.now()}@example.com`;
    const other = await request.post('/api/v1/merchants', {
      data: { name: 'Outro Lojista 26', email: otherEmail, password: 'Senha-E2E-123' },
    });
    expect(other.status()).toBe(201);
    const otherApiKey = (await other.json()).apiKey;
    const otherLogin = await request.post('/api/v1/merchant-auth/login', {
      data: { email: otherEmail, password: 'Senha-E2E-123' },
    });
    expect(otherLogin.status()).toBe(200);
    const otherPortal = { Authorization: `Bearer ${(await otherLogin.json()).token}` };
    const otherStore = await request.post('/api/v1/portal/store', {
      headers: otherPortal,
      data: { name: 'Loja do Outro 26' },
    });
    expect(otherStore.status()).toBe(201);
    const otherConfirm = await request.post(`/api/v1/foxcash/scheduled-messages/${scheduledId}/sent`, {
      headers: { 'X-Api-Key': otherApiKey },
    });
    expect(otherConfirm.status()).toBe(404);
    expect((await otherConfirm.json()).code).toBe('message_not_found');

    // 26.16 Banco: mensagens persistidas, agendamento one-shot SENT com sent_at,
    // cron ainda SCHEDULED e auditoria MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED.
    const client = dbClient();
    await client.connect();
    const storeRow = await client.query('SELECT id FROM stores WHERE merchant_id = $1', [merchantId]);
    const storeId = storeRow.rows[0].id;
    const msgRows = await client.query(
      'SELECT event, text FROM store_messages WHERE store_id = $1 ORDER BY event',
      [storeId],
    );
    expect(msgRows.rows.map((r) => r.event)).toEqual(['WELCOME']); // FAQ deletada no 26.9
    const schedRow = await client.query(
      'SELECT status, sent_at FROM store_scheduled_messages WHERE id = $1',
      [scheduledId],
    );
    expect(schedRow.rows[0].status).toBe('SENT');
    expect(schedRow.rows[0].sent_at).toBeTruthy();
    const cronRow = await client.query('SELECT status FROM store_scheduled_messages WHERE id = $1', [cronId]);
    expect(cronRow.rows[0].status).toBe('SCHEDULED');
    const activity = await client.query(
      "SELECT DISTINCT action FROM platform_activity_log WHERE merchant_id = $1 AND action IN ('MESSAGE_CREATED','MESSAGE_UPDATED','MESSAGE_DELETED','MESSAGE_SCHEDULED')",
      [merchantId],
    );
    const actions = activity.rows.map((r) => r.action);
    expect(actions).toContain('MESSAGE_CREATED');
    expect(actions).toContain('MESSAGE_UPDATED');
    expect(actions).toContain('MESSAGE_DELETED');
    expect(actions).toContain('MESSAGE_SCHEDULED');
    await client.end();
  });

  test('27. MFA TOTP do master (Spec 44) — ativação 2 passos, login 2FA, recovery 1x e desativação', async ({ request }) => {
    // 27.1 Master MFA via SQL com hash BCrypt REAL de 'Senha-E2E-123' (o login
    // 2FA exige senha válida) + sessão direta (padrão 13.9). Inserido via SQL
    // porque o limite de perfis (max-profiles=4) já foi atingido pelos cenários
    // anteriores (13/14/18/21) — criar via API devolveria 409 too_many_masters.
    const mfaPassword = 'Senha-E2E-123';
    const mfaUsername = 'e2e-mfa-user';
    const mfaMasterId = nodeCrypto.randomUUID();
    const mfaClient = dbClient();
    await mfaClient.connect();
    await mfaClient.query(
      'INSERT INTO masters (id, username, password_hash, name, active, can_manage_masters, created_at) VALUES ($1,$2,$3,$4,true,false,$5)',
      [mfaMasterId, mfaUsername,
        '$2a$10$15Pv6f0BRyiTgp.DrVUjwenfdnTaAh5uY1EGRfn0oso2M3GK9IIv6', // BCrypt de 'Senha-E2E-123'
        'Master MFA E2E', new Date().toISOString()],
    );
    await mfaClient.end();

    // 27.2 Login real do master MFA (sem MFA ainda → comportamento atual).
    const loginBefore = await request.post('/api/v1/auth/master/login', {
      data: { username: mfaUsername, password: mfaPassword },
    });
    expect(loginBefore.status()).toBe(200);
    const mfaToken = (await loginBefore.json()).token;
    expect(mfaToken).toMatch(/^mst_/);
    const mfaHeaders = { Authorization: `Bearer ${mfaToken}` };

    // 27.4 Ativação passo 1: gera secret → URI do QR + chave manual (1x).
    const activate = await request.post('/api/v1/master/mfa/activate', {
      headers: mfaHeaders,
    });
    expect(activate.status()).toBe(200);
    const activateBody = await activate.json();
    expect(activateBody.otpauthUri).toContain('otpauth://totp/FoxCash:');
    expect(activateBody.manualEntryKey).toMatch(/^[A-Z2-7]{32}$/);
    const secret = activateBody.manualEntryKey;

    // 27.5 Ativação passo 2: TOTP calculado no teste (RFC 6238) → confirma a
    // posse do app → ativa + 8 recovery codes one-time.
    const confirmCode = totpCode(secret, Math.floor(Date.now() / 1000));
    const confirm = await request.post('/api/v1/master/mfa/confirm', {
      headers: mfaHeaders,
      data: { code: confirmCode },
    });
    expect(confirm.status()).toBe(200);
    const confirmBody = await confirm.json();
    expect(confirmBody.recoveryCodes).toHaveLength(8);
    expect(confirmBody.recoveryCodes[0]).toMatch(/^mrc_[0-9a-f]{32}$/);
    const recoveryCode = confirmBody.recoveryCodes[0];

    // 27.6 Com MFA ativo, login SEM código → 401 (senha correta não basta).
    const noCode = await request.post('/api/v1/auth/master/login', {
      data: { username: mfaUsername, password: mfaPassword },
    });
    expect(noCode.status()).toBe(401);
    expect((await noCode.json()).code).toBe('unauthorized');

    // 27.7 Login com TOTP válido → 200 (2FA completo).
    const validTotp = totpCode(secret, Math.floor(Date.now() / 1000));
    const withTotp = await request.post('/api/v1/auth/master/login', {
      data: { username: mfaUsername, password: mfaPassword, totpCode: validTotp },
    });
    expect(withTotp.status()).toBe(200);
    expect((await withTotp.json()).token).toMatch(/^mst_/);

    // 27.8 Login com TOTP inválido → 401 uniforme (sem oráculo).
    const invalidTotp = validTotp === '000000' ? '000001' : '000000';
    const withBadTotp = await request.post('/api/v1/auth/master/login', {
      data: { username: mfaUsername, password: mfaPassword, totpCode: invalidTotp },
    });
    expect(withBadTotp.status()).toBe(401);
    expect((await withBadTotp.json()).code).toBe('unauthorized');

    // 27.9 Recovery code single-use: login com mrc_ → 200; reuso → 401.
    const withRecovery = await request.post('/api/v1/auth/master/login', {
      data: { username: mfaUsername, password: mfaPassword, totpCode: recoveryCode },
    });
    expect(withRecovery.status()).toBe(200);
    expect((await withRecovery.json()).token).toMatch(/^mst_/);

    const recoveryReuse = await request.post('/api/v1/auth/master/login', {
      data: { username: mfaUsername, password: mfaPassword, totpCode: recoveryCode },
    });
    expect(recoveryReuse.status()).toBe(401);

    // 27.10 Banco ANTES da desativação: secret TOTP criptografado em repouso
    // (v1:), nunca em claro; recovery code usado marcado com used_at.
    const stateClient = dbClient();
    await stateClient.connect();
    const methodRow = await stateClient.query(
      'SELECT m.status, t.secret_encrypted FROM master_mfa_methods m JOIN master_totp t ON t.method_id = m.id WHERE m.master_id = $1',
      [mfaMasterId],
    );
    expect(methodRow.rows[0].status).toBe('ACTIVE');
    expect(methodRow.rows[0].secret_encrypted).toMatch(/^v1:/);
    expect(methodRow.rows[0].secret_encrypted).not.toContain(secret);
    const recoveryRow = await stateClient.query(
      'SELECT used_at FROM master_recovery_codes WHERE code_hash = $1',
      [nodeCrypto.createHash('sha256').update(recoveryCode).digest('hex')],
    );
    expect(recoveryRow.rows[0].used_at).toBeTruthy();
    await stateClient.end();

    // 27.11 Desativação: senha + TOTP válido → 204; login volta ao fluxo simples.
    const deactivateTotp = totpCode(secret, Math.floor(Date.now() / 1000));
    const deactivate = await request.post('/api/v1/master/mfa/deactivate', {
      headers: mfaHeaders,
      data: { code: deactivateTotp, password: mfaPassword },
    });
    expect(deactivate.status()).toBe(204);

    const loginAfter = await request.post('/api/v1/auth/master/login', {
      data: { username: mfaUsername, password: mfaPassword },
    });
    expect(loginAfter.status()).toBe(200);

    // 27.12 Banco APÓS a desativação: método e detalhes removidos em cascata
    // (V42) — re-ativação não conflita com estado residual.
    const afterClient = dbClient();
    await afterClient.connect();
    const goneRow = await afterClient.query(
      'SELECT COUNT(*) AS n FROM master_mfa_methods WHERE master_id = $1',
      [mfaMasterId],
    );
    expect(Number(goneRow.rows[0].n)).toBe(0);
    await afterClient.end();
  });
});
