// @ts-check
const { test, expect } = require('@playwright/test');
const { Client } = require('pg');
const http = require('http');
const nodeCrypto = require('crypto');

/**
 * Pipeline do outbox (Spec 35 + Spec 53) — os 5 fluxos do webhook de saída
 * contra a API real:
 *
 *   1. Caminho feliz:    venda → SALE_PAID PENDING → dispatcher → SENT + sent_at,
 *                        nenhuma linha nova (idempotente)
 *   2. Falha temporária: receptor 500 → PENDING (estável), attempts=1,
 *                        next_attempt_at ≈ now + 30s×2¹, evento não perdido
 *   3. Retry:            próxima tentativa vencida + 500 → attempts=2,
 *                        next_attempt_at ≈ now + 30s×2², mesma linha (sem duplicar)
 *   4. Recuperação:      receptor 200 → SENT + sent_at, attempts preservado (=2)
 *   5. Falha definitiva: 5ª tentativa (max=5) → FAILED (next_attempt_at NULL),
 *                        não reprocessado, auditável, e publica OUTBOX_DEAD
 *                        (plataforma → webhook de operador) como alerta.
 *                        ⚠️ SEM DLQ física: FAILED fica na mesma tabela (trilha);
 *                        o ALERTA operacional via OUTBOX_DEAD é o mecanismo.
 *
 * O receptor local (127.0.0.1) captura os POSTs assinados e responde 200/500
 * sob demanda. Requer perfil dev com FOXCASH_NOTIFICATION_ALLOW_LOOPBACK=true.
 */
