import express, { type Express, type Request, type Response } from 'express';
import type { WebhookEnvelope, SaleEventData } from './types';
import { verify } from './signature';
import { Dedup } from './dedup';
import { FoxCashClient, FoxCashApiError } from './foxcash-client';
import { DeliveryActions } from './delivery-actions';
import { createLogger, type Logger } from './logger';

/**
 * Servidor de webhook de saída (Spec 35/52 + Spec 148/150) — recebe
 * `POST /webhooks/foxcash` do backend:
 * - **raw body preservado** e assinatura `X-FoxCash-Signature` validada em
 *   tempo constante (HMAC-SHA256 com o segredo do lojista; formatos **v1**
 *   hex puro e **v2** `t=,v1=` — janela anti-replay, Spec 148) → 401 se
 *   ausente/inválida;
 * - **dedup** por `X-FoxCash-Event-Id` (best-effort em memória — Spec 52; o
 *   backend é idempotente, re-entregar não duplica efeito);
 * - **dispatcher**: `SALE_PAID` → busca a entrega por transação e executa as
 *   ações (falhas → retry com backoff); `SALE_PAID` de cobrança avulsa (404,
 *   sem delivery) → no-op silencioso (204); demais eventos → no-op logado.
 *
 * Resposta 204 em sucesso (inclusive replay dedupado — o backend trata 2xx
 * como entrega concluída); 500 em falha de processamento → o backend re-tenta
 * com backoff (Spec 35).
 */
export interface WebhookServerDeps {
  /** Segredo do webhook do lojista (env `WEBHOOK_SECRET`). */
  secret: string;
  client: FoxCashClient;
  deliveryActions: DeliveryActions;
  dedup?: Dedup;
  logger?: Logger;
}

export class WebhookServer {
  readonly app: Express;
  private readonly dedup: Dedup;
  private readonly logger: Logger;

  constructor(private readonly deps: WebhookServerDeps) {
    this.dedup = deps.dedup ?? new Dedup();
    this.logger = deps.logger ?? createLogger();
    this.app = express();
    this.app.use(express.json({ limit: '1mb', verify: captureRawBody }));
    this.app.post('/webhooks/foxcash', (req, res) => {
      void this.handle(req, res);
    });
  }

  /** Sobe o servidor na porta (default `WEBHOOK_PORT`, 3000). */
  listen(port = 3000): ReturnType<Express['listen']> {
    return this.app.listen(port);
  }

  private async handle(req: Request, res: Response): Promise<void> {
    const rawBody = getRawBody(req);
    const signature = req.header('x-foxcash-signature');
    if (!verify(rawBody, this.deps.secret, signature)) {
      this.logger.warn('webhook rejeitado — assinatura invalida');
      res.status(401).json({ code: 'invalid_signature' });
      return;
    }
    const eventId = req.header('x-foxcash-event-id') ?? undefined;
    if (eventId && !this.dedup.isNew(eventId)) {
      this.logger.debug('webhook duplicado — no-op', { eventId });
      res.sendStatus(204);
      return;
    }
    let envelope: WebhookEnvelope;
    try {
      envelope = req.body as WebhookEnvelope;
    } catch {
      res.status(400).json({ code: 'invalid_payload' });
      return;
    }
    try {
      await this.dispatch(envelope);
      res.sendStatus(204);
    } catch (err) {
      this.logger.error('falha ao processar webhook', {
        eventId,
        type: envelope.type,
        error: String(err),
      });
      res.status(500).json({ code: 'processing_error' });
    }
  }

  /** Dispatcher (Spec 35/52): SALE_PAID dispara a entrega; demais → no-op. */
  private async dispatch(envelope: WebhookEnvelope): Promise<void> {
    switch (envelope.type) {
      case 'SALE_PAID': {
        const data = envelope.data as unknown as SaleEventData;
        let delivery;
        try {
          delivery = await this.deps.client.deliveryByTransaction(data.transactionId);
        } catch (err) {
          // Cobrança avulsa (Spec 19) não tem delivery — o SALE_PAID é apenas o
          // aviso ao lojista. 404 → no-op silencioso (204), sem gerar retries.
          if (err instanceof FoxCashApiError && err.isNotFound) {
            this.logger.info('SALE_PAID sem delivery (cobranca avulsa)', {
              transactionId: data.transactionId,
            });
            return;
          }
          throw err;
        }
        const { failures } = await this.deps.deliveryActions.processDelivery(delivery);
        if (failures > 0) {
          this.deps.deliveryActions.enqueueRetry(delivery.id);
        }
        return;
      }
      default:
        this.logger.info('evento sem acao no bot', { type: envelope.type });
    }
  }
}

// ---------------------------------------------------------------------------
// raw body (a assinatura cobre o corpo exatamente como enviado)
// ---------------------------------------------------------------------------

function captureRawBody(
  req: Request,
  _res: Response,
  buf: Buffer,
): void {
  (req as Request & { rawBody?: string }).rawBody = buf.toString('utf8');
}

function getRawBody(req: Request): string {
  return (req as Request & { rawBody?: string }).rawBody ?? '';
}
