// Teste de EXPLOSÃO (Spec 62, F8/task 27) — rate limit ATIVO (tetos originais).
//
// Objetivo: provar a "quebra bonita" — sob chegada de 5k→20k vendas/s, o app
// DEGRADA via 429/backpressure (rate limit Specs 58/59) e NUNCA via 5xx,
// corrupção, deadlock, OOM ou restart loop. O 429 é o mecanismo esperado;
// o que NÃO pode acontecer é 5xx, Postgres/pool/JVM mortos ou saldo
// inconsistente.
//
// Diferença vs capacity-stages.js: aqui o rate limit está ATIVO (tetos
// originais 20/120/300/120), então a maioria das chegadas vira 429 — o
// script separa e conta por status:
//   - sale_5xx      → CRÍTICO (threshold rate<0.0001) — nunca deve subir
//   - charge_429    → backpressure esperado no POST /foxcash/pix/cob
//   - webhook_429   → backpressure esperado no POST /webhooks/pix
//   - sale_ok       → venda completa (charge 201 + webhook 204)
//   - dropped       → chegadas que o k6 não conseguiu executar (VU cap)
//
// Uso (por réplica do loadgen; rate total = N réplicas × STAGE):
//   k6 run loadgen/explosion-stages.js \
//     --env BASE_URL=http://foxcash.railway.internal:8080 \
//     --env STAGE=5000 --env DURATION=30s --env WEBHOOK_SECRET=<segredo>
//
// 5k → 7.5k → 10k → 15k → 20k vendas/s: subir STAGE e re-deployar o loadgen
// (cada run = 1 estágio; entre estágios conferir integridade + drenagem).
//
// Nota sobre o setup: o pool de lojistas é criado via POST /api/v1/merchants
// (cadastro público). O teto ANÔNIMO (20/min/IP) precisa ficar elevado
// durante a campanha para o pool ser criado (documentado no roadmap/runbook);
// o anônimo é restaurado na etapa de restauração final. Sem isso o setup
// falha com 429 e o gerador nem chega ao estágio.

import http from 'k6/http';
import crypto from 'k6/crypto';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STAGE = Number(__ENV.STAGE || 5000);   // vendas/s alvo POR RÉPLICA
const DURATION = __ENV.DURATION || '30s';
const POOL = Number(__ENV.POOL || Math.max(20, Math.ceil(STAGE / 250)));
const MAX_VUS = Number(__ENV.MAX_VUS || 2000);
const WEBHOOK_SECRET = __ENV.WEBHOOK_SECRET || '';

const chargeTrend = new Trend('charge_duration');
const webhookTrend = new Trend('webhook_duration');
const saleOk = new Rate('sale_ok');
const sale5xx = new Rate('sale_5xx');
const charge429 = new Rate('charge_429');
const webhook429 = new Rate('webhook_429');

export const options = {
  setupTimeout: '240s',
  scenarios: {
    explosion: {
      executor: 'constant-arrival-rate',
      rate: STAGE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(MAX_VUS, 500),
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    // Quebra bonita: 5xx ≈ 0 SEMPRE (é o que a explosão precisa provar).
    sale_5xx: ['rate<0.0001'],
  },
};

export function setup() {
  const ts = Date.now();
  const instanceTag = __ENV.INSTANCE || Math.random().toString(36).slice(2, 10);
  const merchants = [];
  for (let i = 0; i < POOL; i++) {
    const res = http.post(`${BASE_URL}/api/v1/merchants`, JSON.stringify({
      name: `Expl ${ts}-${instanceTag}-${i}`,
      email: `expl${ts}${instanceTag}${i}@foxcash.test`,
      password: 'Senha-Baseline-123',
    }), { headers: { 'Content-Type': 'application/json' } });
    if (res.status !== 201) {
      throw new Error(`setup: cadastro falhou (${res.status}): ${(res.body || res.error || '').slice(0, 200)}`);
    }
    const body = res.json();
    merchants.push({ apiKey: body.apiKey, merchantId: body.id });
  }
  return {
    merchants,
    keyBase: `expl-${ts}-${instanceTag}`,
  };
}

export default function (data) {
  const merchant = data.merchants[__VU % data.merchants.length];
  const key = `${data.keyBase}-${__VU}-${__ITER}`;
  const charge = http.post(`${BASE_URL}/api/v1/foxcash/pix/cob`, JSON.stringify({
    merchantId: merchant.merchantId,
    amount: 19.90,
    expirationSeconds: 3600,
    idempotencyKey: key,
  }), { headers: { 'Content-Type': 'application/json', 'X-Api-Key': merchant.apiKey } });
  chargeTrend.add(charge.timings.duration);

  if (charge.status === 429) {
    charge429.add(1);
    saleOk.add(0);
    return;
  }
  if (charge.status !== 201) {
    saleOk.add(0);
    if (charge.status >= 500) sale5xx.add(1);
    return;
  }

  const externalReference = charge.json('externalReference');
  const body = JSON.stringify({
    eventId: `evt-${key}`,
    externalReference,
    status: 'PAID',
    amount: 19.90,
  });
  const headers = { 'Content-Type': 'application/json' };
  if (WEBHOOK_SECRET) {
    headers['X-Psp-Signature'] = crypto.hmac('sha256', WEBHOOK_SECRET, body, 'hex');
  }
  const webhook = http.post(`${BASE_URL}/webhooks/pix`, body, { headers });
  webhookTrend.add(webhook.timings.duration);

  if (webhook.status === 429) {
    webhook429.add(1);
    saleOk.add(0);
    return;
  }
  const ok = webhook.status === 204;
  saleOk.add(ok ? 1 : 0);
  if (webhook.status >= 500) sale5xx.add(1);
}

export function handleSummary(data) {
  // Registrar as chegadas que o k6 não conseguiu executar (backpressure do lado do gerador).
  const m = data.metrics || {};
  const val = (name) => {
    const metric = m[name];
    return (metric && metric.values) ? metric.values : {};
  };
  return { stdout: JSON.stringify({
    stage_s: STAGE,
    iterations: val('iterations').count || 0,
    dropped: (m.dropped_iterations && m.dropped_iterations.values) ? m.dropped_iterations.values.count : 0,
    sale_ok_rate: val('sale_ok').rate,
    sale_5xx_rate: val('sale_5xx').rate,
    charge_429_rate: val('charge_429').rate,
    webhook_429_rate: val('webhook_429').rate,
    charge_duration_p95_ms: val('charge_duration')['p(95)'],
    webhook_duration_p95_ms: val('webhook_duration')['p(95)'],
    http_req_failed_rate: val('http_req_failed').rate,
  }, null, 1) };
}
