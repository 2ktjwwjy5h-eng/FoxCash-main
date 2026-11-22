import { createHash } from 'node:crypto';
import type { Platform, PurchaseResponse, StorePlan } from './types';
import type { GatewayClient } from './gateway-client';
import { StorefrontCache } from './storefront';

/**
 * `buyerId` pseudônimo (Spec 19/52): sha256(platform + ':' + userId) — nunca
 * PII; o backend persiste apenas esse hash (antifraude). O destinatário real
 * (platformUserId) vai separado no `purchase` (Spec 49).
 */
export function hashBuyerId(platform: Platform, userId: string): string {
  return createHash('sha256').update(`${platform}:${userId}`).digest('hex');
}

export interface OpenSaleOptions {
  planId: string;
  platform: Platform;
  platformUserId: string;
  buyerId?: string;
  idempotencyKey?: string;
  expirationSeconds?: number;
  /** Spec 150 — envio do aceite (o canal decide; nunca automático). */
  termsAccepted?: boolean;
  /** Spec 150 — provas opcionais quando o canal expuser. */
  buyerIp?: string;
  buyerDevice?: string;
}

export interface OpenSaleResult {
  plan: StorePlan;
  purchase: PurchaseResponse;
}

/**
 * Máquina de venda (Spec 52) — passo 2 do fluxo renderizado: usuário escolhe o
 * plano → abre a cobrança no gateway (`purchase` persiste platform/
 * platformUserId — Spec 49; idempotência — Spec 28). **Stateless**: nada é
 * guardado aqui; o QR/Copia-e-Cola voltam para a plataforma renderizar e o
 * restante do fluxo (SALE_PAID → entregar) é do webhook/polling. A criação de
 * cobrança passa pela porta {@link GatewayClient} (Spec 150/D7) — o bot é
 * gateway-agnóstico; o payload específico vive no adapter.
 */
export class SalesMachine {
  constructor(
    private readonly gateway: GatewayClient,
    private readonly storefront: StorefrontCache,
    private readonly now: () => number = Date.now,
  ) {}

  /**
   * Abre a cobrança de um plano do storefront. Lança se o plano não estiver
   * ativo (desapareceu do storefront) — o chamador renderiza o erro ao usuário.
   */
  async openSale(opts: OpenSaleOptions): Promise<OpenSaleResult> {
    const storefront = await this.storefront.get();
    const plan = storefront.plans.find((p) => p.id === opts.planId);
    if (!plan) {
      throw new Error(`plano inexistente no storefront: ${opts.planId}`);
    }
    const buyerId = opts.buyerId ?? hashBuyerId(opts.platform, opts.platformUserId);
    const idempotencyKey =
      opts.idempotencyKey ?? `compra:${opts.platformUserId}:${this.now()}`;
    const purchase = await this.gateway.createCharge({
      planId: opts.planId,
      platform: opts.platform,
      platformUserId: opts.platformUserId,
      buyerId,
      idempotencyKey,
      expirationSeconds: opts.expirationSeconds,
      termsAccepted: opts.termsAccepted,
      buyerIp: opts.buyerIp,
      buyerDevice: opts.buyerDevice,
    });
    return { plan, purchase };
  }
}
