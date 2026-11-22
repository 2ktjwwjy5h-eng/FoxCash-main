import type { DbQueries } from './db.js';
import type { RailwayClient } from './railway.js';
import type { CloudflareClient } from './cloudflare.js';
import { validIp } from './cloudflare.js';
import { formatMs, parseLatency } from './prometheus.js';
import {
  chargebackVerdict,
  fmtMoney,
  fmtNumber,
  jobsVerdict,
  latencyVerdict,
  outboxVerdict,
  reconciliationVerdict,
  salesVerdict,
  usageVerdict,
} from './format.js';

/** Erro de timeout do `AbortSignal.timeout` (DOMException com name 'TimeoutError'). */
function isTimeoutError(e: unknown): boolean {
  return (
    (e instanceof Error || (typeof e === 'object' && e !== null))
    && (e as { name?: string }).name === 'TimeoutError'
  );
}

/** Descreve um erro de fetch p/ log: nome, mensagem e `cause` (código TCP/DNS). */
function describeFetchError(e: unknown): string {
  const err = e instanceof Error ? e : new Error(String(e));
  const cause = (e as { cause?: { code?: string; message?: string } }).cause;
  return `${err.name}: ${err.message}${cause ? ` (cause: ${cause.code ?? cause.message})` : ''}`;
}

/**
 * Fetch com retry APENAS p/ erros de rede transientes. O backend redeploya a cada
 * push no main (Railway) — durante a troca de réplicas a conexão é recusada/reset
 * por segundos; o retry (500ms→1s→1.5s) absorve a janela. Status HTTP (401/500
 * etc.) NÃO re-tenta (são respostas válidas). **Timeout NÃO re-tenta**: servidor
 * de pé mas lento não fica mais rápido numa 2ª tentativa — só multiplica a espera.
 * Para endpoints pesados (ex.: lookup de IP com muitos lojistas), passe um
 * `timeoutMs` maior.
 */
export async function fetchWithRetry(
  url: string,
  init?: RequestInit,
  retries = 3,
  timeoutMs = 10_000,
): Promise<Response> {
  let lastErr: unknown;
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      return await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
    } catch (e) {
      lastErr = e;
      if (isTimeoutError(e)) throw e;
      if (attempt < retries) {
        await new Promise((r) => setTimeout(r, 500 * attempt));
      }
    }
  }
  throw lastErr;
}

export interface CommandCtx {
  db: DbQueries;
  railway: RailwayClient;
  cloudflare?: CloudflareClient;
  baseUrl: string;
  adminApiKey?: string;
  config: { anomalyThreshold: number };
  now?: () => Date;
}

export type Command = (args: string, ctx: CommandCtx) => Promise<string>;

/** Response do endpoint {@code GET /api/v1/admin/lookup/ip/{ip}} (Spec 67). */
interface IpLookupResponse {
  ip: string;
  matches: Array<{
    merchantId: string;
    name: string;
    email: string;
    status: string;
    totalSales: number;
    averageTicket: string;
    lastSaleAt: string | null;
    sales7d: number;
    revenue7d: string;
    bot: {
      storeId: string;
      storeName: string;
      activePlans: number;
      activeWebhooks: number;
      link: string | null;
    } | null;
  }>;
}

/** Emoji por status de lojista (Spec 67 — lookup de IP). */
const STATUS_EMOJI: Record<string, string> = {
  ACTIVE: '🟢',
  SUSPENDED: '🔴',
  INACTIVE: '⚪',
};

/**
 * Mascara um IP para exibição no chat — nunca expõe o endereço completo
 * (Spec 67): IPv4 mantém o último octeto; IPv6 mantém os primeiros grupos.
 * Usa `•••` (não `***`, que o Discord interpreta como markdown de negrito).
 */
export function maskIp(ip: string): string {
  if (ip.includes(':')) {
    // IPv6: mantém os grupos antes da compressão `::` (no máximo 3); sem
    // grupo inicial (ex.: `::1`) → só `…`. A interface `%eth0` é descartada.
    const [head] = ip.split('::');
    const groups = head.split(':').filter(Boolean).slice(0, 3);
    return groups.length ? `${groups.join(':')}:…` : '…';
  }
  const parts = ip.split('.');
  return parts.map((p, i) => (i === parts.length - 1 ? p : '•••')).join('.');
}

