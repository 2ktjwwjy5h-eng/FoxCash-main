// Idempotência em throughput (Spec 62, F4/task 16): 10.000 requests
// CONCORRENTES com a MESMA idempotencyKey → 1 transação, 0 duplicações.
//
// Uso (rate limit deve estar DESLIGADO — FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false):
//   k6 run load-tests/k6/idempotency-concurrency.js --env BASE_URL=http://localhost:8080
//
// Evidência esperada:
//   - http_req_failed == 0 (nenhum 500; o 409 idempotency_conflict da corrida
//     M-A2 é aceitável e CONTADO separadamente — o cliente re-consulta)
//   - após o run: SELECT count(*) FROM pix_transactions WHERE idempotency_key=K
//     → 1 (0 duplicações); contagem de CHARGE_CREATED no outbox da transação.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 500);
const ITERATIONS = Number(__ENV.ITERATIONS || 10000);

const statusByCode = new Counter('idem_http_status');
const okRate = new Rate('idem_ok');      // 201 (criação ou replay)
const conflictRate = new Rate('idem_conflict'); // 409 idempotency_conflict (M-A2)
const serverErrorRate = new Rate('idem_5xx');   // 500 — DEVE ser 0

export const options = {
  scenarios: {
    idempotency: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: '5m',
    },
  },
  thresholds: {
    idem_5xx: ['rate==0'],            // zero erro de servidor
    http_req_duration: ['p(95)<2000'],
  },
};

export function setup() {
  const ts = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/merchants`, JSON.stringify({
    name: `Idem ${ts}`,
    email: `idem${ts}@foxcash.test`,
    password: 'Senha-Baseline-123',
  }), { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 201) {
    throw new Error(`setup: cadastro falhou (${res.status}): ${res.body}`);
  }
  const body = res.json();
  return { apiKey: body.apiKey, merchantId: body.id, idempotencyKey: `idem-10k-${ts}` };
}

export default function (data) {
  const res = http.post(`${BASE_URL}/api/v1/foxcash/pix/cob`, JSON.stringify({
    merchantId: data.merchantId,
    amount: 19.90,
    expirationSeconds: 3600,
    idempotencyKey: data.idempotencyKey,
  }), { headers: { 'Content-Type': 'application/json', 'X-Api-Key': data.apiKey } });

  statusByCode.add(1, { code: String(res.status) });
  if (res.status === 201) {
    okRate.add(1);
    const ok = check(res, {
      '201 com transactionId': (r) => JSON.parse(r.body).transactionId !== undefined,
    });
  } else if (res.status === 409) {
    conflictRate.add(1);
  } else if (res.status >= 500) {
    serverErrorRate.add(1);
  }
}
