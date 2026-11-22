import 'dotenv/config';
import {
  Client,
  GatewayIntentBits,
  Events,
  ActivityType,
  EmbedBuilder,
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  type Interaction,
  type ButtonInteraction,
  type ChatInputCommandInteraction,
} from 'discord.js';
import {
  loadConfig,
  adminIds,
  createLogger,
  FoxCashClient,
  StorefrontCache,
  SalesMachine,
  FoxCashGatewayClient,
  DeliveryActions,
  Scheduler,
  WebhookServer,
  renderMessage,
  qrPngFromPurchase,
} from '@foxcash/bot-core';
import { DiscordDeliveryExecutor, planEmbed } from './deliver';

/** Bot Discord (Spec 52) — renderiza o storefront, abre cobranças e entrega. */
async function main(): Promise<void> {
  const config = loadConfig();
  const logger = createLogger(config.LOG_LEVEL);
  const token = config.DISCORD_TOKEN;
  if (!token) {
    logger.warn('DISCORD_TOKEN vazio — bot Discord desligado (veja .env.example)');
    return;
  }
  const admins = new Set(adminIds(config.ADMIN_DISCORD_IDS));

  const client = new Client({
    intents: [GatewayIntentKeys.Guilds, GatewayIntentKeys.GuildMembers],
  });

  const fox = new FoxCashClient(config.FOXCASH_BASE_URL, config.FOXCASH_API_KEY);
  const storefront = new StorefrontCache(fox);
  // Spec 150/D7 — criação de cobrança via adapter de gateway.
  const gateway = new FoxCashGatewayClient({
    client: fox,
    // botId próprio (FOXCASH_BOT_ID_DISCORD) — fallback para o genérico
    botId: config.FOXCASH_BOT_ID_DISCORD || config.FOXCASH_BOT_ID || undefined,
  });
  const sales = new SalesMachine(gateway, storefront);
  const executor = new DiscordDeliveryExecutor(client, config.GUILD_ID, storefront, logger);
  // `delivered` (Spec 51): após a entrega concluir (todas as ações DONE), envia
  // a mensagem da etapa ao comprador — variáveis {nome}/{plano} resolvidas.
  const actions = new DeliveryActions(fox, executor, logger, undefined, 5, async (delivery) => {
    const sf = await storefront.get();
    const text = renderMessage(sf.messages, 'delivered', await executor.vars(delivery));
    if (!text) {
      return;
    }
    try {
      const user = await client.users.fetch(delivery.platformUserId);
      await user.send(text);
    } catch (err) {
      logger.warn('envio de delivered falhou', { deliveryId: delivery.id, error: String(err) });
    }
  });
  const scheduler = new Scheduler(fox, actions, 'DISCORD', {
    send: async (message) => {
      for (const recipient of message.recipients) {
        try {
          const user = await client.users.fetch(recipient);
          await user.send(message.text);
        } catch (err) {
          logger.warn('envio de programada falhou', { error: String(err) });
        }
      }
    },
  }, config.DELIVERY_POLL_MS, logger);

  const webhook = new WebhookServer({
    // Segredo próprio (WEBHOOK_SECRET_DISCORD) — separado do Telegram por porta.
    secret: config.WEBHOOK_SECRET_DISCORD || config.WEBHOOK_SECRET,
    client: fox,
    deliveryActions: actions,
    logger,
  });

  client.on(Events.ClientReady, (c) => {
    logger.info('discord bot pronto', { user: c.user.tag });
    c.user.setActivity('/comprar', { type: ActivityType.Listening });
    scheduler.start();
  });

  client.on(Events.InteractionCreate, (interaction) => {
    void handleInteraction(interaction).catch((err) => {
      logger.error('falha na interacao', { error: String(err) });
      if (interaction.isRepliable() && !interaction.replied) {
        void interaction.reply({ content: 'Ops, algo deu errado. Tente de novo.', ephemeral: true });
      }
    });
  });

  async function handleInteraction(interaction: Interaction): Promise<void> {
    if (interaction.isChatInputCommand() && interaction.commandName === 'comprar') {
      await showMenu(interaction);
      return;
    }
    if (interaction.isChatInputCommand() && interaction.commandName === 'admin') {
      await handleAdmin(interaction);
      return;
    }
    if (interaction.isButton()) {
      await handleButton(interaction);
    }
  }

  /** `/comprar` — renderiza os cards dos planos + menu (Specs 49/50). */
  async function showMenu(interaction: ChatInputCommandInteraction): Promise<void> {
    const sf = await storefront.get();
    const embeds = sf.plans.map((plan) =>
      planEmbed(plan.name, plan.price, plan.description, plan.embedColor, plan.imageUrl, sf.store.footer),
    );
    const rows: ActionRowBuilder<ButtonBuilder>[] = [];
    for (let i = 0; i < sf.plans.length; i += 5) {
      const row = new ActionRowBuilder<ButtonBuilder>();
      for (const plan of sf.plans.slice(i, i + 5)) {
        row.addComponents(
          new ButtonBuilder()
            .setStyle(ButtonStyle.Primary)
            .setLabel(`${plan.name} — R$ ${formatPrice(plan.price)}`)
            .setCustomId(`buy:${plan.id}`),
        );
      }
      rows.push(row);
    }
    const welcome = renderMessage(sf.messages, 'welcome', { nome: interaction.user.username });
    await interaction.reply({
      content: welcome ?? sf.store.title ?? sf.store.name,
      embeds: embeds.length > 0 ? embeds : undefined,
      components: rows.length > 0 ? rows : undefined,
    });
  }

  /** Botão `buy:<planId>` — abre a cobrança (SalesMachine) e envia o QR. */
  async function handleButton(interaction: ButtonInteraction): Promise<void> {
    if (!interaction.customId.startsWith('buy:')) {
      return;
    }
    const planId = interaction.customId.slice(4);
    await interaction.deferReply({ ephemeral: true });
    const { plan, purchase } = await sales.openSale({
      planId,
      platform: 'DISCORD',
      platformUserId: interaction.user.id,
      termsAccepted: true, // Spec 150 — aceite na confirmação da compra
    });
    const sf = await storefront.get();
    const awaiting = renderMessage(sf.messages, 'awaiting_payment', {
      nome: interaction.user.username,
      plano: plan.name,
    });
    const qr = await qrPngFromPurchase(purchase);
    await interaction.editReply({
      content: awaiting ?? `Pagamento PIX de R$ ${formatPrice(plan.price)} para ${plan.name}`,
      files: [{ attachment: qr, name: 'qrcode.png' }],
      embeds: [
        new EmbedBuilder()
          .setTitle('PIX Copia e Cola')
          .setDescription(purchase.pixCopyPaste)
          .setColor(sf.store.color && /^#[0-9a-fA-F]{6}$/.test(sf.store.color) ? (sf.store.color as `#${string}`) : null),
      ],
    });
  }

  /** `/admin status` — ACL (ADMIN_DISCORD_IDS): entregas pendentes. */
  async function handleAdmin(interaction: ChatInputCommandInteraction): Promise<void> {
    if (!admins.has(interaction.user.id)) {
      await interaction.reply({ content: 'Sem permissao.', ephemeral: true });
      return;
    }
    const pending = await fox.pendingDeliveries();
    const mine = pending.filter((d) => d.platform === 'DISCORD');
    await interaction.reply({
      content: `Pendentes: ${mine.length}`,
      ephemeral: true,
    });
  }

  await client.login(token);
  // Porta própria (WEBHOOK_PORT_DISCORD) para coexistir com o Telegram (3000).
  webhook.listen(config.WEBHOOK_PORT_DISCORD);
  logger.info('webhook server escutando', { port: config.WEBHOOK_PORT_DISCORD });
}

function formatPrice(value: number): string {
  return value.toFixed(2).replace('.', ',');
}

/** Intents usados pelo bot (Guilds + GuildMembers p/ grant_role). */
const GatewayIntentKeys = {
  Guilds: GatewayIntentBits.Guilds,
  GuildMembers: GatewayIntentBits.GuildMembers,
};

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('Falha ao iniciar bot Discord:', err);
  process.exit(1);
});
