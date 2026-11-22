import cron from 'node-cron';
import type { CommandCtx } from './commands.js';
import { commands } from './commands.js';
import type { WebhookCategory } from './webhooks.js';

export interface ScheduledSender {
  /** Categoria do webhook de destino (default: summaries). */
  send(text: string, category?: WebhookCategory, opts?: { channelFallback?: boolean }): Promise<void>;
}

/**
 * Resumos periódicos (Spec 67): diário (09:00), semanal (seg 09:00) e alerta
 * de anomalia (vendas/hora < 50% da média — a cada 15min, throttle 1/h).
 */
export function startSchedulers(ctx: CommandCtx, sender: ScheduledSender, opts: {
  dailyCron?: string;
  weeklyCron?: string;
  anomalyCron?: string;
} = {}): () => void {
  const jobs: cron.ScheduledTask[] = [];
  let lastAnomalyAt = 0;

  const buildDaily = () => [
    `📊 **Resumo diário FoxCash**`,
    awaitSafe('vendas', '', ctx),
    awaitSafe('lojistas', '', ctx),
    awaitSafe('alertas', '', ctx),
    awaitSafe('saude', '', ctx),
  ].join('\n');

  const buildWeekly = () => [
    `🗓️ **Resumo semanal FoxCash**`,
    `- Vendas (7d): ${summarySales(ctx)}`,
    awaitSafe('chargebacks', '', ctx),
    awaitSafe('top5', '', ctx),
    awaitSafe('reconciliacao', '', ctx),
  ].join('\n');

  jobs.push(cron.schedule(opts.dailyCron ?? process.env.SCHED_DAILY_CRON ?? '0 9 * * *',
    () => sender.send(buildDaily(), 'summaries').catch((e) => console.error('daily:', e))));

  jobs.push(cron.schedule(opts.weeklyCron ?? process.env.SCHED_WEEKLY_CRON ?? '0 9 * * 1',
    () => sender.send(buildWeekly(), 'summaries').catch((e) => console.error('weekly:', e))));

  jobs.push(cron.schedule(opts.anomalyCron ?? '*/15 * * * *', async () => {
    const now = Date.now();
    if (now - lastAnomalyAt < 60 * 60 * 1000) return; // throttle 1/h
    try {
      const [current, baseline] = await Promise.all([
        ctx.db.salesWindow(1),
        ctx.db.salesWindow(24),
      ]);
      const avgPerHour = baseline.count / 24;
      if (avgPerHour > 0 && current.count < avgPerHour * ctx.config.anomalyThreshold) {
        lastAnomalyAt = now;
        // Anomalia é estrita: SOMENTE pulsação financeira (channelFallback:false)
        // — nunca cai em outro canal de texto (pedido do usuário 2026-08-10).
        await sender.send(`🚨 **Anomalia**: ${current.count} vendas na última hora vs média ${avgPerHour.toFixed(0)}/h (${Math.round((current.count / avgPerHour) * 100)}% da média).`, 'finPulse', { channelFallback: false });
      }
    } catch (e) {
      console.error('anomaly:', e);
    }
  }));

  return () => jobs.forEach((j) => j.stop());
}

async function summarySales(ctx: CommandCtx): Promise<string> {
  try {
    const s = await ctx.db.salesWindow(7 * 24);
    return `- Vendas (7d): ${s.count} aprovadas · volume ${s.amount.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })}`;
  } catch {
    return '- Vendas (7d): indisponível';
  }
}

async function awaitSafe(cmd: string, args: string, ctx: CommandCtx): Promise<string> {
  try {
    return await commands[cmd](args, ctx);
  } catch {
    return `- ${cmd}: indisponível`;
  }
}
