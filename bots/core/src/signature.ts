import { createHmac, timingSafeEqual } from 'node:crypto';

/**
 * Assinatura do webhook de saída (Spec 35 + Spec 148/150).
 *
 * Dois formatos aceitos (transição v1 → v2):
 * - **v1 (legado):** `X-FoxCash-Signature: <hex>` — HMAC-SHA256 do RAW BODY.
 * - **v2 (Spec 148):** `t=<unix>,v1=<hex>` — HMAC-SHA256 de `"<t>.<rawBody>"`;
 *   o `t` (epoch seconds) rejeita replay fora da janela (default 5 min).
 *
 * Detecção pelo prefixo `t=`. Comparação em tempo constante (timingSafeEqual).
 * O bot valida o que o backend envia; durante a transição, ambos são aceitos.
 */
export const DEFAULT_MAX_AGE_MS = 5 * 60 * 1000; // 5 min (Spec 148)

export interface VerifyOptions {
  /** Janela anti-replay em ms (default 5 min). */
  maxAgeMs?: number;
  /** Agora em ms (injetável p/ teste). */
  nowMs?: number;
}

/** Assina no formato v1 (legado) — hex puro do HMAC do raw body. */
export function sign(rawBody: string, secret: string): string {
  return createHmac('sha256', secret).update(rawBody, 'utf8').digest('hex');
}

/** Assina no formato v2 (Spec 148) — `t=<unix>,v1=<hex>` (HMAC de `"<t>.<body>"`). */
export function signV2(rawBody: string, secret: string, nowSec = Math.floor(Date.now() / 1000)): string {
  const mac = createHmac('sha256', secret).update(`${nowSec}.${rawBody}`, 'utf8').digest('hex');
  return `t=${nowSec},v1=${mac}`;
}

function constantTimeEqual(a: string, b: string): boolean {
  const ba = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  if (ba.length !== bb.length) {
    return false;
  }
  return timingSafeEqual(ba, bb);
}

/**
 * Verifica a assinatura do raw body (v1 ou v2), em tempo constante.
 * v2 com `t` fora da janela → false (anti-replay).
 */
export function verify(
  rawBody: string,
  secret: string,
  signature: string | undefined,
  options: VerifyOptions = {},
): boolean {
  if (!signature || !rawBody) {
    return false;
  }
  const v2 = /^t=(\d+),v1=([0-9a-f]{64})$/.exec(signature);
  if (v2) {
    const [, t, mac] = v2;
    const tSec = Number(t);
    if (!Number.isFinite(tSec)) {
      return false;
    }
    const maxAgeMs = options.maxAgeMs ?? DEFAULT_MAX_AGE_MS;
    const nowMs = options.nowMs ?? Date.now();
    if (nowMs - tSec * 1000 > maxAgeMs || tSec * 1000 > nowMs + 60_000) {
      // velho demais (replay) ou `t` no futuro além de 1 min (relógio)
      return false;
    }
    const expected = createHmac('sha256', secret)
      .update(`${t}.${rawBody}`, 'utf8')
      .digest('hex');
    return constantTimeEqual(expected, mac);
  }
  // v1 (legado): hex puro do HMAC do body
  if (!/^[0-9a-f]{64}$/.test(signature)) {
    return false;
  }
  const expected = sign(rawBody, secret);
  return constantTimeEqual(expected, signature);
}
