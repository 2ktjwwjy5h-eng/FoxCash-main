/**
 * Tipos do contrato com a API do FoxCash (Specs 48–51) — espelham os DTOs do
 * backend. Payloads de ações/botões são tratados como **opacos** no bot
 * (nunca logados); chegados via API já decifrados (criptografados em repouso
 * no backend).
 */

export type Platform = 'DISCORD' | 'TELEGRAM';

export type ButtonAction = 'BUY' | 'PREVIEW' | 'FAQ' | 'SOCIAL' | 'LINK' | 'CLOSE';

export type MessageEvent =
  | 'welcome'
  | 'awaiting_payment'
  | 'payment_received'
  | 'delivered'
  | 'promo'
  | 'faq';

/** Identidade da loja (Spec 48) — GET /api/v1/foxcash/storefront. */
export interface Store {
  id: string;
  name: string;
  logoUrl: string | null;
  bannerUrl: string | null;
  description: string | null;
  socialLinks: { label: string; url: string }[];
  color: string | null;
  emoji: string | null;
  title: string | null;
  footer: string | null;
}

/** Botão do menu (Spec 50) — payload tipado (opaco aqui). */
export interface StoreButton {
  id: string;
  label: string;
  action: ButtonAction;
  payload: Record<string, unknown> | null;
  position: number;
}

/** Card visual do plano (Spec 49) — SEM payload de ações (nunca sai do backend). */
export interface StorePlan {
  id: string;
  name: string;
  description: string | null;
  price: number;
  imageUrl: string | null;
  embedColor: string | null;
  featured: boolean;
}

/** Mensagens por etapa (Spec 51) — event → texto (pode conter {nome}/{plano}). */
export type StoreMessages = Partial<Record<MessageEvent, string>>;

/** Storefront completo — a fonte de verdade que os bots renderizam (Spec 52). */
export interface Storefront {
  store: Store;
  buttons: StoreButton[];
  plans: StorePlan[];
  messages: StoreMessages;
}

export type DeliveryActionType =
  | 'GRANT_ROLE'
  | 'SEND_MESSAGE'
  | 'SEND_LINK'
  | 'ADD_TO_GROUP'
  | 'SEND_FILE';

export type DeliveryActionStatus = 'PENDING' | 'DONE' | 'FAILED';

export type DeliveryStatus = 'PENDING' | 'DELIVERED' | 'FAILED';

/** Ação de entrega da delivery (Spec 49) — payload em claro ao dono. */
export interface DeliveryAction {
  seq: number;
  actionType: DeliveryActionType;
  payload: Record<string, unknown>;
  status: DeliveryActionStatus;
  note: string | null;
  executedAt: string | null;
}

/** Entrega (Spec 49) — GET /api/v1/foxcash/deliveries/... */
export interface Delivery {
  id: string;
  planId: string;
  transactionId: string;
  platform: Platform;
  platformUserId: string;
  buyerIdHash: string | null;
  status: DeliveryStatus;
  createdAt: string;
  deliveredAt: string | null;
  actions: DeliveryAction[];
}

/** Compra de plano (Spec 49) — POST /api/v1/foxcash/plans/{id}/purchase. */
export interface PurchaseResponse {
  transactionId: string;
  planId: string;
  externalReference: string;
  pixQrCode: string; // base64 (PNG)
  pixCopyPaste: string; // BR Code
  status: string;
  createdAt: string;
}

/** Mensagem programada vencida (Spec 51) — GET /api/v1/foxcash/scheduled-messages. */
export interface ScheduledMessageDue {
  id: string;
  text: string;
  scheduledAt: string | null;
  cron: string | null;
  audience: 'BUYERS';
  recipients: string[];
}

/** Envelope do webhook de saída (Spec 35) — {eventId, type, merchantId, createdAt, data}. */
export interface WebhookEnvelope<T = Record<string, unknown>> {
  eventId: string;
  type: string;
  merchantId: string;
  createdAt: string;
  data: T;
}

/** data do SALE_PAID/SALE_REFUNDED (Spec 35): transactionId + amount + paidAt. */
export interface SaleEventData {
  transactionId: string;
  amount: number;
  paidAt: string | null;
}
