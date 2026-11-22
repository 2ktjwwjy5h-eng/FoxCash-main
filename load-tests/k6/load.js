// Testes de carga/concorrência do backend FoxCash (k6).
//
// Uso:
//   k6 run load-tests/k6/load.js --env BASE_URL=http://localhost:8080
//
// Cenários (options.scenarios):
//   - smoke:    1 VU, 1 iteração — valida que os endpoints respondem
//   - load:     rampa até 20 VUs, 2 min — carga média sustentada
//   - stress:   pico de 100 VUs simultâneas, 1 min — concorrência alta
//   - soak:     10 VUs, 5 min — estabilidade prolongada (opcional, desligado)
//
// Para rodar sem o rate limit de 20 req/min/IP interferir, suba a aplicação com
//   FOXCASH_RATE_LIMIT_ENABLED=false
// (o rate limit é proteção de abuso em produção, não alvo deste teste).
//
// Observação sobre o seed de dev: o MasterSeedRunner cria o master admin no
// boot com FOXCASH_MASTER_SEED_USERNAME/PASSWORD (default master /
// dev-only-master-seed-password no application-dev.yml). O login de lojista
// exige um merchant cadastrado — o cenário de load usa o login do master
// (endpoint público) + uma rota autenticada de ADMIN para exercitar a stack
// completa (security + DB + service) sem depender de fixtures de lojista.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Configuração
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MASTER_USERNAME = __ENV.MASTER_USERNAME || 'master';
const MASTER_PASSWORD = __ENV.MASTER_PASSWORD || 'dev-only-master-seed-password';
const ADMIN_API_KEY = __ENV.ADMIN_API_KEY || 'dev-only-admin-api-key';

export const options = {
  scenarios: {
    smoke: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      tags: { scenario: 'smoke' },
    },
    load: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 20 },   // rampa até 20 VUs
        { duration: '60s', target: 20 },   // carga sustentada
        { duration: '30s', target: 0 },    // descida
      ],
      tags: { scenario: 'load' },
    },
    stress: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '15s', target: 100 },  // pico de 100 VUs simultâneas
        { duration: '30s', target: 100 },
        { duration: '15s', target: 0 },
      ],
      tags: { scenario: 'stress' },
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],              // < 1% de erros
    // Linha de base medida em dev (12.782 reqs, 0% falhas): p95 = 718ms.
    // O gargalo é o login BCrypt (hash ~50-100ms CPU/req). Metas abaixo são a
    // linha de base real; para meta agressiva de produção, baixar para p(95)<400
    // após otimizar/cachear o BCrypt ou medir em hardware de produção.
    http_req_duration: ['p(95)<1000'],
    'http_req_duration{scenario:load}': ['p(95)<800'],
    'http_req_duration{scenario:stress}': ['p(95)<1500'],
  },
};

// ---------------------------------------------------------------------------
// Métricas customizadas
// ---------------------------------------------------------------------------

const loginTrend = new Trend('login_duration', true);
const adminTrend = new Trend('admin_duration', true);
const loginErrors = new Rate('login_errors');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function masterLogin() {
  const payload = JSON.stringify({ username: MASTER_USERNAME, password: MASTER_PASSWORD });
  const res = http.post(`${BASE_URL}/api/v1/auth/master/login`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { scenario: __ENV.K6_SCENARIO || 'load' },
  });
  loginTrend.add(res.timings.duration);
  const ok = check(res, {
    'login 200': (r) => r.status === 200,
    'login tem token': (r) => JSON.parse(r.body).token !== undefined,
  });
  if (!ok) loginErrors.add(1);
  return res.json();
}

function adminMerchants(token) {
  const res = http.get(`${BASE_URL}/api/v1/admin/merchants`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'X-Api-Key': ADMIN_API_KEY,
    },
    tags: { scenario: __ENV.K6_SCENARIO || 'load' },
  });
  adminTrend.add(res.timings.duration);
  check(res, {
    'admin/merchants 200': (r) => r.status === 200,
  });
  return res;
}

// ---------------------------------------------------------------------------
// Iteração principal
// ---------------------------------------------------------------------------

export default function () {
  // Login do master (endpoint público) — exercita BCrypt + sessão + DB.
  const login = masterLogin();

  if (login.token) {
    // Rota autenticada de ADMIN — exercita auth filter + service + DB.
    adminMerchants(login.token);
  }

  // Pausa realista entre requisições de um usuário (evita um único VU
  // martelando o endpoint num loop infinito).
  sleep(0.5);
}
