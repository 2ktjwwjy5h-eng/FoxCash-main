import type { Delivery, DeliveryAction } from './types';
import { FoxCashClient } from './foxcash-client';
import { createLogger, type Logger } from './logger';

/**
 * Resultado da execução de uma ação de entrega — `ok` + `note` opcional
 * (motivo da falha, ex.: `unsupported` para ação não suportada na plataforma).
 */
export interface ActionResult {
  ok: boolean;
  note?: string;
}

/**
 * Executor de ação de entrega (Spec 52) — implementado por plataforma
 * (discord/telegram). Recebe a delivery (destinatário em `platformUserId`) e a
 * ação e executa o efeito real: grant_role, send_message, send_link,
 * add_to_group, send_file (Design §3). Nenhum dado sensível é logado.
 */
export interface DeliveryActionExecutor {
  execute(delivery: Delivery, action: DeliveryAction): Promise<ActionResult>;
}

const DEFAULT_BACKOFF_MS = [1_000, 2_000, 5_000, 10_000];

/**
 * Orquestra a execução das ações de entrega (Spec 49/52) e o reprocessamento:
 * - cada ação PENDING é executada pelo {@link DeliveryActionExecutor} da
 *   plataforma e confirmada no backend (`completeAction` — DONE|FAILED+note,
 *   idempotente; falha em uma ação não interrompe as demais);
 * - entrega com ações FAILED é reaberta via API (`retryDelivery`) e re-executada
 *   com **backoff exponencial em memória** (1s→2s→5s→10s, máx. 5 tentativas);
 * - **stateless**: a fila é best-effort — o polling da API (scheduler) é o
 *   backstop em reinício (o backend reabre/entrega pendências).
 */
export class DeliveryActions {
  private readonly timers = new Map<string, NodeJS.Timeout>();

  constructor(
    private readonly client: FoxCashClient,
    private readonly executor: DeliveryActionExecutor,
    private readonly logger: Logger = createLogger(),
    private readonly backoffMs: number[] = DEFAULT_BACKOFF_MS,
    private readonly maxAttempts = 5,
    private readonly onDelivered?: (delivery: Delivery) => Promise<void>,
  ) {}

  /**
   * Executa todas as ações PENDING da entrega e confirma cada uma no backend.
   * Devolve o número de ações que falharam (para o chamador decidir retry).
   * Quando nenhuma ação falha, dispara `onDelivered` (Spec 51: o bot envia a
   * mensagem `delivered` ao comprador após a entrega concluída).
   */
  async processDelivery(delivery: Delivery): Promise<{ failures: number }> {
    let failures = 0;
    for (const action of delivery.actions) {
      if (action.status !== 'PENDING') {
        continue;
      }
      const result = await this.executor.execute(delivery, action);
      if (result.ok) {
        await this.client.completeAction(delivery.id, action.seq, 'DONE');
      } else {
        failures += 1;
        await this.client.completeAction(
          delivery.id,
          action.seq,
          'FAILED',
          result.note ?? 'falha na execucao',
        );
      }
    }
    if (failures === 0) {
      await this.onDelivered?.(delivery);
    }
    return { failures };
  }

  /**
   * Agenda o reprocessamento de uma entrega com ações FAILED: reabre no
   * backend (`retryDelivery`, Spec 49 — FAILED → PENDING) e re-executa.
   * `attempts` é o número da tentativa (1 = primeiro retry).
   */
  enqueueRetry(deliveryId: string, attempts = 1): void {
    if (attempts > this.maxAttempts) {
      this.logger.warn('retry esgotado — entrega permanece FAILED', { deliveryId });
      return;
    }
    const delay = this.backoffMs[attempts - 1] ?? this.backoffMs[this.backoffMs.length - 1];
    const timer = setTimeout(() => {
      this.timers.delete(deliveryId);
      this.retryNow(deliveryId, attempts).catch((err) => {
        this.logger.error('falha ao reprocessar entrega', {
          deliveryId,
          attempts,
          error: String(err),
        });
      });
    }, delay);
    this.timers.set(deliveryId, timer);
  }

  private async retryNow(deliveryId: string, attempts: number): Promise<void> {
    const delivery = await this.client.retryDelivery(deliveryId);
    const { failures } = await this.processDelivery(delivery);
    if (failures > 0) {
      this.enqueueRetry(deliveryId, attempts + 1);
    }
  }

  /** Cancela todos os retries agendados (shutdown/limpeza). */
  stop(): void {
    for (const timer of this.timers.values()) {
      clearTimeout(timer);
    }
    this.timers.clear();
  }
}
