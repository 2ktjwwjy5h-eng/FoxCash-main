import 'dotenv/config';
import { Bot, InlineKeyboard, InputFile, type Context } from 'grammy';
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
import { TelegramDeliveryExecutor } from './deliver';

/** Bot Telegram (Spec 52) — renderiza o storefront, abre cobranças e entrega. */
async function main(): Promise<void> {
  const config = loadConfig();
  const logger = createLogger(config.LOG_LEVEL);
  const token = config.TELEGRAM_BOT_TOKEN;
  if (!token) {
    logger.warn('TELEGRAM_BOT_TOKEN vazio — bot Telegram desligado (veja .env.example)');
    return;
  }
  const admins = new Set(adminIds(config.ADMIN_TELEGRAM_IDS));

  const bot = new Bot(token);
  const fox = new FoxCashClient(config.FOXCASH_BASE_URL, config.FOXCASH_API_KEY);
  const storefront = new StorefrontCache(fox);
  // Spec 150/D7 — criação de cobrança via adapter de gateway (payload FoxCash
  // com botId/termsAccepted vive no adapter, não no core).
  const gateway = new FoxCashGatewayClient({
    client: fox,
    botId: config.FOXCASH_BOT_ID || undefined,
  });
  const sales = new SalesMachine(gateway, storefront);
  const executor = new TelegramDeliveryExecutor(bot, storefront, logger);
  // `delivered` (Spec 51): após a entrega concluir, envia a mensagem da etapa.
  const actions = new DeliveryActions(fox, executor, logger, undefined, 5, async (delivery) => {
    const sf = await storefront.get();
    const text = renderMessage(sf.messages, 'delivered', await executor.vars(delivery));
    if (!text) {
      return;
    }
    try {
      await bot.api.sendMessage(delivery.platformUserId, text);
    } catch (err) {
      logger.warn('envio de delivered falhou', { deliveryId: delivery.id, error: String(err) });
    }
  });
  const scheduler = new Scheduler(fox, actions, 'TELEGRAM', {
    send: async (message) => {
      for (const recipient of message.recipients) {
        try {
          await bot.api.sendMessage(recipient, message.text);
        } catch (err) {
          logger.warn('envio de programada falhou', { error: String(err) });
        }
      }
    },
  }, config.DELIVERY_POLL_MS, logger);

  const webhook = new WebhookServer({
    secret: config.WEBHOOK_SECRET,
    client: fox,
    deliveryActions: actions,
    logger,
  });

  bot.command('start', async (ctx) => {
    await showMenu(ctx);
  });

  bot.command('comprar', async (ctx) => {
    await showMenu(ctx);
  });

  bot.command('admin', async (ctx) => {
    if (!admins.has(String(ctx.from?.id))) {
      await ctx.reply('Sem permissao.');
      return;
    }
    const pending = await fox.pendingDeliveries();
    const mine = pending.filter((d) => d.platform === 'TELEGRAM');
    await ctx.reply(`Pendentes: ${mine.length}`);
  });

  bot.callbackQuery(/^buy:(.+)$/, async (ctx) => {
    try {
      const planId = ctx.match[1];
      await ctx.answerCallbackQuery();
      const userId = String(ctx.from?.id ?? '');
      const { plan, purchase } = await sales.openSale({
        planId,
        platform: 'TELEGRAM',
        platformUserId: userId,
        termsAccepted: true, // Spec 150 — aceite na confirmação da compra
      });
      const sf = await storefront.get();
      const awaiting = renderMessage(sf.messages, 'awaiting_payment', {
        nome: ctx.from?.first_name,
        plano: plan.name,
      });
      const qr = await qrPngFromPurchase(purchase);
      await ctx.replyWithPhoto(new InputFile(qr, 'qrcode.png'), {
        caption: awaiting ?? `Pagamento PIX de R$ ${formatPrice(plan.price)} para ${plan.name}`,
      });
      await ctx.reply(`**PIX Copia e Cola**\n\`${purchase.pixCopyPaste}\``, {
        parse_mode: 'Markdown',
      });
    } catch (err) {
      logger.warn('falha ao abrir compra', { error: String(err) });
      await ctx.reply('Ops, nao consegui abrir a cobranca. Tente de novo em instantes.').catch(
        () => undefined,
      );
    }
  });

  /** `/comprar` — cards dos planos + menu inline (Specs 49/50). */
  async function showMenu(ctx: Context): Promise<void> {
    const sf = await storefront.get();
    const welcome = renderMessage(sf.messages, 'welcome', {
      nome: ctx.from?.first_name,
    });
    const keyboard = new InlineKeyboard();
    for (const plan of sf.plans) {
      keyboard.text(`${plan.name} — R$ ${formatPrice(plan.price)}`, `buy:${plan.id}`);
      keyboard.row();
    }
    await ctx.reply(welcome ?? sf.store.title ?? sf.store.name, {
      reply_markup: keyboard,
    });
  }

  await bot.api.deleteWebhook(); // polling local (dev); em prod: setWebhook
  // scheduler + webhook server ANTES do bot.start(): o long polling do grammY
  // bloqueia a promise — o que vier depois nunca roda (corrigido em teste manual).
  scheduler.start();
  webhook.listen(config.WEBHOOK_PORT);
  logger.info('webhook server escutando', { port: config.WEBHOOK_PORT });
  await bot.start();
}

function formatPrice(value: number): string {
  return value.toFixed(2).replace('.', ',');
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('Falha ao iniciar bot Telegram:', err);
  process.exit(1);
});
