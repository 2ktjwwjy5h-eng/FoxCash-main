import type { Storefront } from './types';
import { FoxCashClient } from './foxcash-client';

/**
 * Storefront com cache em memória de 30s (Spec 52) — o bot é stateless, mas o
 * storefront (Specs 48–51) muda pouco (só quando o lojista edita a loja no
 * portal); o cache curto evita bater na API a cada interação sem atrasar a
 * propagação de edições. Após um erro, o cache é descartado (o próximo acesso
 * re-tenta a API).
 */
export class StorefrontCache {
  private cached: Storefront | null = null;
  private cachedAt = 0;

  constructor(
    private readonly client: FoxCashClient,
    private readonly ttlMs = 30_000,
    private readonly now: () => number = Date.now,
  ) {}

  /** Storefront fresco (ou com cache válido) — nunca devolve estado velho demais. */
  async get(): Promise<Storefront> {
    if (this.cached && this.now() - this.cachedAt <= this.ttlMs) {
      return this.cached;
    }
    const fresh = await this.client.getStorefront();
    this.cached = fresh;
    this.cachedAt = this.now();
    return fresh;
  }

  /** Força refresh na próxima chamada (após erro ou edição detectada). */
  invalidate(): void {
    this.cached = null;
    this.cachedAt = 0;
  }
}
