import { describe, expect, it, vi } from 'vitest';
import { StorefrontCache } from '../src/storefront';
import { FoxCashClient } from '../src/foxcash-client';
import type { Storefront } from '../src/types';

const STOREFRONT: Storefront = {
  store: {
    id: 's1',
    name: 'Loja',
    logoUrl: null,
    bannerUrl: null,
    description: null,
    socialLinks: [],
    color: null,
    emoji: null,
    title: null,
    footer: null,
  },
  buttons: [],
  plans: [],
  messages: {},
};

function clientWith(storefront: Storefront, calls: { count: () => number }) {
  return new FoxCashClient('http://x', 'key', (async () => {
    calls.count();
    return new Response(JSON.stringify(storefront), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch);
}

describe('StorefrontCache (Spec 52)', () => {
  it('cacheia por 30s (1 fetch em 2 gets)', async () => {
    const calls = { count: vi.fn() };
    const cache = new StorefrontCache(clientWith(STOREFRONT, calls), 30_000);
    const a = await cache.get();
    const b = await cache.get();
    expect(a).toBe(b);
    expect(calls.count).toHaveBeenCalledTimes(1);
  });

  it('expira após o TTL (refetch)', async () => {
    const calls = { count: vi.fn() };
    const now = vi.fn(() => 1_000_000);
    const cache = new StorefrontCache(clientWith(STOREFRONT, calls), 30_000, now);
    await cache.get();
    await cache.get(); // cache válido
    expect(calls.count).toHaveBeenCalledTimes(1);
    now.mockReturnValue(1_000_000 + 31_000);
    await cache.get();
    expect(calls.count).toHaveBeenCalledTimes(2);
  });

  it('invalidate força refetch', async () => {
    const calls = { count: vi.fn() };
    const cache = new StorefrontCache(clientWith(STOREFRONT, calls), 30_000);
    await cache.get();
    cache.invalidate();
    await cache.get();
    expect(calls.count).toHaveBeenCalledTimes(2);
  });
});
