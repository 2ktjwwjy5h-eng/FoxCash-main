// Diagnóstico (Spec 62 F7): 50-500 vendas/s — loga a distribuição de status
// de charge e webhook para entender as falhas. Usa POOL de lojistas (env POOL)
// para não esbarrar no cap de pendentes por lojista (Spec 58).
import http from 'k6/http';
import crypto from 'k6/crypto';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STAGE = Number(__ENV.STAGE || 50);
const DURATION = __ENV.DURATION || '15s';
const WEBHOOK_SECRET = __ENV.WEBHOOK_SECRET || '';
const POOL = Number(__ENV.POOL || Math.max(2, Math.ceil(STAGE / 50)));

export const options = {
  scenarios: {
    diag: {
      executor: 'constant-arrival-rate',
      rate: STAGE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(STAGE, 100),
      maxVUs: Math.max(STAGE * 2, 500),
    },
  },
};

export function setup() {
  const ts = Date.now();
  const merchants = [];
  for (let i = 0; i < POOL; i++) {
    const res = http.post(`${BASE_URL}/api/v1/merchants`, JSON.stringify({
      name: `Diag ${ts}-${i}`,
      email: `diag${ts}-${i}@foxcash.test`,
      password: 'Senha-Baseline-123',
    }), { headers: { 'Content-Type': 'application/json' } });
    if (res.status !== 201) throw new Error(`setup[${i}]: ${res.status}`);
    merchants.push({ apiKey: res.json('apiKey'), merchantId: res.json('id') });
  }
  return { merchants, keyBase: `diag-${ts}` };
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

  if (charge.status !== 201) {
    const code = (charge.body.match(/"code":"([^"]+)"/) || [])[1] || '?';
    console.log(`CHARGE status=${charge.status} code=${code}`);
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
  if (WEBHOOK_SECRET) headers['X-Psp-Signature'] = crypto.hmac('sha256', WEBHOOK_SECRET, body, 'hex');
  const wh = http.post(`${BASE_URL}/webhooks/pix`, body, { headers });
  if (wh.status !== 204) {
    const code = (wh.body.match(/"code":"([^"]+)"/) || [])[1] || '?';
    console.log(`WEBHOOK status=${wh.status} code=${code}`);
  }
}
