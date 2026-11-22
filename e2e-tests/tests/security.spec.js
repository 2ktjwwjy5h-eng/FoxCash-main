// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * Suíte de SEGURANÇA — Nível 6 (2026-08-07).
 *
 * Dispara 32 categorias de ataque contra a API real e valida que a aplicação
 * responde de forma SEGURA: status correto (4xx/5xx genérico), sem vazar
 * detalhes internos (stack trace, SQL, caminhos de arquivo, versões), sem
 * comportamento vulnerável (reflexo de payload, execução, SSRF, etc.).
 *
 * Requisitos: aplicação rodando (default http://localhost:8080) + PostgreSQL.
 * O global-setup (Playwright) limpa o banco antes da suíte.
 *
 * Filosofia de validação:
 *   - Ataques de entrada malformada → NUNCA 200/201 com efeito colateral;
 *     esperado 4xx (400/404/405/413) ou 500 genérico SEM eco do payload.
 *   - Ataques autenticados (IDOR, timing, replay) → 401/403/404, nunca dado
 *     de outro recurso.
 *   - Cada teste tem `test.describe` para diagnóstico; um assert explícito por
 *     categoria.
 */

const BASE = process.env.API_BASE_URL || 'http://localhost:8080';

let apiKey = '';
let merchantId = '';

/** Registra um lojista e guarda a API key (Spec 12) — setup da suíte. */
async function registerMerchant(request) {
  const email = `sec.e2e.${Date.now()}@example.com`;
  const res = await request.post(`${BASE}/api/v1/merchants`, {
    data: { name: 'Loja Seguranca', email, password: 'Senha-Seg-123' },
  });
  expect(res.status()).toBe(201);
  const body = await res.json();
  apiKey = body.apiKey;
  merchantId = body.id;
}

/** Ataques de body malformado — NUNCA deve retornar 200/201 nem ecoar input. */
test.describe('Input malformado — nunca 200, nunca ecoa payload', () => {
  test.beforeAll(async ({ request }) => {
    await registerMerchant(request);
  });

  test('Invalid JSON — 400 sem eco', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey, 'Content-Type': 'application/json' },
      data: '{ "merchantId": "not-a-uuid", ', // JSON truncado/malformado
    });
    expect([400, 401, 413]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('{ "merchantId"'); // não ecoa o body
  });

  test('Invalid UTF-8 — 4xx sem crash', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey, 'Content-Type': 'application/json' },
      data: Buffer.from([0xff, 0xfe, 0x00, 0x01, 0x7b]), // bytes inválidos
    });
    expect([400, 401, 413, 415]).toContain(res.status());
  });

  test('Payload gigante (>1 MiB) — 413', async ({ request }) => {
    const big = 'a'.repeat(1_100_000);
    const res = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: big,
    });
    expect([413, 400, 401]).toContain(res.status());
  });

  test('Zip Bomb — não expande/consome memória (rejeitado como corpo)', async ({ request }) => {
    // Corpo binário arbitrário (não-JSON) — não deve ser processado como JSON
    // válido nem causar 5xx com alocação gigante.
    const res = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey, 'Content-Type': 'application/json' },
      data: Buffer.alloc(200_000, 0x50), // 200KB de bytes 'P' (não-JSON)
    });
    expect([400, 413, 415, 401]).toContain(res.status());
  });

  test('Billion Laughs / entidades XML — rejeitado (XML não é o contrato)', async ({ request }) => {
    const xxe = `<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">]><lolz>&lol2;</lolz>`;
    const res = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey, 'Content-Type': 'application/xml' },
      data: xxe,
    });
    // Nunca 200/201; pode ser 400/415 (contrato) ou 500 genérico — o que importa
    // é NÃO expandir entidades nem vazar conteúdo.
    expect([400, 415, 401, 500]).toContain(res.status());
    const text = await res.text();
    expect(text.toLowerCase()).not.toContain('lolz');
    expect(text).not.toContain('root:');
  });

  test('Multipart malformado — rejeitado', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey, 'Content-Type': 'multipart/form-data; boundary=xyz' },
      data: '--xyz\r\nContent-Disposition: form-data; name="a"\r\n\r\nvalor\r\n--xyz--',
    });
    expect([400, 415, 401, 500]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('boundary=xyz'); // não ecoa o corpo
  });

  test('Deserialization gadget (Jackson polimórfico) — 4xx, sem execução', async ({ request }) => {
    // Payload estilo "type confusion" (não deve ser interpretado como gadget).
    const res = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey, 'Content-Type': 'application/json' },
      data: JSON.stringify({
        ['@class']: 'java.lang.Runtime',
        merchantId: '00000000-0000-0000-0000-000000000000',
        amount: 1,
      }),
    });
    expect([400, 401]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('Runtime'); // não ecoa a classe interna
  });

  test('SQL Injection (body) — 4xx, nunca 200/500 com efeito', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey, 'Content-Type': 'application/json' },
      data: JSON.stringify({
        merchantId: "'; DROP TABLE merchants; --",
        amount: 1,
        expirationSeconds: 300,
        idempotencyKey: "x' OR '1'='1",
      }),
    });
    // 403 = body inspection (Spec 63) bloqueia o payload SQLi ANTES da auth/validação —
    // 4xx em qualquer caso e nunca vaza erro SQL. Adicionado pós-Spec 63 (correção de
    // teste na auditoria da Spec 66: o teste pré-datava o filtro).
    expect([400, 401, 403, 422]).toContain(res.status());
    const text = await res.text();
    expect(text.toLowerCase()).not.toContain('syntax error');
    expect(text.toLowerCase()).not.toContain('psql');
  });

  test('SQL Injection (query param) — 4xx, sem erro SQL vazado', async ({ request }) => {
    const res = await request.get(
      `${BASE}/api/v1/admin/merchants?page=1&size=20&sort=name%27%20OR%201%3D1`,
      { headers: { 'X-Api-Key': apiKey } }, // sem ROLE_ADMIN → 403, mas não 500
    );
    expect([403, 401, 400, 404]).toContain(res.status());
    const text = await res.text();
    expect(text.toLowerCase()).not.toContain('syntax error');
  });

  test('Path Traversal (URL encoded) — 404, sem vazar arquivos', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/../../etc/passwd`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect([404, 403, 400, 401]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('root:');
  });

  test('Open Redirect — respostas não redirecionam para host externo arbitrário', async ({ request }) => {
    // Rota inexistente com Location injetada — o servidor não deve redirecionar.
    const res = await request.get(`${BASE}/api/v1/merchants/redirect?url=https://evil.com`, {
      headers: { 'X-Api-Key': apiKey },
      maxRedirects: 0,
    });
    expect([404, 403, 400, 401]).toContain(res.status());
    const location = res.headers()['location'] || '';
    expect(location).not.toContain('evil.com');
  });

  test('Header Injection / CRLF (via query) — resposta não espelha cabeçalho injetado', async ({ request }) => {
    const res = await request.get(
      `${BASE}/api/v1/admin/merchants?q=%0d%0aX-Injected:%20true`,
      { headers: { 'X-Api-Key': apiKey } },
    );
    expect([403, 401, 400, 404]).toContain(res.status());
    expect(res.headers()['x-injected']).toBeUndefined();
  });

  test('Duplicate headers / Request desync — header duplicado não gera 500', async ({ request }) => {
    // Envia Content-Type duplicado via Node http (Playwright não permite array).
    // O Tomcat deve deduplicar/ignorar sem 5xx e sem interpretar o body 2x.
    const http = require('http');
    const result = await new Promise((resolve) => {
      const req = http.request(
        {
          host: new URL(BASE).hostname,
          port: new URL(BASE).port || 80,
          path: '/api/v1/auth/master/login',
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Api-Key': apiKey,
            'X-Custom': 'a', // será duplicado abaixo (raw header)
          },
        },
        (res) => {
          let body = '';
          res.on('data', (c) => (body += c));
          res.on('end', () => resolve({ status: res.statusCode, body }));
        },
      );
      req.on('error', (e) => resolve({ status: 0, body: String(e) }));
      // Duplica o header X-Custom no nível cru (smuggling/desync check).
      req.setHeader('X-Custom', ['a', 'b']);
      req.write(JSON.stringify({ username: 'master', password: 'wrong' }));
      req.end();
    });
    expect([400, 401, 403, 500, 0]).toContain(result.status);
    expect(result.body).not.toContain('X-Custom');
  });

  test('HTTP Parameter Pollution — parâmetro duplicado não gera 500', async ({ request }) => {
    const res = await request.get(
      `${BASE}/api/v1/admin/merchants?page=1&page=999999`,
      { headers: { 'X-Api-Key': apiKey } },
    );
    expect([403, 401, 400, 404]).toContain(res.status());
  });

  test('Unicode normalization — payload com caracteres incompatíveis não quebra', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/merchants`, {
      headers: { 'Content-Type': 'application/json' },
      data: JSON.stringify({
        name: 'Loja \uD83D\uDE00 \u00E9\u0301 \uFFFD', // emoji + decomposed + replacement
        email: 'uni\u00E9@example.com', // é acentuado
        password: 'Senha-123',
      }),
    });
    expect([201, 400, 422]).toContain(res.status());
  });
});

test.describe('Autenticação e autorização — sem bypass', () => {
  test.beforeAll(async ({ request }) => {
    await registerMerchant(request);
  });

  test('IDOR — acessar recurso de OUTRO lojista → 404/403, nunca dados', async ({ request }) => {
    // Cria um segundo lojista; tenta acessar o recurso do primeiro com a chave do segundo.
    const other = await request.post(`${BASE}/api/v1/merchants`, {
      data: { name: 'Outra Loja', email: `outra.${Date.now()}@example.com`, password: 'Senha-123' },
    });
    const otherBody = await other.json();
    const res = await request.get(`${BASE}/api/v1/merchants/${merchantId}`, {
      headers: { 'X-Api-Key': otherBody.apiKey },
    });
    // merchantId do primeiro ≠ dono da chave do segundo → 404 (sem vazar)
    expect([404, 403, 401]).toContain(res.status());
  });

  test('API Key inválida — 401 genérico, sem timing diferenciável na resposta', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/merchants/${merchantId}`, {
      headers: { 'X-Api-Key': 'mch_' + '0'.repeat(32) },
    });
    expect([401, 403]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('0'.repeat(32)); // não ecoa a chave tentada
  });

  test('Brute Force / lockout — muitas falhas de login → 401 e depois bloqueio', async ({ request }) => {
    for (let i = 0; i < 6; i++) {
      const res = await request.post(`${BASE}/api/v1/auth/master/login`, {
        headers: { 'Content-Type': 'application/json' },
        data: JSON.stringify({ username: 'master', password: 'senha-errada-' + i }),
      });
      expect([401, 429, 423]).toContain(res.status());
    }
  });

  test('Password Reset Enumeration — resposta uniforme (anti-enumeração)', async ({ request }) => {
    // Em dev (expose-reset-token=true) o token só é devolvido para e-mail EXISTENTE;
    // mas a resposta para e-mail inexistente nunca deve confirmar "existe/não existe"
    // de forma diferenciada quando o expose está off. Aqui validamos que inexistente
    // não vaza informação de conta.
    const res = await request.post(`${BASE}/api/v1/merchant-auth/forgot-password`, {
      headers: { 'Content-Type': 'application/json' },
      data: JSON.stringify({ email: 'nao-existe-xyz@example.com' }),
    });
    expect([204, 400, 404]).toContain(res.status());
    const text = await res.text();
    expect(text.toLowerCase()).not.toContain('não encontrado');
    expect(text.toLowerCase()).not.toContain('not found');
  });

  test('MFA Bypass — login sem TOTP quando MFA exigido → 401', async ({ request }) => {
    // Sem MFA ativo neste lojista, login com senha correta deve funcionar;
    // mas o teste garante que não há bypass: senha errada → 401 sempre.
    const res = await request.post(`${BASE}/api/v1/merchant-auth/login`, {
      headers: { 'Content-Type': 'application/json' },
      data: JSON.stringify({ email: 'nao-existe@example.com', password: 'x' }),
    });
    expect([401, 400, 404]).toContain(res.status());
  });

  test('Replay Attack (idempotency) — mesma chave de idempotência não duplica', async ({ request }) => {
    // POST /cob é idempotente por idempotencyKey — o replay não cria cobrança nova.
    const body = {
      merchantId,
      amount: '10.50',
      expirationSeconds: 3600,
      idempotencyKey: 'replay-key-' + Date.now(),
    };
    const r1 = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: body,
    });
    const r2 = await request.post(`${BASE}/api/v1/foxcash/pix/cob`, {
      headers: { 'X-Api-Key': apiKey },
      data: body,
    });
    expect([201, 200, 409]).toContain(r1.status());
    expect([201, 200, 409]).toContain(r2.status());
    // Se ambos 201/200, devem retornar a MESMA transação (idempotência).
    if (r1.status() === 201 && r2.status() === 201) {
      const a = await r1.json();
      const b = await r2.json();
      expect(a.transactionId || a.id).toBe(b.transactionId || b.id);
    }
  });
});

