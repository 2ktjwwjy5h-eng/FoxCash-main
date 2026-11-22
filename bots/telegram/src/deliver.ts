import { Bot, InlineKeyboard, InputFile } from 'grammy';
import type {
  Delivery,
  DeliveryAction,
  ActionResult,
  DeliveryActionExecutor,
  StorefrontCache,
} from '@foxcash/bot-core';
import { interpretAction, renderTemplate } from '@foxcash/bot-core';
import { createLogger, type Logger } from '@foxcash/bot-core';

/**
 * Executor de ações de entrega do Telegram (Spec 52, Design §3):
 * - `grant_role` → **FAILED `unsupported`** (sem suporte no Telegram);
 * - `add_to_group` → envia o link do grupo (o payload `groupId` é o link/chat
 *   de convite — documenta o convite ao comprador);
 * - `send_message` → DM com texto (variáveis `{nome}`/`{plano}`);
 * - `send_link` → DM com link (botão inline);
 * - `send_file` → DM com arquivo (fetch do `fileUrl`).
 *
 * Payload inválido → FAILED `invalid_payload`. Nenhum payload é logado.
 */
export class TelegramDeliveryExecutor implements DeliveryActionExecutor {
  constructor(
    private readonly bot: Bot,
    private readonly storefront: StorefrontCache,
    private readonly logger: Logger = createLogger(),
  ) {}

  async execute(delivery: Delivery, action: DeliveryAction): Promise<ActionResult> {
    const view = interpretAction(action);
    if (!view) {
      return { ok: false, note: 'invalid_payload' };
    }
    switch (view.actionType) {
      case 'GRANT_ROLE':
        return { ok: false, note: 'unsupported' };
      case 'ADD_TO_GROUP':
        return this.addToGroup(delivery, view.groupId);
      case 'SEND_MESSAGE':
        return this.sendMessage(delivery, view.text);
      case 'SEND_LINK':
        return this.sendLink(delivery, view.url, view.label);
      case 'SEND_FILE':
        return this.sendFile(delivery, view.fileUrl);
    }
  }

  private async addToGroup(delivery: Delivery, groupId: string): Promise<ActionResult> {
    try {
      await this.bot.api.sendMessage(
        delivery.platformUserId,
        `Você foi convidado para o grupo VIP:\n${groupId}`,
      );
      return { ok: true };
    } catch (err) {
      this.logger.warn('add_to_group falhou', { deliveryId: delivery.id, error: String(err) });
      return { ok: false, note: 'add_to_group_falhou' };
    }
  }

  private async sendMessage(delivery: Delivery, text: string): Promise<ActionResult> {
    const vars = await this.vars(delivery);
    try {
      await this.bot.api.sendMessage(delivery.platformUserId, renderTemplate(text, vars));
      return { ok: true };
    } catch (err) {
      this.logger.warn('send_message falhou', { deliveryId: delivery.id, error: String(err) });
      return { ok: false, note: 'send_message_falhou' };
    }
  }

  private async sendLink(
    delivery: Delivery,
    url: string,
    label?: string,
  ): Promise<ActionResult> {
    try {
      const keyboard = new InlineKeyboard().url(label ?? 'Abrir', url);
      await this.bot.api.sendMessage(delivery.platformUserId, label ? url : url, {
        reply_markup: keyboard,
      });
      return { ok: true };
    } catch (err) {
      this.logger.warn('send_link falhou', { deliveryId: delivery.id, error: String(err) });
      return { ok: false, note: 'send_link_falhou' };
    }
  }

  private async sendFile(delivery: Delivery, fileUrl: string): Promise<ActionResult> {
    try {
      const res = await fetch(fileUrl);
      if (!res.ok) {
        return { ok: false, note: 'file_download_falhou' };
      }
      const buffer = Buffer.from(await res.arrayBuffer());
      const filename = fileUrl.split('/').pop() ?? 'arquivo';
      await this.bot.api.sendDocument(
        delivery.platformUserId,
        new InputFile(buffer, filename),
      );
      return { ok: true };
    } catch (err) {
      this.logger.warn('send_file falhou', { deliveryId: delivery.id, error: String(err) });
      return { ok: false, note: 'send_file_falhou' };
    }
  }

  /** Variáveis de template `{nome}`/`{plano}` (Spec 51) — nome = username do Telegram. */
  async vars(delivery: Delivery): Promise<{ nome: string; plano: string }> {
    let nome = '';
    try {
      const chat = await this.bot.api.getChat(delivery.platformUserId);
      nome = chat.first_name ?? '';
    } catch {
      // usuário não encontrado — nome vazio (não loga o ID)
    }
    let plano = '';
    try {
      const storefront = await this.storefront.get();
      plano = storefront.plans.find((p) => p.id === delivery.planId)?.name ?? '';
    } catch {
      // storefront indisponível — plano vazio
    }
    return { nome, plano };
  }
}
