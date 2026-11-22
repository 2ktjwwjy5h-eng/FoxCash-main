import { describe, expect, it } from 'vitest';
import { hashBuyerId, SalesMachine } from '../src/sales';
import { FoxCashClient } from '../src/foxcash-client';
import { FoxCashGatewayClient } from '../src/gateway-client';
import { StorefrontCache } from '../src/storefront';
import type { PurchaseResponse, Storefront } from '../src/types';

const PLAN_ID = 'plan-1';

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
  plans: [
    {
      id: PLAN_ID,
      name: 'Plano VIP',
      description: null,
      price: 19.9,
      imageUrl: null,
      embedColor: null,
      featured: false,
    },
  ],
  messages: {},
};

const PURCHASE: PurchaseResponse = {
  transactionId: 'tx-1',
  planId: PLAN_ID,
  externalReference: 'ext-1',
  pixQrCode: 'aGVsbG8=', // "hello" em base64
  pixCopyPaste: '000201...',
  status: 'PENDING',
  createdAt: '2026-08-06T00:00:00Z',
};

describe('hashBuyerId (Spec 19/52)', () => {
  it('é sha256 hex de platform:userId (nunca o ID em claro)', () => {
    const h = hashBuyerId('DISCORD', 'user-123');
    expect(h).toMatch(/^[0-9a-f]{64}$/);
    expect(h).not.toContain('user-123');
  });

  it('é determinístico e difere por plataforma', () => {
    expect(hashBuyerId('DISCORD', 'u1')).toBe(hashBuyerId('DISCORD', 'u1'));
    expect(hashBuyerId('DISCORD', 'u1')).not.toBe(hashBuyerId('TELEGRAM', 'u1'));
  });
});

describe('SalesMachine (Spec 52)', () => {
  /** Monta um SalesMachine com fetch fake e captura os bodies enviados. */
  function setup(options: { botId?: string } = {}) {
    const calls: Array<{ body: string }> = [];
    const fetchImpl = (async (url: string, init?: RequestInit) => {
      if (init?.body !== undefined) {
        calls.push({ body: String(init.body) });
      }
      return new Response(
        JSON.stringify(url.includes('/storefront') ? STOREFRONT : PURCHASE),
        {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        },
      );
    }) as typeof fetch;
    const fox = new FoxCashClient('http://x', 'key', fetchImpl);
    const gateway = new FoxCashGatewayClient({ client: fox, botId: options.botId });
    const sales = new SalesMachine(gateway, new StorefrontCache(fox), () => 1_720_000_000_000);
    return { sales, calls };
  }

  it('abre a cobrança com buyerId e idempotencyKey default', async () => {
    const { sales, calls } = setup();

    const { plan, purchase } = await sales.openSale({
      planId: PLAN_ID,
      platform: 'DISCORD',
      platformUserId: 'user-123',
    });

    expect(plan.name).toBe('Plano VIP');
    expect(purchase.transactionId).toBe('tx-1');
    const body = JSON.parse(calls[0].body) as {
      platform: string;
      platformUserId: string;
      buyerId: string;
      idempotencyKey: string;
    };
    expect(body.platform).toBe('DISCORD');
    expect(body.platformUserId).toBe('user-123');
    expect(body.buyerId).toBe(hashBuyerId('DISCORD', 'user-123'));
    expect(body.idempotencyKey).toBe(`compra:user-123:${1_720_000_000_000}`);
  });

  it('aceita buyerId e idempotencyKey explícitos', async () => {
    const { sales } = setup();

    const purchase = await sales.openSale({
      planId: PLAN_ID,
      platform: 'TELEGRAM',
      platformUserId: 'u9',
      buyerId: 'buyer-explicito',
      idempotencyKey: 'minha-chave',
    });
    expect(purchase.purchase.transactionId).toBe('tx-1');
  });

  it('envia botId (Spec 150) quando configurado no adapter', async () => {
    const { sales, calls } = setup({ botId: 'bot-123' });

    await sales.openSale({
      planId: PLAN_ID,
      platform: 'TELEGRAM',
      platformUserId: 'u9',
      termsAccepted: true,
    });

    const body = JSON.parse(calls[0].body) as { botId?: string; termsAccepted?: boolean };
    expect(body.botId).toBe('bot-123');
    expect(body.termsAccepted).toBe(true);
  });

  it('envia termsAccepted apenas quando o canal confirma (Spec 150/D3)', async () => {
    const { sales, calls } = setup({ botId: 'bot-123' });

    // sem confirmação → termsAccepted ausente
    await sales.openSale({ planId: PLAN_ID, platform: 'DISCORD', platformUserId: 'u1' });
    const bodySemAceite = JSON.parse(calls[0].body) as { termsAccepted?: boolean };
    expect(bodySemAceite.termsAccepted).toBeUndefined();

    // com confirmação → termsAccepted: true
    await sales.openSale({
      planId: PLAN_ID,
      platform: 'DISCORD',
      platformUserId: 'u1',
      termsAccepted: true,
    });
    const bodyComAceite = JSON.parse(calls[1].body) as { termsAccepted?: boolean };
    expect(bodyComAceite.termsAccepted).toBe(true);
  });

  it('lança se o plano não está no storefront (inativo/removido)', async () => {
    const { sales } = setup();
    await expect(
      sales.openSale({
        planId: 'plano-removido',
        platform: 'DISCORD',
        platformUserId: 'u1',
      }),
    ).rejects.toThrow('plano inexistente');
  });
});
