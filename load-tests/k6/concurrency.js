// Teste de CONCORRÊNCIA dedicado do backend FoxCash (k6).
//
// Diferente do load.js (rampa), este teste dispara TODAS as VUs simultaneamente
// desde o primeiro segundo — mede o comportamento sob rajada de requisições
// concorrentes (condição de corrida, pool de conexões, lock do Hikari, etc.).
//
// Uso:
//   k6 run load-tests/k6/concurrency.js --env BASE_URL=http://localhost:8080
//
// Suba a app com FOXCASH_RATE_LIMIT_ENABLED=false para o rate limit não interferir.

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MASTER_USERNAME = __ENV.MASTER_USERNAME || 'master';
const MASTER_PASSWORD = __ENV.MASTER_PASSWORD || 'dev-only-master-seed-password';
const ADMIN_API_KEY = __ENV.ADMIN_API_KEY || 'dev-only-admin-api-key';

// Número de VUs simultâneas (padrão 50; use --env VUS=200 para estressar mais).
const VUS = Number(__ENV.VUS || 50);

// Teto do p95 (ms) — padrão estrito 1000ms. O CI (runner GitHub de 2 vCPUs,
// 50 VUs de login BCrypt = CPU-bound) observa p95≈3.75s; lá passa
// --env P95_MAX_MS=5000 (envelope do runner, documentado no load-tests/README).
const P95_MAX_MS = Number(__ENV.P95_MAX_MS) || 1000;

export const options = {
  scenarios: {
    // Todas as VUs começam no mesmo instante e fazem 1 iteração cada.
    burst: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: VUS,
      maxDuration: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<' + P95_MAX_MS],
  },
};

const loginErrors = new Rate('login_errors');

export default function () {
  const payload = JSON.stringify({ username: MASTER_USERNAME, password: MASTER_PASSWORD });
  const login = http.post(`${BASE_URL}/api/v1/auth/master/login`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  const loginOk = check(login, {
    'login 200': (r) => r.status === 200,
    'login tem token': (r) => JSON.parse(r.body).token !== undefined,
  });
  if (!loginOk) loginErrors.add(1);

  const token = login.json().token;
  if (token) {
    check(http.get(`${BASE_URL}/api/v1/admin/merchants`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'X-Api-Key': ADMIN_API_KEY,
      },
    }), {
      'admin/merchants 200': (r) => r.status === 200,
    });
  }
}
