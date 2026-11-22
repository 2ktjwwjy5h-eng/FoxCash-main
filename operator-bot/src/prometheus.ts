/** Parse do /actuator/prometheus (text format) p/ p95/p99 de `!latencia`. */

export interface LatencyPercentiles {
  p95: number;
  p99: number;
  samples: number;
}

function percentile(sorted: number[], p: number): number {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

/**
 * Parseia http_server_requests_seconds (summary: _count + _sum + quantiles)
 * e calcula p95/p99 agregados (todas as séries, última hora presumida pela
 * scrape do Prometheus — o parse é sobre o dump atual).
 */
export function parseLatency(text: string): LatencyPercentiles {
  const p95: number[] = [];
  const p99: number[] = [];
  const quantile = /http_server_requests_seconds\{[^}]*quantile="([\d.]+)"[^}]*\}\s+([\d.eE+-]+)/g;
  let m: RegExpExecArray | null;
  while ((m = quantile.exec(text)) !== null) {
    const q = Number(m[1]);
    const v = Number(m[2]);
    if (q === 0.95) p95.push(v);
    if (q === 0.99) p99.push(v);
  }
  // Contagem total de amostras (soma de _count por série).
  const counts = [...text.matchAll(/http_server_requests_seconds_count[^\n]*\s+(\d+)/g)]
    .reduce((acc, x) => acc + Number(x[1]), 0);
  p95.sort((a, b) => a - b);
  p99.sort((a, b) => a - b);
  return { p95: percentile(p95, 0.95), p99: percentile(p99, 0.99), samples: counts };
}

export function formatMs(seconds: number): string {
  if (seconds <= 0) return 'n/d';
  if (seconds < 1) return `${Math.round(seconds * 1000)}ms`;
  return `${seconds.toFixed(2)}s`;
}
