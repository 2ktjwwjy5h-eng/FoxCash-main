import { describe, expect, it } from 'vitest';
import { parseLatency, formatMs } from '../src/prometheus.js';
import { validIp } from '../src/cloudflare.js';

const FIXTURE = `# HELP http_server_requests_seconds
http_server_requests_seconds_count{method="GET",uri="/api/v1/portal/me",status="200"} 10
http_server_requests_seconds_sum{method="GET",uri="/api/v1/portal/me",status="200"} 1.5
http_server_requests_seconds_count{method="POST",uri="/api/v1/foxcash/pix/cob",status="201"} 30
http_server_requests_seconds_sum{method="POST",uri="/api/v1/foxcash/pix/cob",status="201"} 12.0
http_server_requests_seconds{method="GET",uri="/api/v1/portal/me",status="200",quantile="0.5"} 0.05
http_server_requests_seconds{method="GET",uri="/api/v1/portal/me",status="200",quantile="0.95"} 0.2
http_server_requests_seconds{method="GET",uri="/api/v1/portal/me",status="200",quantile="0.99"} 0.5
http_server_requests_seconds{method="POST",uri="/api/v1/foxcash/pix/cob",status="201",quantile="0.95"} 0.8
http_server_requests_seconds{method="POST",uri="/api/v1/foxcash/pix/cob",status="201",quantile="0.99"} 1.2
`;

describe('parseLatency', () => {
  it('agrega p95/p99 e soma as amostras', () => {
    const l = parseLatency(FIXTURE);
    expect(l.samples).toBe(40);
    // p95 = max(0.2, 0.8); p99 = max(0.5, 1.2)
    expect(l.p95).toBeCloseTo(0.8);
    expect(l.p99).toBeCloseTo(1.2);
  });

  it('retorna 0 sem dados', () => {
    expect(parseLatency('nada')).toEqual({ p95: 0, p99: 0, samples: 0 });
  });
});

describe('formatMs', () => {
  it('formata ms e segundos', () => {
    expect(formatMs(0)).toBe('n/d');
    expect(formatMs(0.25)).toBe('250ms');
    expect(formatMs(1.5)).toBe('1.50s');
  });
});

describe('validIp', () => {
  it('aceita IPv4/IPv6 válidos e rejeita inválidos', () => {
    expect(validIp('192.168.1.1')).toBe(true);
    expect(validIp('2001:db8::1')).toBe(true);
    expect(validIp('999.1.1.1')).toBe(false);
    expect(validIp('abc')).toBe(false);
    expect(validIp('1.2.3')).toBe(false);
  });
});
