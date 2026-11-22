// Teste de CALIBRAÇÃO do rate limit por principal (Spec 58) na infra real.
//
// Cada run cria um lojista NOVO via registro público (setup) — o bucket do
// AuthenticatedRateLimitFilter é por principal (merchantId), então cada run
// nasce com janela limpa (baseline 120 req/min). Todas as VUs disparam em
// burst (1 iteração cada) contra GET /api/v1/merchants/{id} com a X-Api-Key
// do lojista — rota autenticada SEM bucket de IP (determinismo; mesmo padrão
// do verify-rate-limit-429.py, camada A).
//
// Objetivo: medir a DEGRADAÇÃO CONTROLADA:
//   - 200 (aceitos)      — latência REAL da infra sob rajada (p95/p99)
//   - 429 (bloqueados)   — o teto cortando o excesso ANTES de saturar
//   - 5xx                — NUNCA deve existir (saturação antes do teto =
//                          teto alto demais para a infra)
//   - status 0           — erro de conexão (infra recusando socket) = sinal
//                          de saturação de accept/Tomcat
//
// Uso (ramp recomendado pela Spec 58):
//   k6 run load-tests/k6/rate-limit-calibration.js \
//     --env BASE_URL=https://foxcash-production.up.railway.app --env VUS=50
//   ... repetir com VUS=100, 500, 1000, 2000, 5000 (cada run = lojista novo).
//
// Thresholds (falha se violar):
//   http_5xx            rate < 1%   (sem saturação antes do teto)
//   http_conn_error     rate < 1%   (sem recusa de socket)
//   accepted_latency_ms p(95) < 1500ms (ponto de inflexão dos aceitos;
//                                       --env ACCEPTED_P95_MAX_MS sobrepõe)

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 50);
const ACCEPTED_P95_MAX_MS = Number(__ENV.ACCEPTED_P95_MAX_MS) || 1500;

const acceptedLatency = new Trend('accepted_latency_ms');
const rate200 = new Rate('http_200');
const rate429 = new Rate('http_429');
const rate5xx = new Rate('http_5xx');
const rateOther = new Rate('http_other');
const rateConnError = new Rate('http_conn_error');
const cnt429 = new Counter('count_429');
const cnt5xx = new Counter('count_5xx');

export const options = {
  scenarios: {
    // Todas as VUs começam no mesmo instante e fazem 1 iteração cada (burst).
    burst: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: VUS,
      maxDuration: '120s',
    },
  },
  thresholds: {
    // 5xx = saturação ANTES do teto — teto alto demais para a infra.
    http_5xx: ['rate<0.01'],
    // status 0 = conexão recusada/estourada (accept queue do Tomcat).
    http_conn_error: ['rate<0.01'],
    // p95 da latência dos ACEITOS (200) — o ponto de inflexão real.
    accepted_latency_ms: [`p(95)<${ACCEPTED_P95_MAX_MS}`],
  },
};

/** Cria um lojista novo (bucket de rate limit fresco por run). */
export function setup() {
  const ts = Date.now();
  const headers = { 'Content-Type': 'application/json' };
  const reg = http.post(
    `${BASE}/api/v1/merchants`,
    JSON.stringify({
      name: `Calib ${ts}`,
      email: `calib.${ts}@example.com`,
      password: 'Senha-calib-1',
    }),
    { headers },
  );
  const merchant = reg.json();
  if (reg.status !== 201 || !merchant || !merchant.id || !merchant.apiKey) {
    throw new Error(`setup: registro falhou (status=${reg.status}) body=${reg.body}`);
  }
  return { apiKey: merchant.apiKey, merchantId: merchant.id };
}

export default function (data) {
  const res = http.get(`${BASE}/api/v1/merchants/${data.merchantId}`, {
    headers: { 'X-Api-Key': data.apiKey },
  });
  const st = res.status;
  if (st === 200) {
    rate200.add(1);
    acceptedLatency.add(res.timings.duration);
  } else if (st === 429) {
    rate429.add(1);
    cnt429.add(1);
  } else if (st >= 500) {
    rate5xx.add(1);
    cnt5xx.add(1);
  } else if (st === 0) {
    rateConnError.add(1);
    rateOther.add(1);
  } else {
    rateOther.add(1);
  }
  check(res, {
    '200 ou 429 (nunca 5xx/erro de conexão)': (r) =>
      r.status === 200 || r.status === 429,
  });
}
