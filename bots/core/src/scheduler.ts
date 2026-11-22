import type { Platform, ScheduledMessageDue } from './types';
import { FoxCashClient } from './foxcash-client';
import { DeliveryActions } from './delivery-actions';
import { createLogger, type Logger } from './logger';

/**
 * Envio de mensagem programada (Spec 51) — implementado pelo bot da plataforma
 * (discord/telegram): recebe a mensagem + recipients e envia para os IDs que
 * reconhece. **Assunção (decisão Spec 52): 1 plataforma por loja** — o backend
 * devolve recipients sem plataforma; o executor valida os IDs da sua plataforma.
 */
export interface ScheduledSender {
  send(message: ScheduledMessageDue): Promise<void>;
}

/**
 * Polling de pendências (Spec 52 §2.4) — o bot é **stateless**: a cada
 * `pollMs` (default `DELIVERY_POLL_MS`, 15s):
 * 1. `deliveries/pending` → processa as da **sua plataforma** (filtro por
 *    `delivery.platform` — o endpoint devolve todas as da loja) via
 *    {@link DeliveryActions}; falhas → retry com backoff.
 * 2. `scheduled-messages?dueBefore=now` → envia via {@link ScheduledSender} e
 *    confirma `sent` (idempotente — o backend marca SENT; replay 204).
 *
 * É o **backstop de recuperação**: após reinício, re-busca pendências no
 * backend e entrega (nada é guardado em memória como fonte de verdade).
 */
export class Scheduler {
  private timer: NodeJS.Timeout | null = null;
  private running = false;

  constructor(
    private readonly client: FoxCashClient,
    private readonly deliveryActions: DeliveryActions,
    private readonly platform: Platform,
    private readonly scheduledSender: ScheduledSender,
    private readonly pollMs = 15_000,
    private readonly logger: Logger = createLogger(),
    private readonly now: () => Date = () => new Date(),
  ) {}

  start(): void {
    if (this.timer) {
      return;
    }
    this.timer = setInterval(() => {
      this.pollOnce().catch((err) => {
        this.logger.error('falha no polling', { error: String(err) });
      });
    }, this.pollMs);
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  /** Executa um ciclo de polling (exportado para teste). */
  async pollOnce(): Promise<void> {
    if (this.running) {
      return; // evita sobreposição se o ciclo demorar mais que o intervalo
    }
    this.running = true;
    try {
      await this.pollDeliveries();
      await this.pollScheduledMessages();
    } finally {
      this.running = false;
    }
  }

  private async pollDeliveries(): Promise<void> {
    const pending = await this.client.pendingDeliveries();
    for (const delivery of pending) {
      if (delivery.platform !== this.platform) {
        continue; // entrega de outra plataforma — o outro bot cuida
      }
      const { failures } = await this.deliveryActions.processDelivery(delivery);
      if (failures > 0) {
        this.deliveryActions.enqueueRetry(delivery.id);
      }
    }
  }

  private async pollScheduledMessages(): Promise<void> {
    const dueBefore = this.now().toISOString();
    const due = await this.client.scheduledMessagesDue(dueBefore);
    for (const message of due) {
      await this.scheduledSender.send(message);
      await this.client.markScheduledSent(message.id);
    }
  }
}
