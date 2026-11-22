// Helpers de formatação do Painel Geral de Usuários (Spec 99) — badges de
// status, cor do MED% por faixa e percentual. CSS puro (padrão Spec 73).

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'Aprovado',
  SUSPENDED: 'Suspenso',
  INACTIVE: 'Inativo',
  BANNED: 'Banido',
};

const STATUS_CLASSES: Record<string, string> = {
  ACTIVE: 'tag-success',
  SUSPENDED: 'tag-warning',
  INACTIVE: 'tag-neutral',
  BANNED: 'tag-danger',
};

/** Rótulo PT-BR do status da conta (Aprovado/Suspenso/Inativo/Banido). */
export function statusLabel(status: string): string {
  return STATUS_LABELS[status] || status;
}

/** Classe do badge de status (tag-success/warning/neutral/danger). */
export function statusClass(status: string): string {
  return STATUS_CLASSES[status] || 'tag-neutral';
}

/**
 * Cor da célula MED% por faixa (Spec 99 C1): verde < 2%, amarelo 2–5%,
 * vermelho > 5% — mesmo limiar dos thresholds do backend (`risk-thresholds`).
 */
export function medClass(percent: string | number | null | undefined): string {
  const p = Number(percent ?? 0);
  if (p > 5) return 'tag-danger';
  if (p >= 2) return 'tag-warning';
  return 'tag-success';
}

/**
 * Percentual formatado PT-BR (2 casas, vírgula decimal) — ex.: "8,73%".
 * Mesmo padrão do `formatMoney` (toLocaleString pt-BR, Spec 73).
 */
export function formatPercent(value: string | number | null | undefined): string {
  return `${Number(value ?? 0).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`;
}

/** Rótulo PT-BR do status do saque (Spec 99 D7 — "CONCLUIDO" na UI). */
export function withdrawalStatusLabel(status: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'CONCLUIDO';
    case 'FAILED':
      return 'FALHOU';
    case 'PROCESSING':
      return 'PROCESSANDO';
    case 'PENDING':
      return 'PENDENTE';
    case 'REJECTED':
      return 'REJEITADO';
    default:
      return status;
  }
}

export function withdrawalStatusClass(status: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'tag-success';
    case 'FAILED':
    case 'REJECTED':
      return 'tag-danger';
    case 'PROCESSING':
      return 'tag-warning';
    default:
      return 'tag-neutral';
  }
}
