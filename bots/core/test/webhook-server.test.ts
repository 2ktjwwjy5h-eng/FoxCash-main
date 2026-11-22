import { describe, expect, it, vi } from 'vitest';
import { WebhookServer } from '../src/webhook-server';
import { FoxCashClient } from '../src/foxcash-client';
import { DeliveryActions } from '../src/delivery-actions';
import { sign } from '../src/signature';

/** fetch fake controlado — devolve respostas em fila (ou por matcher de URL). */
function fakeFetch(
  handler: (url: string, init?: RequestInit) => Promise<Response>,
): typeof fetch {
  return (async (url: string | URL | Request, init?: RequestInit) =>
    handler(String(url), init)) as typeof fetch;
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Sobe o servidor numa porta efêmera e devolve {url, close}. */
async function startServer(server: WebhookServer): Promise<{ url: string; close: () => void }> {
  const http = server.app.listen(0);
  await new Promise<void>((resolve) => http.once('listening', resolve));
  const port = (http.address() as { port: number }).port;
  return {
    url: `http://localhost:${port}/webhooks/foxcash`,
    close: () => http.close(),
  };
}

describe('WebhookServer — SALE_PAID de cobrança avulsa (Spec 19/35)', () => {
  it('404 (sem delivery) → no-op silencioso 204, sem processar entrega', async () => {
    // client fake: deliveryByTransaction sempre 404 (cobrança avulsa não tem delivery)
    const fetchImpl = fakeFetch(async () =>
      jsonResponse(404, { code: 'delivery_not_found' }),
    );
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl);

    // deliveryActions fake — nunca deve ser chamado
    const processDelivery = vi.fn();
    const enqueueRetry = vi.fn();
    const deliveryActions = {
      processDelivery,
      enqueueRetry,
    } as unknown as DeliveryActions;

    const server = new WebhookServer({
      secret: 'segredo-teste',
      client,
      deliveryActions,
    });
    const { url, close } = await startServer(server);

    const body = JSON.stringify({
      eventId: 'evt-avulsa-1',
      type: 'SALE_PAID',
      merchantId: 'm1',
      createdAt: new Date().toISOString(),
      data: { transactionId: 'tx-avulsa-1', amount: 25, paidAt: null },
    });

    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-FoxCash-Signature': sign(body, 'segredo-teste'),
        'X-FoxCash-Event-Id': 'evt-avulsa-1',
        'X-FoxCash-Event': 'SALE_PAID',
      },
      body,
    });

    expect(res.status).toBe(204);
    expect(processDelivery).not.toHaveBeenCalled();
    expect(enqueueRetry).not.toHaveBeenCalled();
    close();
  });

  it('SALE_PAID com delivery existente → processa a entrega', async () => {
    const fetchImpl = fakeFetch(async () =>
      jsonResponse(200, {
        id: 'd1',
        planId: 'p1',
        transactionId: 'tx-plano-1',
        platform: 'TELEGRAM',
        platformUserId: 'u1',
        buyerIdHash: 'hash',
        status: 'PENDING',
        createdAt: new Date().toISOString(),
        deliveredAt: null,
        actions: [
          {
            seq: 1,
            actionType: 'SEND_MESSAGE',
            payload: { text: 'Produto' },
            status: 'PENDING',
            note: null,
            executedAt: null,
          },
        ],
        accessLogs: [],
      }),
    );
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl);

    const processDelivery = vi.fn().mockResolvedValue({ failures: 0 });
    const enqueueRetry = vi.fn();
    const deliveryActions = {
      processDelivery,
      enqueueRetry,
    } as unknown as DeliveryActions;

    const server = new WebhookServer({
      secret: 'segredo-teste',
      client,
      deliveryActions,
    });
    const { url, close } = await startServer(server);

    const body = JSON.stringify({
      eventId: 'evt-plano-1',
      type: 'SALE_PAID',
      merchantId: 'm1',
      createdAt: new Date().toISOString(),
      data: { transactionId: 'tx-plano-1', amount: 10, paidAt: null },
    });

    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-FoxCash-Signature': sign(body, 'segredo-teste'),
        'X-FoxCash-Event-Id': 'evt-plano-1',
        'X-FoxCash-Event': 'SALE_PAID',
      },
      body,
    });

    expect(res.status).toBe(204);
    expect(processDelivery).toHaveBeenCalledTimes(1);
    expect(enqueueRetry).not.toHaveBeenCalled();
    close();
  });
});