async function fetchPrometheus(ctx: CommandCtx): Promise<string | null> {
  if (!ctx.adminApiKey) return null;
  const res = await fetchWithRetry(`${ctx.baseUrl}/actuator/prometheus`, {
    headers: { 'X-Api-Key': ctx.adminApiKey },
  });
  if (!res.ok) return null;
  return res.text();
}

/** Plural simples pt-BR: singular p/ 1, plural caso contrário. */
function plural(n: number, singular: string, pluralForm: string): string {
  return n === 1 ? singular : pluralForm;
}

function timeBr(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' });
}

/** Resultado do lookup de IP: ok com dados, ou erro com texto pronto p/ o chat. */
type LookupOutcome =
  | { kind: 'ok'; data: IpLookupResponse }
  | { kind: 'error'; text: string };

/** Converte a resposta do lookup em resultado p/ o chat (auth/400/ok). */
async function handleLookupResponse(res: Response, ip: string): Promise<LookupOutcome> {
  if (res.status === 401 || res.status === 403) return { kind: 'error', text: '**!ip**: chave admin inválida ou sem permissão.' };
  if (res.status === 400) return { kind: 'error', text: `**${maskIp(ip)}**: endereço IP inválido.` };
  if (!res.ok) return { kind: 'error', text: `**!ip**: erro ${res.status} no lookup.` };
  return { kind: 'ok', data: (await res.json()) as IpLookupResponse };
}

/**
 * Consulta o endpoint admin de lookup por IP (validação + auth + erros amigáveis).
 * A rede interna (`foxcash.railway.internal`) falhou na conexão com o endpoint
 * admin mesmo com health UP (2026-08-10) — fallback p/ a URL pública do mesmo
 * deploy quando a interna lança erro de rede.
 */
async function ipLookup(ip: string, ctx: CommandCtx): Promise<LookupOutcome> {
  if (!validIp(ip)) return { kind: 'error', text: `**${maskIp(ip)}**: endereço IP inválido.` };
  if (!ctx.adminApiKey) return { kind: 'error', text: '**!ip**: indisponível (sem FOXCASH_ADMIN_API_KEY).' };

  const path = `/api/v1/admin/lookup/ip/${encodeURIComponent(ip)}`;
  const headers = { 'X-Api-Key': ctx.adminApiKey };
  // URL pública do backend p/ fallback (Railway injeta RAILWAY_SERVICE_FOXCASH_URL;
  // default = domínio público do runbook).
  const publicBase = process.env.RAILWAY_SERVICE_FOXCASH_URL
    ? `https://${process.env.RAILWAY_SERVICE_FOXCASH_URL}`
    : 'https://api.foxcash.example.com';

  // Tentativa 1: URL configurada (rede interna em produção). Timeout de 30s —
  // o retry NÃO cobre timeout (servidor lento), só falha de conexão.
  try {
    const res = await fetchWithRetry(`${ctx.baseUrl}${path}`, { headers }, 3, 30_000);
    return await handleLookupResponse(res, ip);
  } catch (e) {
    // Diagnóstico no log do Railway: tipo + cause + se o health no mesmo host
    // responde (rede interna caiu? só o endpoint admin?).
    let health = '?';
    try {
      const h = await fetchWithRetry(`${ctx.baseUrl}/actuator/health`, undefined, 1, 10_000);
      health = `HTTP ${h.status}`;
    } catch (he) {
      health = `ERR ${he instanceof Error ? he.name : he}`;
    }
    console.error(`[!ip] ${ip}: interno ${describeFetchError(e)} | health=${health}`);

    // Tentativa 2: URL pública (mesmo deploy; edge do Railway, fora do Cloudflare).
    try {
      const res = await fetchWithRetry(`${publicBase}${path}`, { headers }, 1, 30_000);
      console.error(`[!ip] ${ip}: fallback público OK`);
      return await handleLookupResponse(res, ip);
    } catch (e2) {
      console.error(`[!ip] ${ip}: público também falhou: ${describeFetchError(e2)}`);
      return {
        kind: 'error',
        text: isTimeoutError(e) || isTimeoutError(e2)
          ? '**!ip**: o backend demorou demais para responder (lookup pesado). Tente novamente em instantes.'
          : '**!ip**: falha ao consultar o backend.',
      };
    }
  }
}

/**
 * Formata o relatório de um IP (usado por `!ip` e `!vigiar`): IP mascarado,
 * lojista, faturamento da última semana, ticket, última venda e links dos bots.
 */
