/**
 * Deduplicação de `eventId` dos webhooks (Spec 52) — **best-effort em memória**
 * (o bot é stateless; reiniciou, perdeu o cache e reprocessa um evento já
 * visto). Isso é seguro porque o **backend é idempotente** (gate de status por
 * ação, Spec 49): re-entregar não duplica efeito.
 */
export class Dedup {
  private readonly seen = new Map<string, number>();

  constructor(private readonly ttlMs = 10 * 60_000) {}

  /**
   * Registra o eventId e devolve `true` se ele é novo (não visto dentro do
   * TTL). Repetido (ou dentro do TTL) → `false` (o chamador vira no-op).
   */
  isNew(eventId: string, now = Date.now()): boolean {
    this.purge(now);
    if (this.seen.has(eventId)) {
      return false;
    }
    this.seen.set(eventId, now);
    return true;
  }

  /** Limpa entradas expiradas (chamado a cada registro — O(n) amortizado). */
  private purge(now: number): void {
    for (const [id, ts] of this.seen) {
      if (now - ts > this.ttlMs) {
        this.seen.delete(id);
      }
    }
  }

  get size(): number {
    return this.seen.size;
  }
}
