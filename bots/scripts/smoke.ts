/**
 * Smoke E2E dos bots (Spec 52) — contra o app dev (Specs 48–51), **sem
 * plataformas reais** (executor fake de entrega):
 *
 *   storefront → comprar → QR/Copia-e-Cola → PSP webhook PAID (backend cria a
 *   delivery PENDING) → SALE_PAID assinado → webhook-server do bot (HMAC +
 *   dedup) → executa ações (fake DONE) → complete → DELIVERED; replay
 *   idempotente; entrega após "reinício" via polling.
 *
 * Requer: backend dev no ar (FOXCASH_BASE_URL) com a loja configurada (planos
 * ativos) + `FOXCASH_API_KEY` + `WEBHOOK_SECRET` no `.env`.
 *
 * Uso: `npm run smoke` (raiz do `bots/`). Exit 0 = sucesso.
 */
import 'dotenv/config';
import {
  loadConfig,
  createLogger,
  FoxCashClient,
  StorefrontCache,
  SalesMachine,
  FoxCashGatewayClient,
  DeliveryActions,
  Scheduler,
  WebhookServer,
  sign,
  type Delivery,
  type DeliveryAction,
  type ActionResult,
  type ScheduledMessageDue,
} from '@foxcash/bot-core';
import { randomUUID } from 'node:crypto';

let failed = 0;

function assert(cond: boolean, label: string): void {
  if (cond) {
    // eslint-disable-next-line no-console
    console.log(`  ✓ ${label}`);
  } else {
    failed += 1;
    // eslint-disable-next-line no-console
    console.error(`  ✗ ${label}`);
  }
}

/** Executor fake — marca tudo DONE (sem plataformas reais). */
const fakeExecutor = {
  execute: async (_d: Delivery, a: DeliveryAction): Promise<ActionResult> => {
    // eslint-disable-next-line no-console
    console.log(`    (fake) ação ${a.seq} ${a.actionType} → DONE`);
    return { ok: true };
  },
};

/** Sender fake de programadas — nada a enviar de verdade. */
const fakeScheduled: { send: (m: ScheduledMessageDue) => Promise<void> } = {
  send: async (_m: ScheduledMessageDue) => {},
};