function formatIpReport(title: string, ip: string, data: IpLookupResponse): string {
  if (!data.matches || data.matches.length === 0) {
    return `🔍 **IP ${maskIp(ip)}**: não encontrado — nenhum lojista associado (cobrança ou trilha).`;
  }
  const lines = [title, `Endereço: ${maskIp(ip)}`, ''];
  for (const m of data.matches) {
    const emoji = STATUS_EMOJI[m.status] ?? '⚪';
    const last = m.lastSaleAt ? new Date(m.lastSaleAt).toISOString().slice(0, 10) : '—';
    lines.push(`${emoji} **${m.name}** (${m.status})`);
    lines.push(`   💰 Última semana: ${fmtMoney(Number(m.revenue7d))} (${fmtNumber(m.sales7d)} ${plural(m.sales7d, 'venda', 'vendas')}) · ticket ${fmtMoney(Number(m.averageTicket))} · última ${last}`);
    if (m.bot) {
      lines.push(`   🤖 Bot: ${m.bot.link ?? 'ativo, sem link configurado'} (${m.bot.activePlans} planos · ${m.bot.activeWebhooks} webhooks)`);
    }
  }
  lines.push('');
  lines.push(`${data.matches.length} lojista(s) associado(s) ao IP.`);
  return lines.join('\n');
}

