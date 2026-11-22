import { describe, expect, it } from 'vitest';
import { FoxCashClient } from '../src/foxcash-client';
import { FoxCashGatewayClient } from '../src/gateway-client';
import type { PurchaseResponse } from '../src/types';

const PURCHASE: PurchaseResponse = {
  transactionId: 'tx-1',
  planId: 'plan-1',
  externalReference: 'ext-1',
  pixQrCode: 'aGVsbG8=',
  pixCopyPaste: '000201...',
  status: 'PENDING',
  createdAt: '2026-08-06T00:00:00Z',
};

/** Monta um client fake que captura os bodies enviados ao purchase. */
function setup(options: { botId?: string } = {}) {
  const calls: Array<Record<string, unknown>> = [];
  const fetchImpl = (async (_url: string, init?: RequestInit) => {
    calls.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
    return new Response(JSON.stringify(PURCHASE), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
  const fox = new FoxCashClient('http://x', 'key', fetchImpl);
  const gateway = new FoxCashGatewayClient({ client: fox, botId: options.botId });
  return { gateway, calls };
}

describe('FoxCashGatewayClient (Spec 150/D7)', () => {
  it('repassa botId + termsAccepted + provas no payload FoxCash', async () => {
    const { gateway, calls } = setup({ botId: 'bot-9' });

    const result = await gateway.createCharge({
      planId: 'plan-1',
      platform: 'DISCORD',
      platformUserId: 'u1',
      buyerId: 'buyer-1',
      idempotencyKey: 'k1',
      termsAccepted: true,
      buyerIp: '200.1.2.3',
      buyerDevice: 'telegram',
    });

    expect(result.transactionId).toBe('tx-1');
    expect(calls[0]).toMatchObject({
      botId: 'bot-9',
      termsAccepted: true,
      buyerIp: '200.1.2.3',
      buyerDevice: 'telegram',
    });
  });

  it('sem botId configurado → não envia o campo (compatibilidade dev)', async () => {
    const { gateway, calls } = setup();

    await gateway.createCharge({
      planId: 'plan-1',
      platform: 'TELEGRAM',
      platformUserId: 'u2',
      idempotencyKey: 'k2',
    });

    expect(calls[0].botId).toBeUndefined();
  });
});