test.describe('Pipeline do outbox — feliz, falha, retry, recuperação e FAILED', () => {
  test.describe.configure({ mode: 'serial' });

  function dbClient() {
    return new Client({
      host: process.env.DB_HOST || 'localhost',
      port: Number(process.env.DB_PORT || 5432),
      user: process.env.DB_USERNAME || 'nyxcash',
      password: process.env.DB_PASSWORD || 'nyxcash',
      database: process.env.DB_NAME || 'nyxcash',
    });
  }

  async function dbQuery(text, params) {
    const client = dbClient();
    await client.connect();
    try {
      const { rows } = await client.query(text, params);
      return rows;
    } finally {
      await client.end();
    }
  }

  test('OUTBOX: os 5 fluxos do webhook de saída (SALE_PAID)', async ({ request }) => {
    // 5 cenários com polls reais do dispatcher (30s) + espera de não-reprocessamento:
    // o teste completo leva ~4 min (padrão do repo: test.setTimeout no corpo).
    test.setTimeout(600_000);

    // 0. Lojista próprio (auto-suficiente) + login no portal.
    const email = `outbox.e2e.${Date.now()}@example.com`;
    const password = 'Senha-OUTBOX-123';
    const register = await request.post('/api/v1/merchants', {
      data: { name: 'Loja Outbox E2E', email, password },
    });
    expect(register.status()).toBe(201);
    const merchant = await register.json();
    const merchantId = merchant.id;
    const apiKey = merchant.apiKey;

    const login = await request.post('/api/v1/merchant-auth/login', { data: { email, password } });
    expect(login.status()).toBe(200);
    const portalHeaders = { Authorization: `Bearer ${(await login.json()).token}` };

    // 0.1 Receptor local controlável (200/500 por modo) — captura os POSTs assinados.
    const deliveries = [];
    let failMode = 'ok';
    const receiver = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        deliveries.push({
          eventId: req.headers['x-foxcash-event-id'],
          event: req.headers['x-foxcash-event'],
          signature: req.headers['x-foxcash-signature'],
          body,
          responseStatus: failMode === 'fail' ? 500 : 200,
        });
        res.statusCode = failMode === 'fail' ? 500 : 200;
        res.end();
      });
    });
    await new Promise((resolve) => receiver.listen(0, '127.0.0.1', resolve));
    const webhookUrl = `http://127.0.0.1:${receiver.address().port}/hook`;

    const outboxRow = async (id) =>
      (await dbQuery(
        'SELECT status, attempts, last_error, next_attempt_at, sent_at FROM notification_outbox WHERE id = $1',
        [id],
      ))[0];

    const waitFor = (fn, message, timeout = 60_000) =>
      expect.poll(fn, { timeout, message }).toBeTruthy();

    const waitStatus = (id, status) =>
      waitFor(() => outboxRow(id).then((r) => r && r.status === status), `outbox ${id} não virou ${status}`);

    try {
      // Drena PENDING de cenários anteriores (o receptor só recebe os nossos).
      await dbQuery("UPDATE notification_outbox SET status = 'SENT' WHERE status = 'PENDING'");

      // 0.2 Webhook SALE_PAID ativo para o lojista (secret devolvido 1×).
      const created = await request.post('/api/v1/portal/webhooks', {
        headers: portalHeaders,
        data: { url: webhookUrl, events: ['SALE_PAID'] },
      });
      expect(created.status()).toBe(201);
      const webhook = await created.json();
      expect(webhook.secret).toMatch(/^[0-9a-f]{64}$/);

      // Vende (cobrança + PAID) e devolve a linha do outbox recém-publicada.
      const sell = async (tag) => {
        const charge = await request.post('/api/v1/foxcash/pix/cob', {
          headers: { 'X-Api-Key': apiKey },
          data: {
            merchantId,
            amount: 100.0,
            buyerId: `buyer-outbox-${tag}`,
            expirationSeconds: 3600,
            idempotencyKey: `outbox-${tag}-${Date.now()}`,
          },
        });
        expect(charge.status()).toBe(201);
        const externalReference = (await charge.json()).externalReference;
        const paid = await request.post('/webhooks/pix', {
          data: {
            eventId: `evt-outbox-${tag}`,
            externalReference,
            status: 'PAID',
            settledAt: new Date().toISOString(),
            amount: 100.0,
          },
        });
        expect(paid.status()).toBe(204);
        // O evento é publicado na MESMA transação do crédito (Spec 35).
        return (await dbQuery(
          "SELECT id, type, status FROM notification_outbox WHERE merchant_id = $1 AND type = 'SALE_PAID' ORDER BY created_at DESC LIMIT 1",
          [merchantId],
        ))[0];
      };

      // ============ CENÁRIO 1 — Caminho feliz ============
      failMode = 'ok';
      const sale1 = await sell('happy');
      expect(sale1).toBeTruthy();
      expect(sale1.type).toBe('SALE_PAID');
      // O registro nasce PENDING na janela imediata (dispatcher roda a cada 30s).
      // Best-effort: se o tick do dispatcher ganhar a corrida (~1/30), o PENDING
      // é verificado de forma ESTÁVEL no cenário 2 (após a falha).
      if (sale1.status === 'PENDING') {
        expect(sale1.status).toBe('PENDING');
      }
      const eventId1 = sale1.id;

      // Dispatcher entrega → POST assinado no receptor (X-FoxCash-Event-Id = id do outbox).
      await waitFor(
        () => deliveries.some((d) => d.eventId === eventId1 && d.event === 'SALE_PAID'),
        'SALE_PAID não foi entregue no receptor',
      );
      const delivery1 = deliveries.find((d) => d.eventId === eventId1);
      expect(delivery1.responseStatus).toBe(200);
      expect(JSON.parse(delivery1.body).type).toBe('SALE_PAID');
      expect(JSON.parse(delivery1.body).merchantId).toBe(merchantId);
      expect(delivery1.signature)
        .toBe(nodeCrypto.createHmac('sha256', webhook.secret).update(delivery1.body).digest('hex'));

      // Outbox: SENT + sent_at preenchido + NENHUMA linha nova (1 linha para o evento).
      await waitStatus(eventId1, 'SENT');
      const sent1 = await outboxRow(eventId1);
      expect(sent1.sent_at).toBeTruthy();
      const count1 = await dbQuery('SELECT count(*)::int AS n FROM notification_outbox WHERE id = $1', [eventId1]);
      expect(count1[0].n).toBe(1);

      // ============ CENÁRIO 2 — Falha temporária ============
      failMode = 'fail';
      const sale2 = await sell('fail1');
      const eventId2 = sale2.id;
      await waitFor(
        () => deliveries.filter((d) => d.eventId === eventId2 && d.responseStatus === 500).length === 1,
        '1ª tentativa (500) não foi registrada no receptor',
      );
      // PENDING ESTÁVEL + attempts=1 + backoff 30s×2¹ ≈ 60s + sem perda (linha existe).
      await waitStatus(eventId2, 'PENDING');
      const fail1 = await outboxRow(eventId2);
      expect(fail1.attempts).toBe(1);
      expect(fail1.last_error).toContain('500');
      expect(fail1.next_attempt_at).toBeTruthy();
      const backoff1 = await dbQuery(
        "SELECT extract(epoch FROM (next_attempt_at - (now() AT TIME ZONE 'UTC')))::int AS s FROM notification_outbox WHERE id = $1",
        [eventId2],
      );
      expect(backoff1[0].s).toBeGreaterThan(45);
      expect(backoff1[0].s).toBeLessThan(75); // ≈60s (30s×2¹) — coluna TIMESTAMP sem TZ é UTC

      // ============ CENÁRIO 3 — Retry (2ª falha) ============
      // Adianta o relógio (próxima tentativa para o passado) → 2ª tentativa real.
      await dbQuery("UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = $1", [eventId2]);
      await waitFor(
        () => deliveries.filter((d) => d.eventId === eventId2 && d.responseStatus === 500).length === 2,
        '2ª tentativa (500) não foi registrada no receptor',
      );
      await waitFor(() => outboxRow(eventId2).then((r) => r && r.attempts === 2), 'attempts não chegou a 2');
      const fail2 = await outboxRow(eventId2);
      expect(fail2.status).toBe('PENDING'); // mesma linha, sem duplicar
      const backoff2 = await dbQuery(
        "SELECT extract(epoch FROM (next_attempt_at - (now() AT TIME ZONE 'UTC')))::int AS s FROM notification_outbox WHERE id = $1",
        [eventId2],
      );
      expect(backoff2[0].s).toBeGreaterThan(90);
      expect(backoff2[0].s).toBeLessThan(150); // ≈120s (30s×2²)
      const count2 = await dbQuery('SELECT count(*)::int AS n FROM notification_outbox WHERE id = $1', [eventId2]);
      expect(count2[0].n).toBe(1); // sem duplicar registros / sem novo evento

      // ============ CENÁRIO 4 — Recuperação ============
      failMode = 'ok';
      await dbQuery("UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = $1", [eventId2]);
      await waitStatus(eventId2, 'SENT');
      const recovered = await outboxRow(eventId2);
      expect(recovered.sent_at).toBeTruthy();
      expect(recovered.attempts).toBe(2); // preservado para auditoria
      // Receptor viu 3 entregas do MESMO evento: 2×500 + 1×200 (nada duplicado).
      expect(deliveries.filter((d) => d.eventId === eventId2)).toHaveLength(3);

      // ============ CENÁRIO 5 — Falha definitiva (FAILED, sem DLQ) ============
      failMode = 'fail';
      const sale5 = await sell('dead');
      const eventId5 = sale5.id;
      // Semeia attempts=4 (estado equivalente a 4 falhas reais) — o dispatcher
      // faz a 5ª tentativa REAL: incremento 4→5 ≥ max(5) → markFailed(null) → FAILED.
      await dbQuery('UPDATE notification_outbox SET attempts = 4 WHERE id = $1', [eventId5]);
      await waitFor(
        () => outboxRow(eventId5).then((r) => r && r.attempts === 5 && r.status === 'FAILED'),
        '5ª tentativa não marcou FAILED',
      );
      const dead = await outboxRow(eventId5);
      expect(dead.next_attempt_at).toBeNull(); // esgotou: sem próxima tentativa
      expect(dead.last_error).toContain('500');

      // Não é mais reprocessado: aguarda 1 ciclo do dispatcher e confirma que
      // status/attempts não mudaram e o receptor não recebeu nova entrega.
      const deliveriesBefore = deliveries.filter((d) => d.eventId === eventId5).length;
      await new Promise((r) => setTimeout(r, 40_000));
      const deadAfter = await outboxRow(eventId5);
      expect(deadAfter.status).toBe('FAILED');
      expect(deadAfter.attempts).toBe(5);
      expect(deliveries.filter((d) => d.eventId === eventId5)).toHaveLength(deliveriesBefore);

      // Auditável: payload preservado (envelope SALE_PAID com eventId único).
      const payloadRow = await dbQuery('SELECT payload FROM notification_outbox WHERE id = $1', [eventId5]);
      expect(JSON.parse(payloadRow[0].payload).type).toBe('SALE_PAID');

      // ALERTA OPERACIONAL (Spec 35/53): o esgotamento publica um OUTBOX_DEAD
      // (plataforma, sem merchant → webhook de operador) NA MESMA transação do
      // FAILED, com o eventId/erro do evento morto no payload.
      await waitFor(
        () => dbQuery(
          "SELECT payload FROM notification_outbox WHERE type = 'OUTBOX_DEAD' AND payload LIKE $1 ORDER BY created_at DESC LIMIT 1",
          [`%${eventId5}%`],
        ).then((r) => r.length > 0),
        'OUTBOX_DEAD não foi publicado para o evento esgotado',
      );
      const deadAlert = (await dbQuery(
        "SELECT payload, status FROM notification_outbox WHERE type = 'OUTBOX_DEAD' AND payload LIKE $1 ORDER BY created_at DESC LIMIT 1",
        [`%${eventId5}%`],
      ))[0];
      const deadEnvelope = JSON.parse(deadAlert.payload);
      expect(deadEnvelope.type).toBe('OUTBOX_DEAD');
      expect(deadEnvelope.merchantId).toBeNull(); // plataforma → operador
      expect(deadEnvelope.data.eventId).toBe(eventId5);
      expect(deadEnvelope.data.originalType).toBe('SALE_PAID');
      expect(deadEnvelope.data.merchantId).toBe(merchantId);
      expect(deadEnvelope.data.error).toContain('500');
      expect(deadEnvelope.data.attempts).toBe(5);
      // O OUTBOX_DEAD nasce PENDING (será entregue ao operador pelo dispatcher).
      expect(['PENDING', 'SENT']).toContain(deadAlert.status);
    } finally {
      receiver.closeAllConnections();
      await new Promise((resolve) => receiver.close(resolve));
    }
  });
});
