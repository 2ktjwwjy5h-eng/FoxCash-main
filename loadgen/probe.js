// Probe simples: verifica health do app na rede privada
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  iterations: 3,
};

export default function () {
  const res = http.get('http://foxcash.railway.internal:8080/actuator/health', { timeout: '15s' });
  check(res, { '200': (r) => r.status === 200 });
  console.log('PROBE status=' + res.status + ' body=' + (res.body || '').slice(0, 100));
}