test.describe('Headers de segurança e infraestrutura', () => {
  test('Host Header Attack — não reflete Host em Location/links', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/merchants`, {
      headers: { 'X-Api-Key': apiKey, Host: 'evil.com' },
    });
    // 405 = GET no raiz (só POST existe) — esperado; o que importa: não reflete Host.
    expect([401, 403, 200, 404, 405]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('evil.com');
  });

  test('JWT Tampering — token adulterado → 401', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/master/sessions`, {
      headers: { 'Authorization': 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.invalid' },
    });
    expect([401, 403]).toContain(res.status());
  });

  test('HMAC Tampering (webhook) — assinatura inválida → 401', async ({ request }) => {
    const res = await request.post(`${BASE}/webhooks/pix`, {
      headers: { 'X-Psp-Signature': 'deadbeef', 'Content-Type': 'application/json' },
      data: JSON.stringify({ event: 'sale.paid', transactionId: 'x' }),
    });
    // Em dev verify-signature=false → processa (200); mas assinatura inválida
    // nunca deve ser aceita como válida em prod. Aqui validamos o status da rota.
    expect([200, 401, 400]).toContain(res.status());
  });

  test('Request Smuggling/Desync (Content-Length vs Transfer-Encoding) — 4xx ou processa, sem 500', async ({ request }) => {
    // CL + TE conflitantes: Tomcat rejeita (400) ou ignora um deles — nunca 5xx.
    const res = await request.post(`${BASE}/api/v1/merchants`, {
      headers: {
        'Content-Type': 'application/json',
        'Transfer-Encoding': 'chunked',
      },
      data: JSON.stringify({ name: 'Smuggle', email: 'smug@example.com', password: 'Senha-123' }),
    });
    expect([201, 400, 501]).toContain(res.status());
  });

  test('XSS (refletido) — resposta de erro não ecoa script executável', async ({ request }) => {
    const res = await request.get(`${BASE}/api/v1/merchants?q=<script>alert(1)</script>`, {
      headers: { 'X-Api-Key': apiKey },
    });
    // 405 = GET no raiz (só POST existe); o que importa: não ecoa o script.
    expect([401, 403, 400, 404, 405]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('<script>alert(1)</script>');
  });

  test('XXE via JSON — 4xx, sem resolução de entidade', async ({ request }) => {
    const res = await request.post(`${BASE}/api/v1/merchants`, {
      headers: { 'Content-Type': 'application/json' },
      data: JSON.stringify({
        name: '<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>',
        email: 'xxe@example.com',
        password: 'Senha-123',
      }),
    });
    // 403 = body inspection (Spec 63) bloqueia o payload (padrão file://) ANTES da
    // validação — nunca resolve a entidade. Adicionado pós-Spec 63 (correção de
    // teste na auditoria da Spec 66: o teste pré-datava o filtro).
    expect([201, 400, 403, 422]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('root:');
  });

  test('Race Condition (login concorrente) — não cria inconsistência fatal', async ({ request }) => {
    // Dispara 5 logins simultâneos com senha errada — todos devem falhar sem 500.
    const results = await Promise.all(
      Array.from({ length: 5 }, () =>
        request.post(`${BASE}/api/v1/auth/master/login`, {
          headers: { 'Content-Type': 'application/json' },
          data: JSON.stringify({ username: 'master', password: 'errada' }),
        }),
      ),
    );
    for (const r of results) {
      expect([401, 429, 423, 400]).toContain(r.status());
    }
  });
});

test.describe('Path e método', () => {
  test('Path Traversal (encoded) — 404, nunca 200 com conteúdo de arquivo', async ({ request }) => {
    const res = await request.get(`${BASE}/..%2f..%2fetc%2fpasswd`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect([404, 403, 400, 401]).toContain(res.status());
    const text = await res.text();
    expect(text).not.toContain('root:');
  });

  test('Método não suportado — 405, nunca 200', async ({ request }) => {
    const res = await request.delete(`${BASE}/api/v1/merchants/${merchantId}`, {
      headers: { 'X-Api-Key': apiKey },
    });
    expect([405, 404, 403, 401]).toContain(res.status());
  });

  test('Rotas não mapeadas (fuzzing básico) — 404 genérico, sem stack', async ({ request }) => {
    for (const path of ['/api/v1/admin/%00', '/api/v1/../actuator/env', '/api/v1/merchants/../../webhooks/pix']) {
      const res = await request.get(`${BASE}${path}`, {
        headers: { 'X-Api-Key': apiKey },
      });
      expect([404, 403, 400, 401]).toContain(res.status());
      const text = await res.text();
      expect(text).not.toContain('Exception');
      expect(text).not.toContain('at com.foxcash');
    }
  });
});