export const commands: Record<string, Command> = {
  // ---------------- Saúde ----------------
  async saude(_a, ctx) {
    const [health, conn, outbox, jobs, deploy] = await Promise.allSettled([
      fetchWithRetry(`${ctx.baseUrl}/actuator/health`).then((r) => r.json() as any),
      ctx.db.connections(),
      ctx.db.outbox(),
      ctx.db.jobs(),
      ctx.railway.status(),
    ]);
    const lines: string[] = ['**🩺 Saúde FoxCash**', ''];
    const attention: string[] = [];

    if (health.status === 'fulfilled' && health.value?.status === 'UP') {
      lines.push('✅ **App**: UP — respondendo normalmente');
    } else {
      lines.push('🔴 **App**: DOWN — fora do ar!');
      attention.push('app fora do ar — verifique o deploy imediatamente');
    }

    if (conn.status === 'fulfilled') {
      const pct = (conn.value.active / conn.value.max) * 100;
      const v = usageVerdict(pct);
      lines.push(`${v.emoji} **Conexões DB**: ${conn.value.active}/${conn.value.max} (${Math.round(pct)}%) — ${v.label}`);
      if (v.advice) attention.push(`conexões: ${v.advice}`);
    } else {
      lines.push('⚪ **DB**: indisponível');
      attention.push('banco indisponível');
    }

    if (outbox.status === 'fulfilled') {
      const pending = outbox.value.find((x) => x.status === 'PENDING')?.count ?? 0;
      const failed = outbox.value.find((x) => x.status === 'FAILED')?.count ?? 0;
      const v = outboxVerdict(pending);
      lines.push(`${v.emoji} **Outbox (1h)**: ${fmtNumber(pending)} PENDING — ${v.label}`
        + (failed > 0 ? ` · ❌ ${fmtNumber(failed)} FAILED` : ''));
      if (v.advice) attention.push(`outbox: ${v.advice}`);
      if (failed > 0) attention.push(`outbox com ${fmtNumber(failed)} evento(s) FAILED`);
    }

    if (jobs.status === 'fulfilled') {
      const failed = jobs.value.filter((j) => j.status === 'FAILED').length;
      const v = jobsVerdict(failed, jobs.value.length);
      lines.push(`${v.emoji} **Jobs**: ${v.label}`);
      if (v.advice) attention.push(`jobs: ${v.advice}`);
    }

    if (deploy.status === 'fulfilled') {
      const d = deploy.value;
      if (d.online) {
        lines.push(`🟢 **Deploy**: online${d.deploying ? ' (deploy/building em andamento)' : ''}`
          + (d.replicas ? ` · ${d.replicas} réplicas` : ''));
      } else {
        lines.push('🔴 **Deploy**: offline');
        attention.push('deploy offline');
      }
    } else {
      lines.push('⚪ **Deploy**: indisponível (sem Railway)');
    }

    lines.push('');
    if (attention.length === 0) {
      lines.push('**Resumo**: tudo certo! 🎉 Nenhum ponto de atenção.');
    } else {
      lines.push(`**Resumo**: ${attention.length} ponto(s) de atenção:`);
      lines.push(attention.map((a) => `• ${a}`).join('\n'));
      lines.push('Dica: use `!outbox`, `!jobs` ou `!deploy` para investigar.');
    }
    return lines.join('\n');
  },
  async conexoes(_a, ctx) {
    const c = await ctx.db.connections();
    const pct = (c.active / c.max) * 100;
    const v = usageVerdict(pct);
    const lines = ['**🔌 Conexões DB**'];
    lines.push(`${v.emoji} ${c.active}/${c.max} ativas (${Math.round(pct)}%) — ${v.label}`);
    if (v.advice) lines.push(`💡 ${v.advice}`);
    return lines.join('\n');
  },
  async outbox(_a, ctx) {
    const rows = await ctx.db.outbox();
    if (rows.length === 0) return '📭 **Outbox (1h)**: nenhum evento no último período.';
    const pending = rows.find((r) => r.status === 'PENDING')?.count ?? 0;
    const failed = rows.find((r) => r.status === 'FAILED')?.count ?? 0;
    const sent = rows.find((r) => r.status === 'SENT')?.count ?? 0;
    const v = outboxVerdict(pending);
    const lines = ['**📦 Outbox (última hora)**'];
    lines.push(`${v.emoji} PENDING: ${fmtNumber(pending)} — ${v.label}`);
    if (sent > 0) lines.push(`📨 SENT: ${fmtNumber(sent)}`);
    if (failed > 0) lines.push(`❌ FAILED: ${fmtNumber(failed)}`);
    if (v.advice) lines.push(`💡 ${v.advice}`);
    if (failed > 0) lines.push('🚨 Eventos FAILED = entrega esgotada (Spec 35) — confira o webhook de saída.');
    return lines.join('\n');
  },
  async jobs(_a, ctx) {
    const rows = await ctx.db.jobs();
    if (rows.length === 0) return '🗂️ **Jobs**: nenhum registro ainda (o scheduler ainda não rodou).';
    const failed = rows.filter((j) => j.status === 'FAILED').length;
    const lines = ['**🗂️ Últimos jobs (5)**'];
    for (const j of rows) {
      const ok = j.status === 'SUCCESS';
      lines.push(`${ok ? '✅' : '❌'} ${j.job} · ${j.status} · ${timeBr(j.started_at.toISOString())}`);
    }
    if (failed === 0) {
      lines.push('🟢 Nenhuma falha — tudo certo!');
    } else {
      lines.push(`🔴 ${failed} falha(s) — atenção!`);
      lines.push('💡 confira o job no Railway (job_runs) e reexecute se necessário.');
    }
    return lines.join('\n');
  },
  async latencia(_a, ctx) {
    const text = await fetchPrometheus(ctx);
    if (!text) return '**⚡ Latência**: indisponível (sem FOXCASH_ADMIN_API_KEY ou métricas).';
    const l = parseLatency(text);
    const v = latencyVerdict(l.p95 * 1000); // p95 do Prometheus vem em segundos
    const lines = ['**⚡ Latência (última hora)**'];
    lines.push(`${v.emoji} p95: ${formatMs(l.p95)} — **${v.label}**`);
    lines.push(`p99: ${formatMs(l.p99)} · amostras: ${fmtNumber(l.samples)}`);
    if (v.advice) lines.push(`💡 ${v.advice}`);
    return lines.join('\n');
  },
  async deploy(_a, ctx) {
    const s = await ctx.railway.status();
    const lines = ['**🚀 Deploy**'];
    if (s.online) {
      lines.push(`🟢 Status: **online**${s.deploying ? ' (deploy/building em andamento)' : ''}`);
    } else {
      lines.push('🔴 Status: **offline**');
    }
    lines.push(`📦 Réplicas: ${s.replicas ?? 'n/d'}`);
    if (s.url) lines.push(`🔗 URL: ${s.url}`);
    if (s.deploymentId) lines.push(`🆔 Deploy id: ${s.deploymentId}`);
    if (!s.online) lines.push('💡 Verifique o build/logs no Railway e o healthcheck do app.');
    return lines.join('\n');
  },

  // ---------------- Negócio ----------------
  async vendas(_a, ctx) {
    const s = await ctx.db.salesLastHour();
    const v = salesVerdict(s.count);
    const lines = ['**🛒 Vendas (última hora)**'];
    if (s.count === 0) {
      lines.push(`${v.emoji} ${v.label}`);
    } else {
      lines.push(`${v.emoji} **${fmtNumber(s.count)}** aprovadas · ticket médio ${fmtMoney(s.ticket)}`);
      lines.push(`💰 Movimentado na hora: ~${fmtMoney(s.count * s.ticket)}`);
    }
    return lines.join('\n');
  },
  async chargebacks(_a, ctx) {
    const n = await ctx.db.chargebacks(7);
    const v = chargebackVerdict(n);
    const lines = ['**⚖️ Chargebacks (7 dias)**'];
    lines.push(`${v.emoji} ${n === 0 ? 'Nenhuma disputa — excelente!' : `${fmtNumber(n)} disputa(s) registrada(s)`}`);
    if (v.advice) lines.push(`💡 ${v.advice}`);
    return lines.join('\n');
  },
  async lojistas(_a, ctx) {
    const m = await ctx.db.merchants();
    const growth = m.active > 0 ? Math.round((m.new7d / m.active) * 100) : 0;
    const lines = ['**🏪 Lojistas**'];
    lines.push(`🟢 **${fmtNumber(m.active)}** ativos`);
    lines.push(`🆕 **${fmtNumber(m.new7d)}** novos nos últimos 7 dias${growth > 0 ? ` (${growth}% da base)` : ''}`);
    lines.push(m.new7d > 0 ? '📈 Base crescendo — boa!' : '😴 Nenhum cadastro novo na semana.');
    return lines.join('\n');
  },
  async receita(_a, ctx) {
    const r = await ctx.db.monthlyRevenue();
    const lines = ['**💰 Receita estimada (mês atual)**'];
    lines.push(`${r > 0 ? '📈' : '⚪'} ${fmtMoney(r)} (taxas + mensalidades)`);
    lines.push(r > 0
      ? 'Acumulado do mês — compare com o mês anterior no dashboard master.'
      : 'Nenhuma receita acumulada até agora neste mês.');
    return lines.join('\n');
  },
  async top5(_a, ctx) {
    const rows = await ctx.db.topMerchants(5);
    if (rows.length === 0) return '🏆 **Top lojistas (mês)**: sem vendas ainda este mês.';
    const podium = ['🥇', '🥈', '🥉'];
    const lines = ['**🏆 Top lojistas do mês**'];
    rows.forEach((r, i) => {
      lines.push(`${podium[i] ?? `${i + 1}.`} **${r.name}** — ${fmtMoney(r.volume)}`);
    });
    return lines.join('\n');
  },

  // ---------------- Segurança / auditoria ----------------
  async alertas(_a, ctx) {
    const rows = await ctx.db.securityAlerts(5);
    if (rows.length === 0) return '🛡️ **Alertas de segurança**: nenhum recente — tudo calmo.';
    const lines = ['**🛡️ Últimos alertas de segurança**'];
    for (const a of rows) {
      lines.push(`⚠️ **${a.type}** · ${timeBr(a.created_at.toISOString())}`);
    }
    lines.push('💡 Investigação: use `!ip <ip>`, `!log <requestId>` ou `!bloqueios`.');
    return lines.join('\n');
  },
  async bloqueios(_a, ctx) {
    const logs = await ctx.railway.logs(5000);
    const hits = logs.split('\n')
      .filter((l) => l.includes('Inspecao de corpo bloqueou'))
      .slice(-5);
    if (hits.length === 0) return '🚫 **Bloqueios (body inspection)**: nenhum nos últimos logs — sem ataques detectados.';
    const lines = ['**🚫 Últimos bloqueios (body inspection)**'];
    for (const h of hits) {
      const path = /path=(\S+)/.exec(h)?.[1] ?? '';
      const pattern = /pattern=(\S+)/.exec(h)?.[1] ?? '';
      const category = /category=(\S+)/.exec(h)?.[1] ?? '';
      lines.push(`🛑 ${category}/${pattern} → ${path}`);
    }
    lines.push('💡 Se for ataque real, considere `!bloquear <ip>` na borda.');
    return lines.join('\n');
  },
  async ip(args, ctx) {
    // `!ip <ip> [filtro]` — o filtro opcional (id/nome do vendedor) limita a
    // lista sem nova chamada ao backend (client-side sobre os 217 matches).
    const parts = args.trim().split(/\s+/);
    const ip = parts[0] ?? '';
    if (!ip) return 'Uso: `!ip <endereço> [id-do-vendedor]`';
    const filtro = parts.slice(1).join(' ').toLowerCase();
    const r = await ipLookup(ip, ctx);
    if (r.kind === 'error') return r.text;
    if (!filtro) return formatIpReport('**🔍 Lookup de IP**', ip, r.data);
    const matches = r.data.matches.filter((m) =>
      m.name.toLowerCase().includes(filtro) || m.merchantId.toLowerCase().includes(filtro));
    if (matches.length === 0) {
      return `🔍 **IP ${maskIp(ip)}**: nenhum vendedor com \`${filtro}\` no nome/ID.`;
    }
    return formatIpReport(`**🔍 Lookup de IP** (filtro: \`${filtro}\`)`, ip, { ...r.data, matches });
  },
  async vigiar(args, ctx) {
    const ip = args.trim();
    if (!ip) return 'Uso: `!vigiar <ip>` — alerta único com a situação atual do IP (uma vez por chamada).';
    const r = await ipLookup(ip, ctx);
    if (r.kind === 'error') return r.text;
    return formatIpReport('**🚨 Vigilância de IP**', ip, r.data);
  },
  async reconciliacao(_a, ctx) {
    const [open, last] = await Promise.all([ctx.db.reconciliationOpen(), ctx.db.lastReconciliation()]);
    const v = reconciliationVerdict(open);
    const lines = ['**🧾 Reconciliação wallet×ledger**'];
    lines.push(`${v.emoji} ${v.label}`);
    if (v.advice) lines.push(`💡 ${v.advice}`);
    if (last) {
      lines.push(`Última execução: ${last.status} · ${last.finished_at ? timeBr(last.finished_at.toISOString()) : 'em andamento'}`);
    }
    return lines.join('\n');
  },
  async log(args, ctx) {
    const requestId = args.trim();
    if (!requestId) return 'Uso: `!log <requestId>`';
    const logs = await ctx.railway.logs(5000);
    const hits = logs.split('\n').filter((l) => l.includes(requestId)).slice(0, 10);
    if (hits.length === 0) return `🔎 **requestId ${requestId}**: não encontrado nos últimos logs.`;
    return `🔎 **requestId ${requestId}** (${fmtNumber(hits.length)} linhas):\n\`\`\`\n${hits.join('\n').slice(0, 1800)}\n\`\`\``;
  },

  // ---------------- Ações ----------------
  async bloquear(args, ctx) {
    if (!ctx.cloudflare) return '**!bloquear**: Cloudflare não configurado (CF_API_TOKEN ausente).';
    const ip = args.trim();
    if (!ip) return 'Uso: `!bloquear <ip>`';
    if (!validIp(ip)) return `**${maskIp(ip)}**: endereço IP inválido.`;
    try {
      const r = await ctx.cloudflare.blockIp(ip);
      return `🛑 **IP ${ip} bloqueado** no Cloudflare (regra ${r.ruleId.slice(0, 8)}).\nReversível: remover a regra manualmente (dashboard → WAF).`;
    } catch (e) {
      return `❌ Falha ao bloquear: ${(e as Error).message}`;
    }
  },
  async ratelimit(_a, ctx) {
    const lines = [
      '**🚦 Rate limit**',
      '🟢 App (Redis/memory): 20/min públicas · 120/min autenticado · 300/min transacional · 120/min webhook',
    ];
    if (ctx.cloudflare) {
      try {
        const edge = await ctx.cloudflare.edgeRateLimits();
        lines.push(edge.length
          ? `🛡️ Edge (Cloudflare): ${edge.map((e) => e.description).join(' | ')}`
          : '🛡️ Edge (Cloudflare): sem regras');
      } catch {
        lines.push('🛡️ Edge: indisponível');
      }
    }
    lines.push('💡 Ajustes exigem config no backend (app) ou dashboard (edge).');
    return lines.join('\n');
  },
};

export const help = `**👋 Comandos do Operador FoxCash**\n`
  + `_Saúde_: !saude · !conexoes · !outbox · !jobs · !latencia · !deploy\n`
  + `_Negócio_: !vendas · !chargebacks · !lojistas · !receita · !top5\n`
  + `_Vendedores por IP_: !ip <ip> [id] (lookup + filtro por id) · !vigiar <ip> (alerta único)\n`
  + `_Segurança_: !alertas · !bloqueios · !reconciliacao · !log <requestId>\n`
  + `_Ações_: !bloquear <ip> · !ratelimit\n`
  + `_Outros_: !ajuda`;
