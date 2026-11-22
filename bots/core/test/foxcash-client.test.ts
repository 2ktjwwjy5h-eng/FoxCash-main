import { describe, expect, it } from 'vitest';
import { FoxCashApiError, FoxCashClient } from '../src/foxcash-client';
import type { Storefront } from '../src/types';

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

const STOREFRONT: Storefront = {
  store: {
    id: 's1',
    name: 'Loja Teste',
    logoUrl: null,
    bannerUrl: null,
    description: null,
    socialLinks: [],
    color: '#ff0000',
    emoji: null,
    title: null,
    footer: null,
  },
  buttons: [],
  plans: [],
  messages: {},
};

describe('FoxCashClient (Spec 52)', () => {
  it('getStorefront envia X-Api-Key e devolve o JSON', async () => {
    let authHeader: string | null = null;
    const fetchImpl = fakeFetch(async (url, init) => {
      authHeader = (init?.headers as Record<string, string> | undefined)?.['X-Api-Key'] ?? null;
      expect(url).toBe('http://localhost:8080/api/v1/foxcash/storefront');
      return jsonResponse(200, STOREFRONT);
    });
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl);
    const sf = await client.getStorefront();
    expect(sf.store.name).toBe('Loja Teste');
    expect(authHeader).toBe('mch_abc');
  });

  it('erros viram FoxCashApiError com status + code de domínio', async () => {
    const fetchImpl = fakeFetch(async () => jsonResponse(409, { code: 'plan_inactive' }));
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl);
    const err = await client
      .purchase('p1', {
        platform: 'DISCORD',
        platformUserId: 'u1',
        idempotencyKey: 'k1',
      })
      .then(() => null)
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(FoxCashApiError);
    const apiErr = err as FoxCashApiError;
    expect(apiErr.status).toBe(409);
    expect(apiErr.code).toBe('plan_inactive');
    expect(apiErr.isConflict).toBe(true);
  });

  it('429 expõe isRateLimited', async () => {
    const fetchImpl = fakeFetch(async () => jsonResponse(429, { code: 'too_many_requests' }));
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl);
    const err = (await client.pendingDeliveries().catch((e: unknown) => e)) as FoxCashApiError;
    expect(err.isRateLimited).toBe(true);
  });

  it('404 expõe isNotFound', async () => {
    const fetchImpl = fakeFetch(async () => jsonResponse(404, { code: 'delivery_not_found' }));
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl);
    const err = (await client.deliveryByTransaction('tx1').catch((e: unknown) => e)) as FoxCashApiError;
    expect(err.isNotFound).toBe(true);
  });

  it('204 devolve undefined sem parse de JSON', async () => {
    const fetchImpl = fakeFetch(async () => new Response(null, { status: 204 }));
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl);
    const result = await client.completeAction('d1', 0, 'DONE');
    expect(result).toBeUndefined();
  });

  it('corpo não-JSON em erro → code unknown', async () => {
    const fetchImpl = fakeFetch(async () => new Response('upstream exploded', { status: 502 }));
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl);
    const err = (await client.pendingDeliveries().catch((e: unknown) => e)) as FoxCashApiError;
    expect(err.status).toBe(502);
    expect(err.code).toBe('unknown');
  });

  it('429 re-tenta com backoff exponencial e sucede (design §4)', async () => {
    const calls: number[] = [];
    const sleeps: number[] = [];
    const fetchImpl = fakeFetch(async () => {
      calls.push(calls.length);
      // 429 duas vezes, depois 200
      return calls.length <= 2
        ? jsonResponse(429, { code: 'too_many_requests' })
        : jsonResponse(200, STOREFRONT);
    });
    const sleep = async (ms: number) => {
      sleeps.push(ms);
    };
    const client = new FoxCashClient(
      'http://localhost:8080',
      'mch_abc',
      fetchImpl,
      2, // retries
      500, // base delay
      sleep,
    );
    const sf = await client.getStorefront();
    expect(sf.store.name).toBe('Loja Teste');
    expect(calls).toHaveLength(3); // 1 original + 2 retries
    expect(sleeps).toEqual([500, 1000]); // 500ms → 1000ms
  });

  it('429 esgotado lança FoxCashApiError (não loop infinito)', async () => {
    const fetchImpl = fakeFetch(async () => jsonResponse(429, { code: 'too_many_requests' }));
    const client = new FoxCashClient('http://localhost:8080', 'mch_abc', fetchImpl, 2, 10, async () => {});
    const err = (await client.pendingDeliveries().catch((e: unknown) => e)) as FoxCashApiError;
    expect(err.status).toBe(429);
    expect(err.isRateLimited).toBe(true);
  });
});
