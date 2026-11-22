// Teste de CALIBRAÇÃO do rate limit de webhooks por IP (Spec 59) na infra real.
//
// Todas as VUs disparam em burst contra POST /webhooks/pix com assinatura
// INVÁLIDA (X-Psp-Signature: invalida) — o WebhookSignatureFilter faz o rate
// limit por IP no TOPO do filtro, ANTES de ler o body/computar HMAC (Spec 59):
//   - bucket disponível  → segue para HMAC → assinatura inválida → 401
//   - bucket esgotado    → 429 too_many_requests (sem gastar CPU no HMAC)
//
// Isso exercita o caminho real de proteção contra flood SEM processar
// pagamento (assinatura inválida nunca chega ao handler — sem risco de crédito
// indevido em produção).
//
// O contador usa o RateLimitStore (Spec 43/ADR-010):
//   - store=memory (single-instance) → teto 120/min POR RÉPLICA
//   - store=redis  (multi-instância) → teto 120/min AGREGADO entre réplicas
//
// Com FLOOD > teto, a contagem de 429 revela o store:
//   - redis  → 429 ≈ FLOOD - 120 (agregado — Spec 61/5c PASS)
//   - memory → 429 ≈ FLOOD - 120*2 (se as réplicas dividem o tráfego)
//
// Uso (Spec 59 task 8):
//   k6 run load-tests/k6/webhook-flood-calibration.js \
//     --env BASE_URL=https://foxcash-production.up.railway.app --env FLOOD=300
//
// Thresholds (falha se violar):
//   http_5xx            rate < 1%   (saturação antes do teto)
//   http_conn_error     rate < 1%   (sem recusa de socket)
//   accepted_latency_ms p(95) < 1500ms (latência dos 401/204 — ponto de inflexão)

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const FLOOD = Number(__ENV.FLOOD || 300);
const ACCEPTED_P95_MAX_MS = Number(__ENV.ACCEPTED_P95_MAX_MS) || 1500;

const acceptedLatency = new Trend('accepted_latency_ms');
const rate2xx = new Rate('http_2xx');
const rate401 = new Rate('http_401');
const rate429 = new Rate('http_429');
const rate5xx = new Rate('http_5xx');
const rateConnError = new Rate('http_conn_error');
const cnt429 = new Counter('count_429');
const cnt5xx = new Counter('count_5xx');
const cnt401 = new Counter('count_401');

export const options = {
  scenarios: {
    // Todas as VUs começam no mesmo instante e fazem 1 iteração cada (burst).
    burst: {
      executor: 'shared-iterations',
      vus: FLOOD,
      iterations: FLOOD,
      maxDuration: '120s',
    },
  },
  thresholds: {
    // 5xx = saturação ANTES do teto — o teto 120/min está alto demais para a infra.
    http_5xx: ['rate<0.01'],
    // status 0 = conexão recusada/estourada (accept queue do Tomcat).
    http_conn_error: ['rate<0.01'],
    // p95 da latência dos ACEITOS (401/2xx) — o ponto de inflexão real.
    accepted_latency_ms: [`p(95)<${ACCEPTED_P95_MAX_MS}`],
  },
};

export default function () {
  const res = http.post(
    `${BASE}/webhooks/pix`,
    JSON.stringify({ type: 'payment.paid', externalReference: 'flood-calibration' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Psp-Signature': 'invalida', // assinatura inválida de propósito
      },
    },
  );
  const st = res.status;
  if (st === 204 || st === 200) {
    rate2xx.add(1);
    acceptedLatency.add(res.timings.duration);
  } else if (st === 401) {
    rate401.add(1);
    cnt401.add(1);
    acceptedLatency.add(res.timings.duration);
  } else if (st === 429) {
    rate429.add(1);
    cnt429.add(1);
  } else if (st >= 500) {
    rate5xx.add(1);
    cnt5xx.add(1);
  } else if (st === 0) {
    rateConnError.add(1);
  }
  check(res, {
    '401/429/204 apenas (nunca 5xx/erro de conexão)': (r) =>
      r.status === 401 || r.status === 429 || r.status === 204 || r.status === 200,
  });
}
