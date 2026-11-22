import type { Platform, PurchaseResponse } from './types';
import { FoxCashClient } from './foxcash-client';

/**
 * Porta de gateway de pagamento (Spec 150, D7) — o bot de venda é um **cliente
 * de gateway plugável**: pode vender via FoxCash ou via gateway concorrente
 * (ex.: NyxCash). O core conversa com esta interface; o payload específico de
 * cada gateway vive no adapter (nunca no core).
 *
 * Regras (D7):
 * 1. **Payload é do adapter** — `botId`/`termsAccepted`/`buyerIp`/`buyerDevice`
 *    são conceitos FoxCash e entram no `FoxCashGatewayClient`; a interface
 *    expõe um contrato neutro de criação.
 * 2. **Assinatura é do adapter** — o receiver do webhook seleciona o validador
 *    por `gatewayType` (config/env `FOXCASH_GATEWAY_TYPE=foxcash` hoje).
 * 3. **Eventos por gateway** — `SALE_PAID` (FoxCash) mapeia para o mesmo gatilho
 *    de entrega no core.
 */
export interface GatewayClient {
  /** Cria a cobrança de um plano (contrato neutro de criação). */
  createCharge(command: CreateChargeCommand): Promise<PurchaseResponse>;
}

export interface CreateChargeCommand {
  planId: string;
  platform: Platform;
  platformUserId: string;
  buyerId?: string;
  idempotencyKey: string;
  expirationSeconds?: number;
  /** Provas de compra (Spec 150) — o canal decide quando enviar. */
  termsAccepted?: boolean;
  buyerIp?: string;
  buyerDevice?: string;
}

export interface FoxCashGatewayOptions {
  client: FoxCashClient;
  /** `FOXCASH_BOT_ID` — id do store_bot (Spec 75): atribui a venda ao bot. */
  botId?: string;
}

/**
 * Adapter FoxCash (Spec 150, D7) — monta o payload específico do gateway
 * FoxCash: `botId` + `termsAccepted` (+ `buyerIp`/`buyerDevice` quando o canal
 * expuser; o backend captura do header e aceita os campos).
 */
export class FoxCashGatewayClient implements GatewayClient {
  constructor(private readonly options: FoxCashGatewayOptions) {}

  async createCharge(command: CreateChargeCommand): Promise<PurchaseResponse> {
    return this.options.client.purchase(command.planId, {
      platform: command.platform,
      platformUserId: command.platformUserId,
      buyerId: command.buyerId,
      idempotencyKey: command.idempotencyKey,
      expirationSeconds: command.expirationSeconds,
      botId: this.options.botId,
      termsAccepted: command.termsAccepted,
      buyerIp: command.buyerIp,
      buyerDevice: command.buyerDevice,
    });
  }
}
