import {
  Client,
  EmbedBuilder,
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
} from 'discord.js';
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
 * Executor de ações de entrega do Discord (Spec 52, Design §3):
 * - `grant_role` → `member.roles.add(roleId)` (exige `GUILD_ID`);
 * - `add_to_group` → **FAILED `unsupported`** (sem suporte no Discord nesta
 *   versão);
 * - `send_message` → DM com texto (variáveis `{nome}`/`{plano}`);
 * - `send_link` → DM com embed + botão de link;
 * - `send_file` → DM com arquivo (fetch do `fileUrl`).
 *
 * Payload inválido → FAILED `invalid_payload`. Nenhum payload é logado.
 */
export class DiscordDeliveryExecutor implements DeliveryActionExecutor {
  constructor(
    private readonly client: Client,
    private readonly guildId: string | undefined,
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
        return this.grantRole(delivery, view.roleId);
      case 'ADD_TO_GROUP':
        return { ok: false, note: 'unsupported' };
      case 'SEND_MESSAGE':
        return this.sendMessage(delivery, view.text);
      case 'SEND_LINK':
        return this.sendLink(delivery, view.url, view.label);
      case 'SEND_FILE':
        return this.sendFile(delivery, view.fileUrl);
    }
  }

  private async grantRole(delivery: Delivery, roleId: string): Promise<ActionResult> {
    if (!this.guildId) {
      return { ok: false, note: 'GUILD_ID nao configurado' };
    }
    try {
      const guild = this.client.guilds.cache.get(this.guildId);
      if (!guild) {
        return { ok: false, note: 'guild_nao_encontrada' };
      }
      const member = await guild.members.fetch(delivery.platformUserId);
      await member.roles.add(roleId);
      return { ok: true };
    } catch (err) {
      this.logger.warn('grant_role falhou', { deliveryId: delivery.id, error: String(err) });
      return { ok: false, note: 'grant_role_falhou' };
    }
  }

  private async sendMessage(delivery: Delivery, text: string): Promise<ActionResult> {
    const vars = await this.vars(delivery);
    try {
      const user = await this.client.users.fetch(delivery.platformUserId);
      await user.send(renderTemplate(text, vars));
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
      const user = await this.client.users.fetch(delivery.platformUserId);
      const row = new ActionRowBuilder<ButtonBuilder>().addComponents(
        new ButtonBuilder()
          .setStyle(ButtonStyle.Link)
          .setLabel(label ?? 'Abrir')
          .setURL(url),
      );
      await user.send({
        content: label ? undefined : url,
        components: [row],
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
      const user = await this.client.users.fetch(delivery.platformUserId);
      await user.send({ files: [{ attachment: buffer, name: filename }] });
      return { ok: true };
    } catch (err) {
      this.logger.warn('send_file falhou', { deliveryId: delivery.id, error: String(err) });
      return { ok: false, note: 'send_file_falhou' };
    }
  }

  /** Variáveis de template `{nome}`/`{plano}` (Spec 51) — nome = username do Discord. */
  async vars(delivery: Delivery): Promise<{ nome: string; plano: string }> {
    let nome = '';
    try {
      const user = await this.client.users.fetch(delivery.platformUserId);
      nome = user.username;
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

/** Embutido do card do plano (Spec 49) — cor + imagem + descrição da loja. */
export function planEmbed(
  planName: string,
  price: number,
  description: string | null,
  color: string | null,
  imageUrl: string | null,
  footer?: string | null,
): EmbedBuilder {
  const embed = new EmbedBuilder()
    .setTitle(planName)
    .setDescription(description ?? null)
    .addFields({ name: 'Preço', value: `R$ ${price.toFixed(2).replace('.', ',')}`, inline: true });
  if (color && /^#[0-9a-fA-F]{6}$/.test(color)) {
    embed.setColor(color as `#${string}`);
  }
  if (imageUrl) {
    embed.setImage(imageUrl);
  }
  if (footer) {
    embed.setFooter({ text: footer });
  }
  return embed;
}