async function main(): Promise<void> {
  const config = loadConfig();
  const logger = createLogger(config.LOG_LEVEL);
  const base = config.FOXCASH_BASE_URL.replace(/\/$/, '');

  // eslint-disable-next-line no-console
  console.log(`Smoke bots (Spec 52) contra ${base}`);

  const fox = new FoxCashClient(base, config.FOXCASH_API_KEY);
  const storefront = new StorefrontCache(fox);
  const gateway = new FoxCashGatewayClient({
    client: fox,
    botId: config.FOXCASH_BOT_ID || undefined,
  });
  const sales = new SalesMachine(gateway, storefront);

  // --- 1. Storefront (Specs 48–51) ---
  // eslint-disable-next-line no-console
  console.log('\n[1] Storefront');
  const sf = await storefront.get();
  assert(!!sf.store.name, `loja "${sf.store.name}"`);
  assert(sf.plans.length > 0, `planos ativos (${sf.plans.length})`);
  const plan = sf.plans[0];

  // --- 2. Compra → QR + Copia e Cola (Spec 49) ---
  // eslint-disable-next-line no-console
  console.log('\n[2] Compra');
  const platformUserId = `smoke-${randomUUID()}`;
  const { purchase } = await sales.openSale({
    planId: plan.id,
    platform: 'DISCORD',
    platformUserId,
  });
  assert(!!purchase.transactionId, `transactionId ${purchase.transactionId}`);
  assert(!!purchase.externalReference, `externalReference ${purchase.externalReference}`);
  assert(purchase.pixQrCode.length > 0, 'pixQrCode (base64 PNG)');
  assert(purchase.pixCopyPaste.startsWith('000201'), 'pixCopyPaste (BR Code)');

  // --- 3. PSP webhook PAID → backend cria a delivery PENDING (Spec 49) ---
  // eslint-disable-next-line no-console
  console.log('\n[3] PSP webhook PAID (backend cria delivery PENDING)');
  const pspRes = await fetch(`${base}/webhooks/pix`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      externalReference: purchase.externalReference,
      status: 'PAID',
      amount: plan.price,
    }),
  });
  assert(pspRes.status === 204, `POST /webhooks/pix → ${pspRes.status}`);
  const delivery = await fox.deliveryByTransaction(purchase.transactionId);
  assert(delivery.id !== undefined, `delivery criada (${delivery.status})`);
  assert(
    delivery.status === 'PENDING' || delivery.status === 'DELIVERED',
    `status PENDING (ou DELIVERED se plano sem ações) → ${delivery.status}`,
  );

  // --- 4. Webhook-server do bot: SALE_PAID assinado → ações → DELIVERED ---
  // eslint-disable-next-line no-console
  console.log('\n[4] SALE_PAID assinado → entrega');
  const actions = new DeliveryActions(fox, fakeExecutor, logger);
  const webhook = new WebhookServer({
    secret: config.WEBHOOK_SECRET,
    client: fox,
    deliveryActions: actions,
    logger,
  });
  const server = webhook.app.listen(0);
  const port = (server.address() as { port: number }).port;

  const eventId = randomUUID();
  const envelope = JSON.stringify({
    eventId,
    type: 'SALE_PAID',
    merchantId: 'smoke',
    createdAt: new Date().toISOString(),
    data: { transactionId: purchase.transactionId, amount: plan.price, paidAt: null },
  });
  const delivered = await fetch(`http://localhost:${port}/webhooks/foxcash`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-FoxCash-Signature': sign(envelope, config.WEBHOOK_SECRET),
      'X-FoxCash-Event-Id': eventId,
      'X-FoxCash-Event': 'SALE_PAID',
    },
    body: envelope,
  });
  assert(delivered.status === 204, `webhook SALE_PAID → ${delivered.status}`);

  const finalDelivery = await fox.deliveryByTransaction(purchase.transactionId);
  assert(finalDelivery.status === 'DELIVERED', `entrega DELIVERED → ${finalDelivery.status}`);
  const doneAll = finalDelivery.actions.every((a) => a.status === 'DONE');
  assert(doneAll, `ações todas DONE (${finalDelivery.actions.length})`);

  // --- 5. Replay idempotente (dedup por eventId — no-op 204) ---
  // eslint-disable-next-line no-console
  console.log('\n[5] Replay do mesmo eventId');
  const replay = await fetch(`http://localhost:${port}/webhooks/foxcash`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-FoxCash-Signature': sign(envelope, config.WEBHOOK_SECRET),
      'X-FoxCash-Event-Id': eventId,
      'X-FoxCash-Event': 'SALE_PAID',
    },
    body: envelope,
  });
  assert(replay.status === 204, `replay → ${replay.status} (no-op)`);

  // --- 6. Assinatura inválida → 401 ---
  // eslint-disable-next-line no-console
  console.log('\n[6] Assinatura inválida');
  // assina o MESMO body com outro segredo → HMAC divergente → 401
  const bad = await fetch(`http://localhost:${port}/webhooks/foxcash`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-FoxCash-Signature': sign(envelope, 'segredo-errado'),
      'X-FoxCash-Event-Id': randomUUID(),
      'X-FoxCash-Event': 'SALE_PAID',
    },
    body: envelope,
  });
  assert(bad.status === 401, `assinatura divergente → ${bad.status} (401)`);

  // --- 7. Entrega após "reinício" (polling recupera pendências) ---
  // eslint-disable-next-line no-console
  console.log('\n[7] Polling recupera pendência (simula reinício do bot)');
  const second = await sales.openSale({
    planId: plan.id,
    platform: 'DISCORD',
    platformUserId: `smoke2-${randomUUID()}`,
  });
  const psp2 = await fetch(`${base}/webhooks/pix`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      externalReference: second.purchase.externalReference,
      status: 'PAID',
      amount: plan.price,
    }),
  });
  assert(psp2.status === 204, `2ª compra PAID → ${psp2.status}`);

  // novo scheduler (bot "reiniciou" — sem estado em memória)
  const scheduler = new Scheduler(
    fox,
    new DeliveryActions(fox, fakeExecutor, logger),
    'DISCORD',
    fakeScheduled,
    15_000,
    logger,
  );
  await scheduler.pollOnce();
  const recovered = await fox.deliveryByTransaction(second.purchase.transactionId);
  assert(recovered.status === 'DELIVERED', `polling entregou → ${recovered.status}`);

  server.close();

  // eslint-disable-next-line no-console
  console.log(`\n${failed === 0 ? 'Smoke OK ✅' : `Smoke FALHOU (${failed} checks)`}`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('Smoke abortado:', err);
  process.exit(1);
});
