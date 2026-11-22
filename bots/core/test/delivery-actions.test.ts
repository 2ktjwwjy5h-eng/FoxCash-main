import { describe, expect, it, vi } from 'vitest';
import { DeliveryActions, type DeliveryActionExecutor } from '../src/delivery-actions';
import { FoxCashClient } from '../src/foxcash-client';
import type { Delivery, DeliveryAction } from '../src/types';

function makeDelivery(actions: Partial<DeliveryAction>[] = []): Delivery {
  return {
    id: 'd1',
    planId: 'p1',
    transactionId: 'tx1',
    platform: 'DISCORD',
    platformUserId: 'u1',
    buyerIdHash: null,
    status: 'PENDING',
    createdAt: '2026-08-06T00:00:00Z',
    deliveredAt: null,
    actions: actions.map((a, i) => ({
      seq: i,
      actionType: 'SEND_MESSAGE',
      payload: { text: 'oi' },
      status: 'PENDING',
      note: null,
      executedAt: null,
      ...a,
    })),
  };
}

/** Client fake — registra completeAction e retryDelivery. */
function fakeClient() {
  const completeCalls: Array<{ deliveryId: string; seq: number; status: string; note?: string }> =
    [];
  const retryCalls: string[] = [];
  const client = {
    completeAction: async (
      deliveryId: string,
      seq: number,
      status: 'DONE' | 'FAILED',
      note?: string,
    ) => {
      completeCalls.push({ deliveryId, seq, status, note });
    },
    retryDelivery: async (deliveryId: string) => {
      retryCalls.push(deliveryId);
      return makeDelivery(); // volta PENDING
    },
  } as unknown as FoxCashClient;
  return { client, completeCalls, retryCalls };
}

/** Executor fake — resultado configurável por seq. */
function executorWith(results: Record<number, { ok: boolean; note?: string }>): DeliveryActionExecutor {
  return {
    execute: async (_d: Delivery, a: DeliveryAction) =>
      results[a.seq] ?? { ok: true },
  };
}

describe('DeliveryActions (Spec 49/52)', () => {
  it('executa cada ação PENDING e confirma DONE', async () => {
    const { client, completeCalls } = fakeClient();
    const actions = new DeliveryActions(client, executorWith({}));
    const delivery = makeDelivery([{}, {}, {}]);
    const { failures } = await actions.processDelivery(delivery);
    expect(failures).toBe(0);
    expect(completeCalls).toEqual([
      { deliveryId: 'd1', seq: 0, status: 'DONE' },
      { deliveryId: 'd1', seq: 1, status: 'DONE' },
      { deliveryId: 'd1', seq: 2, status: 'DONE' },
    ]);
  });

  it('não re-executa ações já DONE (idempotente no gateway)', async () => {
    const { client, completeCalls } = fakeClient();
    const actions = new DeliveryActions(client, executorWith({}));
    const delivery = makeDelivery([{ status: 'DONE' }, {}]);
    await actions.processDelivery(delivery);
    expect(completeCalls).toEqual([{ deliveryId: 'd1', seq: 1, status: 'DONE' }]);
  });

  it('falha em uma ação não interrompe as demais; conta failures', async () => {
    const { client, completeCalls } = fakeClient();
    const actions = new DeliveryActions(
      client,
      executorWith({ 0: { ok: false, note: 'grant_role_falhou' } }),
    );
    const delivery = makeDelivery([{}, {}]);
    const { failures } = await actions.processDelivery(delivery);
    expect(failures).toBe(1);
    expect(completeCalls).toEqual([
      { deliveryId: 'd1', seq: 0, status: 'FAILED', note: 'grant_role_falhou' },
      { deliveryId: 'd1', seq: 1, status: 'DONE' },
    ]);
  });

  it('enqueueRetry reabre via API e reprocessa após o backoff', async () => {
    vi.useFakeTimers();
    try {
      const { client, retryCalls, completeCalls } = fakeClient();
      const executor = executorWith({ 0: { ok: false, note: 'x' } });
      const actions = new DeliveryActions(client, executor, undefined as never, [1_000], 5);

      // primeira passada falha
      const { failures } = await actions.processDelivery(makeDelivery([{}]));
      expect(failures).toBe(1);

      // agenda retry e espera o backoff
      actions.enqueueRetry('d1', 1);
      await vi.advanceTimersByTimeAsync(1_000);

      expect(retryCalls).toEqual(['d1']);
      // a delivery do retry volta sem ações PENDING (fake) — sem nova execução
      expect(completeCalls).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('enqueueRetry esgota após maxAttempts (não reabre mais)', async () => {
    vi.useFakeTimers();
    try {
      const { client, retryCalls } = fakeClient();
      const actions = new DeliveryActions(
        client,
        executorWith({}),
        undefined as never,
        [1_000, 2_000],
        2,
      );
      actions.enqueueRetry('d1', 3); // acima do max → não agenda
      await vi.advanceTimersByTimeAsync(10_000);
      expect(retryCalls).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('stop cancela retries pendentes', async () => {
    vi.useFakeTimers();
    try {
      const { client, retryCalls } = fakeClient();
      const actions = new DeliveryActions(
        client,
        executorWith({}),
        undefined as never,
        [1_000],
        5,
      );
      actions.enqueueRetry('d1', 1);
      actions.stop();
      await vi.advanceTimersByTimeAsync(10_000);
      expect(retryCalls).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('dispara onDelivered quando nenhuma ação falha (Spec 51)', async () => {
    const { client } = fakeClient();
    const delivered: Delivery[] = [];
    const actions = new DeliveryActions(client, executorWith({}), undefined as never, [1_000], 5, async (d) => {
      delivered.push(d);
    });
    await actions.processDelivery(makeDelivery([{}, {}]));
    expect(delivered).toHaveLength(1);
    expect(delivered[0].id).toBe('d1');
  });

  it('não dispara onDelivered quando há falha', async () => {
    const { client } = fakeClient();
    const delivered: Delivery[] = [];
    const actions = new DeliveryActions(
      client,
      executorWith({ 0: { ok: false, note: 'x' } }),
      undefined as never,
      [1_000],
      5,
      async (d) => {
        delivered.push(d);
      },
    );
    await actions.processDelivery(makeDelivery([{}, {}]));
    expect(delivered).toHaveLength(0);
  });
});
