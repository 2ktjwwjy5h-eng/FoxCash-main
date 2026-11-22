// Teste de capacidade por estágio de throughput (Spec 62, F7/task 23-25).
//
// 1 venda = POST /api/v1/foxcash/pix/cob (criar cobrança, idempotencyKey única)
//         + POST /webhooks/pix (PAID) — o ciclo completo de uma venda.
//
// Unidade de medida = VENDAS/S ALCANÇADAS (não VUs). O executor
// 'constant-arrival-rate' com rate = STAGE (vendas/s alvo) dirige as iterações;
// cada iteração = 1 venda. O relatório registra AMBOS: VUs configurados e
// vendas/s medidas (http_reqs/2 por segundo — a métrica de vendas/s é
// deduzida das iterações completadas).
//
// Uso (rate limit ELEVADO para não mascarar o teto real — task 24):
//   k6 run load-tests/k6/capacity-stages.js \
//     --env BASE_URL=https://<railway>.up.railway.app \
//     --env STAGE=1000 \
//     --env DURATION=60s
// Estágios: 100 → 250 → 500 → 1000 → 2000 → 3000 → 4000 → 5000 → 6000 → 8000 → 10000
//
// Rate limit elevado na app (task 24): suba com tetos acima do estágio sob
// teste, ex.:
//   FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false   (mais simples; justificar no relatório)
//   ou tetos altos: FOXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS=1000000
//   e FOXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS=1000000
//
// p95/p99 por endpoint: use o output JSON (--summary-export) e a métrica
// http_req_duration por nome de grupo.

import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STAGE = Number(__ENV.STAGE || 100);      // vendas/s alvo
const DURATION = __ENV.DURATION || '60s';       // janela do estágio
const VUS = Number(__ENV.VUS || STAGE);         // VUs configurados (default = rate)
// Teto de VUs (default = 2×VUS, mínimo 2000). Achado F7 (2026-08-08): o edge
// Railway limita conexões simultâneas por IP de origem — acima de ~1500 VUs o k6
// (1 IP) acumula timeouts de conexão TCP. Para isolar o teto real da app use
// MAX_VUS baixo (ex: 600) — o k6 então não escala além disso.
const MAX_VUS = Number(__ENV.MAX_VUS || Math.max(VUS * 2, 2000));
// Em PRODUÇÃO o webhook exige assinatura (verify-signature=true): informe o
// FOXCASH_PSP_WEBHOOK_SECRET via --env WEBHOOK_SECRET=<segredo> para assinar o
// payload (HMAC-SHA256 hex do raw body, header X-Psp-Signature — Spec 10/11).
// Em dev (verify-signature=false) pode rodar sem o segredo.
const WEBHOOK_SECRET = __ENV.WEBHOOK_SECRET || '';

const chargeTrend = new Trend('charge_duration');
const webhookTrend = new Trend('webhook_duration');
const saleOk = new Rate('sale_ok');
const sale5xx = new Rate('sale_5xx');

export const options = {
  // Pool de lojistas criado no setup() (1 request por lojista em produção) pode
  // demorar: 80 lojistas × ~0.7s = ~56s. Timeout padrão (60s) estoura com
  // POOL alto — elevado para acomodar POOL=80+ no benchmark F7 (2026-08-08).
  setupTimeout: '180s',
  scenarios: {
    capacity: {
      executor: 'constant-arrival-rate',
      rate: STAGE,            // iterações (vendas) por segundo
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(VUS, 200),
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    sale_5xx: ['rate<0.0001'],       // ~0 5xx (teto real, sem rate limit mascarando)
    http_req_failed: ['rate<0.001'],
  },
};

export function setup() {
  const ts = Date.now();
  // Instância distribuída (Etapa 2, 2026-08-09): sufixo aleatório por réplica do
  // loadgen — com N geradores rodando o MESMO script, o keyBase (cap-<ts>) de
  // duas réplicas que iniciaram no mesmo ms colidiria em idempotencyKey (409/
  // duplicata = falso negativo). O Math.random() no setup difere por réplica.
  const instanceTag = __ENV.INSTANCE || Math.random().toString(36).slice(2, 10);
  // Pool de lojistas: o cap de cobranças PENDENTES é POR LOJISTA (Spec 58,
  // foxcash.pricing.max-pending-charges, baseline 100). Com 1 lojista o teste
  // esbarra em 409 too_many_pending_charges a ~100 vendas/s (achado F7) — a
  // produção tem muitos lojistas, então o benchmark distribui as vendas por um
  // pool (padrão = STAGE/50, garantindo >= 100 pendentes livres por lojista).
  const poolSize = Number(__ENV.POOL || Math.max(2, Math.ceil(STAGE / 50)));
  const merchants = [];
  for (let i = 0; i < poolSize; i++) {
    const res = http.post(`${BASE_URL}/api/v1/merchants`, JSON.stringify({
      name: `Cap ${ts}-${instanceTag}-${i}`,
      email: `cap${ts}${instanceTag}${i}@foxcash.test`,
      password: 'Senha-Baseline-123',
    }), { headers: { 'Content-Type': 'application/json' } });
    if (res.status !== 201) {
      // res.body pode ser null em falha de conexão (ex.: connection refused no
      // setup) — não deixar o próprio handler de erro quebrar o diagnóstico.
      throw new Error(`setup: cadastro falhou (${res.status}): ${(res.body || res.error || '').slice(0, 200)}`);
    }
    const body = res.json();
    merchants.push({ apiKey: body.apiKey, merchantId: body.id });
  }
  return {
    merchants,
    // idempotencyKey única por iteração (instância + VU + iteração) — 1 venda = 1 key.
    keyBase: `cap-${ts}-${instanceTag}`,
  };
}

export default function (data) {
  // Distribuição por VU → cada VU usa sempre o mesmo lojista (determinístico).
  const merchant = data.merchants[__VU % data.merchants.length];
  // 1 venda: cobrança (idempotencyKey única) + webhook PAID.
  const key = `${data.keyBase}-${__VU}-${__ITER}`;
  const charge = http.post(`${BASE_URL}/api/v1/foxcash/pix/cob`, JSON.stringify({
    merchantId: merchant.merchantId,
    amount: 19.90,
    expirationSeconds: 3600,
    idempotencyKey: key,
  }), { headers: { 'Content-Type': 'application/json', 'X-Api-Key': merchant.apiKey } });
  chargeTrend.add(charge.timings.duration);

  if (charge.status !== 201) {
    saleOk.add(0);
    if (charge.status >= 500) sale5xx.add(1);
    return; // sem cobrança, sem webhook — venda não completada
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

  const ok = webhook.status === 204;
  saleOk.add(ok ? 1 : 0);
  if (webhook.status >= 500) sale5xx.add(1);
  check(webhook, { 'webhook PAID 204': (r) => r.status === 204 });
}
