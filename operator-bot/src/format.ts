/**
 * Formatação conversacional das respostas do bot de operação (Spec 67).
 *
 * Cada "verdict" devolve { emoji, label, advice? } — o emoji + rótulo humano do
 * estado (ótimo/bom/regular/ruim) e, quando há atenção, uma dica de próximo
 * passo. Módulo puro (sem IO) para testes unitários.
 */

export const fmtNumber = (n: number) => n.toLocaleString('pt-BR');
export const fmtMoney = (n: number) =>
  `R$ ${n.toLocaleString('pt-BR', { minimumFractionDigits: 2 })}`;

export interface Verdict {
  emoji: string;
  label: string;
  advice?: string;
}

const E = {
  great: '🟢',
  warn: '🟠',
  bad: '🔴',
};

/** Classifica a latência p95 (em milissegundos). */
export function latencyVerdict(p95ms: number): Verdict {
  if (p95ms <= 0) return { emoji: '⚪', label: 'sem amostras' };
  if (p95ms <= 200) return { emoji: E.great, label: 'ótimo 🚀' };
  if (p95ms <= 500) return { emoji: E.great, label: 'bom ✅' };
  if (p95ms <= 1000) {
    return {
      emoji: E.warn,
      label: 'regular ⚠️',
      advice: 'vale acompanhar — confira o p99 e a carga de CPU/DB antes de agir',
    };
  }
  return {
    emoji: E.bad,
    label: 'ruim 🚨',
    advice: 'investigue: CPU/JVM, pool Hikari, queries lentas (pg_stat_statements) e réplicas',
  };
}

/** Classifica o uso de conexões do banco (percentual 0-100). */
export function usageVerdict(pct: number): Verdict {
  if (pct < 50) return { emoji: E.great, label: 'tranquilo' };
  if (pct < 75) return { emoji: E.great, label: 'ok' };
  if (pct < 90) {
    return {
      emoji: E.warn,
      label: 'elevado',
      advice: 'confira se não há conexões presas (pg_stat_activity)',
    };
  }
  return {
    emoji: E.bad,
    label: 'crítico',
    advice: 'considere subir o pool/réplicas ou revisar conexões de longa duração',
  };
}

/** Classifica o PENDING do outbox na última hora. */
export function outboxVerdict(pending: number): Verdict {
  if (pending === 0) return { emoji: E.great, label: 'drenando liso ✅' };
  if (pending < 100) return { emoji: E.great, label: 'dentro do normal' };
  if (pending < 1000) {
    return {
      emoji: E.warn,
      label: 'acumulando',
      advice: 'confira a drenagem e o dispatcher (Spec 53/65)',
    };
  }
  return {
    emoji: E.bad,
    label: 'possível gargalo',
    advice: 'se o PENDING não cair, veja os FAILED e o alerta OUTBOX_DEAD',
  };
}

/** Classifica as falhas dos últimos jobs. */
export function jobsVerdict(failed: number, total: number): Verdict {
  if (total === 0) return { emoji: '⚪', label: 'sem registros ainda' };
  if (failed === 0) return { emoji: E.great, label: `nenhuma falha nos últimos ${total}` };
  return {
    emoji: E.bad,
    label: `${failed} falha(s) em ${total}`,
    advice: 'confira o job no Railway (job_runs) e reexecute se necessário',
  };
}

/** Classifica as vendas aprovadas da última hora. */
export function salesVerdict(count: number): Verdict {
  if (count === 0) {
    return { emoji: '😴', label: 'nenhuma venda na última hora — tudo quieto por aqui' };
  }
  if (count < 10) return { emoji: '🙂', label: 'movimento leve, dentro do esperado' };
  if (count < 100) return { emoji: '📈', label: 'bom movimento!' };
  return { emoji: '🔥', label: 'pico de vendas!' };
}

/** Classifica os chargebacks dos últimos 7 dias. */
export function chargebackVerdict(n: number): Verdict {
  if (n === 0) return { emoji: '✅', label: 'nenhuma disputa — excelente!' };
  if (n < 5) {
    return {
      emoji: E.warn,
      label: `${n} disputa(s) — de olho, ainda sob controle`,
      advice: 'acompanhe se não cresce; acima de 5 vale revisar a triagem antifraude',
    };
  }
  return {
    emoji: E.bad,
    label: `${n} disputa(s)`,
    advice: 'acima do normal: revise a triagem antifraude e os compradores com chargeback',
  };
}

/** Classifica as divergências abertas da reconciliação wallet×ledger. */
export function reconciliationVerdict(open: number): Verdict {
  if (open === 0) {
    return { emoji: '✅', label: 'nenhuma divergência aberta — saldo íntegro!' };
  }
  return {
    emoji: E.bad,
    label: `${fmtNumber(open)} divergência(s) aberta(s)`,
    advice: 'saldo wallet ≠ ledger: investigue antes de liberar saques (Spec 30)',
  };
}
