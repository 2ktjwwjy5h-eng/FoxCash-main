import type {
  Delivery,
  Platform,
  PurchaseResponse,
  ScheduledMessageDue,
  Storefront,
} from './types';

/**
 * Erro tipado da API do FoxCash — carrega o HTTP status e o code de domínio
 * (ex.: `plan_inactive`, `delivery_not_found`, `too_many_requests`).
 */
export class FoxCashApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message?: string,
  ) {
    super(message ?? `${status} ${code}`);
    this.name = 'FoxCashApiError';
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isConflict(): boolean {
    return this.status === 409;
  }

  get isRateLimited(): boolean {
    return this.status === 429;
  }
}

/**
 * Client tipado da API do FoxCash (Spec 52) — autenticado pela API key do
 * lojista (`X-Api-Key`, Spec 12/24) em TODAS as chamadas. 404/409 viram
 * {@link FoxCashApiError} (o chamador decide retry). **429 (rate limit, Spec
 * 43) re-tenta com backoff exponencial** (design §4: "respeita 429 do backend
 * — backoff no client"); esgotado, lança o erro (o chamador vira no-op ou
 * aguarda o próximo ciclo de polling). Nada de token em log — o client nunca
 * loga headers/corpos.
 */
export class FoxCashClient {
  constructor(
    private readonly baseUrl: string,
    private readonly apiKey: string,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly rateLimitRetries = 2,
    private readonly rateLimitBaseDelayMs = 500,
    private readonly sleep: (ms: number) => Promise<void> = (ms) =>
      new Promise((resolve) => setTimeout(resolve, ms)),
  ) {}

  /** Storefront completo (Specs 48–51) — identidade + planos + botões + mensagens. */
  async getStorefront(): Promise<Storefront> {
    return this.request('/api/v1/foxcash/storefront');
  }

  /** Compra de plano (Spec 49) — persiste o destinatário no backend. */
  async purchase(
    planId: string,
    body: {
      platform: Platform;
      platformUserId: string;
      buyerId?: string;
      idempotencyKey: string;
      expirationSeconds?: number;
      botId?: string; // Spec 75/150 — atribui a venda ao bot (funil/métricas)
      termsAccepted?: boolean; // Spec 150 — prova de aceite (termsAcceptedAt)
      buyerIp?: string; // Spec 150 — opcional; backend captura do header
      buyerDevice?: string; // Spec 150 — opcional; idem
    },
  ): Promise<PurchaseResponse> {
    return this.request(`/api/v1/foxcash/plans/${planId}/purchase`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  }

  /** Entregas PENDING da loja (polling — recuperação pós-falha, Spec 52). */
  async pendingDeliveries(): Promise<Delivery[]> {
    return this.request('/api/v1/foxcash/deliveries/pending');
  }

  /** Entrega de uma transação (webhook SALE_PAID). */
  async deliveryByTransaction(transactionId: string): Promise<Delivery> {
    return this.request(`/api/v1/foxcash/deliveries/by-transaction/${transactionId}`);
  }

  /** Confirma a execução de uma ação (DONE|FAILED + note) — idempotente. */
  async completeAction(
    deliveryId: string,
    seq: number,
    status: 'DONE' | 'FAILED',
    note?: string,
  ): Promise<void> {
    await this.request(`/api/v1/foxcash/deliveries/${deliveryId}/actions/${seq}/complete`, {
      method: 'POST',
      body: JSON.stringify(status === 'DONE' ? { status } : { status, note: note ?? null }),
    });
  }

  /** Reabre uma entrega FAILED (Spec 49) — volta a PENDING para reprocessar. */
  async retryDelivery(deliveryId: string): Promise<Delivery> {
    return this.request(`/api/v1/foxcash/deliveries/${deliveryId}/retry`, { method: 'POST' });
  }

  /** Mensagens programadas vencidas (Spec 51) — com recipients (em claro ao dono). */
  async scheduledMessagesDue(dueBefore: string): Promise<ScheduledMessageDue[]> {
    return this.request(
      `/api/v1/foxcash/scheduled-messages?dueBefore=${encodeURIComponent(dueBefore)}`,
    );
  }

  /** Confirma o envio de uma mensagem programada (Spec 51) — idempotente. */
  async markScheduledSent(messageId: string): Promise<void> {
    await this.request(`/api/v1/foxcash/scheduled-messages/${messageId}/sent`, { method: 'POST' });
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    for (let attempt = 0; ; attempt++) {
      const res = await this.fetchImpl(url, {
        ...init,
        headers: {
          'X-Api-Key': this.apiKey,
          'Content-Type': 'application/json',
          ...(init?.headers ?? {}),
        },
      });
      if (res.status === 429 && attempt < this.rateLimitRetries) {
        // backoff exponencial: 500ms → 1000ms (design §4 — respeita o rate limit)
        await this.sleep(this.rateLimitBaseDelayMs * 2 ** attempt);
        continue;
      }
      if (!res.ok) {
        let code = 'unknown';
        try {
          const body = (await res.json()) as { code?: string };
          code = body.code ?? 'unknown';
        } catch {
          // corpo não-JSON — mantém 'unknown'
        }
        throw new FoxCashApiError(res.status, code, res.statusText);
      }
      if (res.status === 204) {
        return undefined as T;
      }
      return (await res.json()) as T;
    }
  }
}
