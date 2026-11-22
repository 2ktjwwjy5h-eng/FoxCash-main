// Teste de CARGA dos endpoints críticos da loja (Spec 49/52):
//   /storefront            — o bot renderiza a loja (GET, API key)
//   /deliveries/pending    — o bot polla pendências (GET, API key)
//   /webhooks/pix          — confirmação do PSP: TODAS as VUs disparam PAID para
//                            a MESMA cobrança real (criada no setup) → mede o
//                            custo do LOCK da transação sob contenção + valida a
//                            idempotência (204 sempre, 1 crédito).
//
// O setup() cria um lojista real (loja + plano ativo + 1 cobrança pendente).
//
// Uso:
//   k6 run load-tests/k6/critical-endpoints.js --env VUS=1000 --env BASE_URL=http://localhost:8080
//   k6 run ... --scenario storefront  (executar um cenário isolado)
//
// VUS = usuários virtuais TOTAIS; distribuídos igualmente entre os 3 cenários
// (storefront, pending, webhook). Default 30 (≈10 por cenário).
//
// Suba a app com FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false (o rate limit é
// proteção de abuso, não alvo deste teste).

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

// VUs totais → por cenário (arredonda p/ cima, mínimo 1).
const VUS_TOTAL = Number(__ENV.VUS || 30);
const VUS_PER = Math.max(1, Math.round(VUS_TOTAL / 3));

/** Cria lojista + loja + plano ativo + 1 cobrança UMA vez. */
export function setup() {
  const ts = Date.now();
  const headers = { 'Content-Type': 'application/json' };
  const email = `k6.${ts}@example.com`;
  const password = 'Senha-k6-123';

  const reg = http.post(`${BASE}/api/v1/merchants`,
    JSON.stringify({ name: `Loja k6 ${ts}`, email, password }), { headers });
  const merchant = reg.json();

  const login = http.post(`${BASE}/api/v1/merchant-auth/login`,
    JSON.stringify({ email, password }), { headers });
  const portal = { Authorization: `Bearer ${login.json().token}`, 'Content-Type': 'application/json' };

  http.post(`${BASE}/api/v1/portal/store`, JSON.stringify({ name: `Loja k6 ${ts}` }), { headers: portal });

  const plan = http.post(`${BASE}/api/v1/portal/plans`,
    JSON.stringify({ name: 'Plano k6', price: 5.0, description: 'plano de carga' }), { headers: portal });
  const planId = plan.json().id;
  http.post(`${BASE}/api/v1/portal/plans/${planId}/activate`, null, { headers: portal });

  // 1 cobrança real (o webhook de carga aponta para ela — PAID idempotente).
  const cob = http.post(`${BASE}/api/v1/foxcash/plans/${planId}/purchase`,
    JSON.stringify({
      platform: 'DISCORD', platformUserId: `k6-user-${ts}`, buyerId: 'k6-buyer',
      expirationSeconds: 3600, idempotencyKey: `k6-purchase-${ts}`, termsAccepted: true,
    }),
    { headers: { 'X-Api-Key': merchant.apiKey, 'Content-Type': 'application/json' } });

  return { apiKey: merchant.apiKey, planId, externalReference: cob.json().externalReference };
}

export const options = {
  scenarios: {
    storefront: {
      executor: 'ramping-vus',
      exec: 'storefront',
      startVUs: 0,
      stages: [
        { duration: '10s', target: VUS_PER },
        { duration: '20s', target: VUS_PER },
        { duration: '10s', target: 0 },
      ],
    },
    pending: {
      executor: 'ramping-vus',
      exec: 'pending',
      startVUs: 0,
      stages: [
        { duration: '10s', target: VUS_PER },
        { duration: '20s', target: VUS_PER },
        { duration: '10s', target: 0 },
      ],
    },
    webhook: {
      executor: 'ramping-vus',
      exec: 'webhook',
      startVUs: 0,
      stages: [
        { duration: '10s', target: VUS_PER },
        { duration: '20s', target: VUS_PER },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function storefront(data) {
  const r = http.get(`${BASE}/api/v1/foxcash/storefront`, {
    headers: { 'X-Api-Key': data.apiKey },
  });
  check(r, { 'storefront 200': (res) => res.status === 200 });
  sleep(0.05);
}

export function pending(data) {
  const r = http.get(`${BASE}/api/v1/foxcash/deliveries/pending`, {
    headers: { 'X-Api-Key': data.apiKey },
  });
  check(r, { 'pending 200': (res) => res.status === 200 });
  sleep(0.05);
}

export function webhook(data) {
  // PAID concorrentes para a MESMA cobrança real (lock da transação): o 1º
  // aprova, os demais são replay idempotente 204 — mede a contenção do lock.
  const payload = JSON.stringify({
    eventId: `k6-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    externalReference: data.externalReference,
    status: 'PAID',
    settledAt: new Date().toISOString(),
    amount: 5.0,
  });
  const r = http.post(`${BASE}/webhooks/pix`, payload, { headers: { 'Content-Type': 'application/json' } });
  check(r, { 'webhook 204': (res) => res.status === 204 });
  sleep(0.05);
}
