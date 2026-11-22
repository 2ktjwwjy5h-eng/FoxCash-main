// Contratos das APIs ROLE_MASTER consumidas pelo console (Spec 71, fatia 1).
// Shapes espelhados dos records do backend (dto/) — thin client, zero regra de
// negócio.

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PixTransaction {
  id: string;
  merchantId: string;
  amount: string | number;
  description: string | null;
  pixKey: string | null;
  endToEndId: string | null;
  status: string;
  createdAt: string;
  updatedAt: string;
  paidAt: string | null;
}

export interface Merchant {
  id: string;
  name: string;
  email: string;
  status: string;
  transactionFee: string | number;
  monthlyFee: string | number;
  createdAt: string;
}

export interface MerchantEarnings {
  walletBalance: string | number;
  approvedVolume: string | number;
  totalWithdrawn: string | number;
  withdrawalsBlocked: boolean;
}

export interface MerchantFees {
  transactionCount: number;
  transactionFees: string | number;
  monthlyFee: string | number;
  totalFees: string | number;
}

export interface MerchantRisk {
  merchantId: string;
  computedAt: string;
  approvedVolume: string | number;
  totalTransactions: number;
  approvedTransactions: number;
  refundedTransactions: number;
  refundRatePercent: string | number;
  chargebackRatePercent: string | number;
  complaintsCount: number;
  refundAlert: boolean;
  chargebackAlert: boolean;
  complaintsAlert: boolean;
  riskScore: number;
}

export interface WalletLedgerEntry {
  entryId: string;
  type: string;
  amount: string | number;
  balanceAfter: string | number;
  referenceType: string;
  referenceId: string;
  createdAt: string;
}

export interface Receivable {
  id: string;
  merchantId: string;
  origin: string;
  amount: string | number;
  paidAmount: string | number;
  status: string;
  createdAt: string;
  paidAt: string | null;
}

export interface Withdrawal {
  id: string;
  merchantId: string;
  amount: string | number;
  /** Spec 88 — taxa de saque (R$ 2,50 ou override do lojista). */
  fee: string | number;
  status: string;
  type: string;
  destination: string;
  walletBalance: string | number | null;
  externalReference: string | null;
  failureReason: string | null;
  createdAt: string;
  completedAt: string | null;
  rejectedAt: string | null;
  failedAt: string | null;
}

export interface RevenueSummary {
  transactionFees: string | number;
  monthlyFees: string | number;
  recoveries: string | number;
  adminRecoveries: string | number;
  losses: string | number;
  total: string | number;
  net: string | number;
}

export interface MasterLedgerEntry {
  id: string;
  merchantId: string;
  type: string;
  amount: string | number;
  balanceAfter: string | number;
  referenceType: string;
  referenceId: string;
  createdAt: string;
}

export interface AuditEntry {
  id: string;
  actorId: string;
  action: string;
  merchantId: string | null;
  referenceType: string;
  referenceId: string | null;
  before: string | null;
  after: string | null;
  reason: string | null;
  ip: string | null;
  createdAt: string;
}

export interface PlatformActivity {
  id: string;
  occurredAt: string;
  actorType: string;
  actorId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  merchantId: string | null;
  details: string | null;
  ip: string | null;
}

export interface ReconciliationIssue {
  issueId: string;
  merchantId: string;
  expectedBalance: string | number;
  actualBalance: string | number;
  status: string;
  detectedAt: string;
  resolvedAt: string | null;
}

export interface IpLookupMatch {
  merchantId: string;
  name: string;
  email: string;
  status: string;
  totalSales: number;
  averageTicket: string | number;
  lastSaleAt: string | null;
  sales7d: number;
  revenue7d: string | number;
  bot: {
    storeId: string;
    storeName: string;
    activePlans: number;
    activeWebhooks: number;
    link: string;
  } | null;
}

export interface IpLookup {
  ip: string;
  matches: IpLookupMatch[];
}

// ── Spec 72 (fatia 2) — ações sensíveis + step-up ──────────────────────────

export interface Invoice {
  id: string;
  merchantId: string;
  billingPeriod: string;
  transactionCount: number;
  transactionFeesTotal: string | number;
  monthlyFee: string | number;
  totalAmount: string | number;
  status: string;
  dueDate: string;
  createdAt: string;
}

export interface CollectionResult {
  debit: string | number;
  remaining: string | number;
}

export interface WriteOffResult {
  loss: string | number;
}

export interface MasterProfile {
  id: string;
  username: string;
  name: string;
  active: boolean;
  canManageMasters: boolean;
  createdAt: string;
}

export interface MasterCreatedProfile {
  id: string;
  username: string;
  name: string;
  active: boolean;
  canManageMasters: boolean;
  createdAt: string;
  temporaryPassword: string;
}

// ── Spec 97/99 — Painel Geral de Usuários (leitura) ─────────────────────────

export interface MerchantPanelItem {
  id: string;
  name: string;
  email: string;
  status: string; // ACTIVE | SUSPENDED | INACTIVE | BANNED
  transactionFee: string | number;
  monthlyFee: string | number;
  withdrawalFee: string | number;
  conversionRate: string | number;
  paidPixCount: number;
  medPercent: string | number;
  medOccurrences: number;
  gateway: string | null; // null → PADRAO (rota global da Spec 91)
  createdAt: string;
}

export interface MerchantPanelDetail {
  kpis: {
    walletBalance: string | number;
    grossRevenue: string | number;
    paidPixCount: number;
    conversionRate: string | number;
    storesCount: number;
  };
  account: {
    name: string;
    email: string;
    phone: string | null;
    documentMasked: string | null;
    gateway: string | null;
    createdAt: string;
  };
  feesAndLimits: {
    transactionFee: string | number;
    fixedFee: string | number;
    withdrawalFee: string | number;
    medPercent: string | number;
    medOccurrences: number;
    pixMin: string | number | null;
    pixMax: string | number | null;
    withdrawalMin: string | number | null;
    withdrawalMax: string | number | null;
  };
  /** Spec 141: lojistas DISTINTOS no dispositivo do lojista (0 = sem fingerprint, 1 = exclusivo, N>1 = risco). */
  fingerprintDistinctMerchants?: number;
}

export interface MerchantPanelTransaction {
  transactionId: string;
  amount: string | number;
  status: string;
  buyerName: string | null;
  buyerIdHash: string;
  productName: string | null;
  externalReference: string | null;
  fee: string | number;
  netAmount: string | number;
  createdAt: string;
}

export interface MerchantStore {
  id: string;
  name: string;
  title: string | null;
  color: string | null;
  emoji: string | null;
  description: string | null;
  logoUrl: string | null;
  bannerUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

// ── Spec 98/100 — Config fina por lojista (escrita) ─────────────────────────

export type PixFeeType = 'FIXED' | 'PERCENT' | null;

export interface MerchantConfig {
  pixFeeType: PixFeeType;
  pixFeeFixed: string | number | null;
  pixFeePercent: string | number | null;
  pixFeeThreshold: string | number | null;
  withdrawalFee: string | number | null;
  pixMin: string | number | null;
  pixMax: string | number | null;
  withdrawalMin: string | number | null;
  withdrawalMax: string | number | null;
  rateLimitRps: number | null;
  rateLimitRpm: number | null;
  apiBlocked: boolean;
  honeypot: boolean;
}

export interface DebitResult {
  newBalance: string | number;
  debitedAmount: string | number;
}

// ── Spec 127 — Trace de transação (fases 3–4 da Spec 125) ─────────────────────

export interface BotRef {
  botId: string;
  platformUserId: string;
}

export interface TraceItem {
  at: string;
  stage: string; // BOT | GATEWAY | PSP | LEDGER | WEBHOOK
  event: string;
  detail: Record<string, unknown>;
}

export interface TransactionTraceResponse {
  traceId: string;
  transactionId: string;
  merchantId: string;
  amount: string | number;
  status: string;
  externalReference: string | null;
  endToEndId: string | null;
  provider: string | null;
  profileId: string | null;
  idempotencyKey: string | null;
  createdAt: string;
  paidAt: string | null;
  bot: BotRef | null;
  timeline: TraceItem[];
}

