// Detalhe do lojista (Spec 71, fatia 1): tabs de leitura
// (earnings/fees/risk/ledger/receivables/saques — Specs 15/16/19/27/33/14) +
// ações reversíveis de OPERATOR (suspend/release — Spec 16) com confirmação.
// A UI esconde botões sem a permissão (Spec 69); o backend continua a autoridade
// (403 → banner "sem permissão", a sessão permanece).
// Spec 72 (fatia 2): aba "Faturas" (espelho GET /master/invoices + cobrança
// manual — Spec 33, ação sensível com step-up TOTP).
// Spec 99 (Painel Geral): KPIs de topo + Dados da Conta + Taxas e Limites
// (GET /master/merchants/{id}/panel — Spec 97), aba "Transações" (comprador
// via lead do CRM, produto via plano, líquido) e aba Saques integrada (Chave
// Pix mascarada + taxa — Spec 88). Ações: "Config" (Spec 100) + "Ver Shops".

import { useState } from 'react';
import { Link, NavLink, Route, Routes, useParams } from 'react-router-dom';
import { api } from '../apiClient';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Card, ErrorBanner, Pagination, Spinner, SuccessBanner, formatDate, formatMoney, useAsync } from '../components/ui';
import { RequirePermission } from '../hooks/usePermission';
import { useStepUp } from '../hooks/useStepUp';
import { formatPercent, medClass, withdrawalStatusClass, withdrawalStatusLabel } from '../merchantFormat';
import StoresModal from './StoresModal';
import type {
  CollectionResult,
  Invoice,
  Merchant,
  MerchantEarnings,
  MerchantFees,
  MerchantPanelDetail,
  MerchantPanelTransaction,
  MerchantRisk,
  PageResponse,
  Receivable,
  WalletLedgerEntry,
  Withdrawal,
} from '../types';

const TABS = [
  { to: '', label: 'Earnings' },
  { to: 'fees', label: 'Fees' },
  { to: 'risk', label: 'Risco' },
  { to: 'ledger', label: 'Ledger' },
  { to: 'receivables', label: 'Receivables' },
  { to: 'withdrawals', label: 'Saques' },
  { to: 'transactions', label: 'Transações' },
  { to: 'invoices', label: 'Faturas' },
];

export default function MerchantDetailPage() {
  const { id = '' } = useParams();
  const earnings = useAsync(() => api.get<MerchantEarnings>(`/api/v1/master/merchants/${id}/earnings`), [id]);
  // Spec 73 (D5.2): resolve o NOME do lojista pela lista (cap 100) — o h1 não
  // mostra só o id. Sem endpoint novo (reuso do GET /master/merchants).
  const merchants = useAsync(() => api.get<PageResponse<Merchant>>('/api/v1/master/merchants?page=0&size=100'), []);
  const merchantName = merchants.data?.items.find((m) => m.id === id)?.name;
  const [confirm, setConfirm] = useState<'suspend' | 'release' | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [shopsOpen, setShopsOpen] = useState(false);

  const runAction = async (action: 'suspend' | 'release') => {
    setBusy(true);
    setActionError(null);
    try {
      await api.post<void>(`/api/v1/master/merchants/${id}/${action}`);
      setFeedback(`Lojista ${action === 'suspend' ? 'suspenso' : 'liberado'}.`);
      setConfirm(null);
      earnings.reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <p>
        <Link to="/console/merchants" className="btn btn-small">
          ← Lojistas
        </Link>
      </p>
      <h1>Lojista {merchantName || `${id.slice(0, 8)}…`}</h1>

      {feedback && <div className="alert alert-success">{feedback}</div>}

      {/* Spec 99 — KPIs de topo + Dados da Conta + Taxas e Limites (Spec 97). */}
      <PanelHeader id={id} />

      <div className="grid-2">
        <Card title="Saldo do wallet">
          <div className="metric-value big">{earnings.data ? formatMoney(earnings.data.walletBalance) : '—'}</div>
          <p className="muted">
            Volume aprovado: {earnings.data ? formatMoney(earnings.data.approvedVolume) : '—'} · Saques:{' '}
            {earnings.data ? formatMoney(earnings.data.totalWithdrawn) : '—'}
          </p>
          <p className="muted">
            Saques {earnings.data?.withdrawalsBlocked ? 'BLOQUEADOS' : 'liberados'}
          </p>
        </Card>
        <Card title="Ações de operação">
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <RequirePermission permission="merchant.suspend">
              <button type="button" className="btn btn-danger" onClick={() => setConfirm('suspend')}>
                Suspender lojista
              </button>
            </RequirePermission>
            <RequirePermission permission="merchant.release">
              <button type="button" className="btn" onClick={() => setConfirm('release')}>
                Liberar lojista
              </button>
            </RequirePermission>
            {/* Spec 99/100 — atalhos: Config (escrita) + Ver Shops (leitura). */}
            <RequirePermission permission="merchant.config">
              <Link className="btn" to={`/console/merchants/${id}/config`}>
                Config
              </Link>
            </RequirePermission>
            <button type="button" className="btn" onClick={() => setShopsOpen(true)}>
              Ver shops
            </button>
          </div>
          <p className="muted">Ações reversíveis de OPERATOR (Spec 16) — sem step-up na fatia 1.</p>
        </Card>
      </div>
      <ErrorBanner error={earnings.error} />

      <div className="tabs">
        {TABS.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to ? `/console/merchants/${id}/${tab.to}` : `/console/merchants/${id}`}
            end={tab.to === ''}
            className={({ isActive }) => (isActive ? 'tab active' : 'tab')}
          >
            {tab.label}
          </NavLink>
        ))}
      </div>

      <Routes>
        <Route index element={<EarningsTab id={id} />} />
        <Route path="fees" element={<FeesTab id={id} />} />
        <Route path="risk" element={<RiskTab id={id} />} />
        <Route path="ledger" element={<LedgerTab id={id} />} />
        <Route path="receivables" element={<MerchantReceivablesTab id={id} />} />
        <Route path="withdrawals" element={<MerchantWithdrawalsTab id={id} />} />
        <Route path="transactions" element={<MerchantTransactionsTab id={id} />} />
        <Route path="invoices" element={<MerchantInvoicesTab id={id} />} />
      </Routes>

      <ConfirmDialog
        open={confirm !== null}
        title={confirm === 'suspend' ? 'Suspender lojista' : 'Liberar lojista'}
        message={
          confirm === 'suspend'
            ? `Suspender o lojista ${id.slice(0, 8)}…? Ele deixará de processar cobranças e sacar.`
            : `Liberar o lojista ${id.slice(0, 8)}…? Ele voltará a processar cobranças e sacar.`
        }
        confirmLabel={confirm === 'suspend' ? 'Suspender' : 'Liberar'}
        tone={confirm === 'release' ? 'primary' : 'danger'}
        busy={busy}
        error={actionError}
        onConfirm={() => confirm && runAction(confirm)}
        onCancel={() => {
          setConfirm(null);
          setActionError(null);
        }}
      />

      {shopsOpen && (
        <StoresModal merchantId={id} merchantName={merchantName || id.slice(0, 8)} onClose={() => setShopsOpen(false)} />
      )}
    </div>
  );
}

/** Spec 99 — KPIs de topo (4 cards) + Dados da Conta + Taxas e Limites. */
function PanelHeader({ id }: { id: string }) {
  const { data, error, loading } = useAsync(
    () => api.get<MerchantPanelDetail>(`/api/v1/master/merchants/${id}/panel`),
    [id],
  );
  if (loading) return <Spinner />;
  if (error) return <ErrorBanner error={error} />;
  if (!data) return null;
  const { kpis, account, feesAndLimits: f } = data;
  return (
    <>
      <div className="metric-grid">
        <Card title="Saldo">
          <div className="metric-value">{formatMoney(kpis.walletBalance)}</div>
        </Card>
        <Card title="Faturamento">
          <div className="metric-value">{formatMoney(kpis.grossRevenue)}</div>
          <p className="muted">{kpis.paidPixCount} Pix pagos</p>
        </Card>
        <Card title="Conversão">
          <div className="metric-value">{formatPercent(kpis.conversionRate)}</div>
        </Card>
        <Card title="Shops">
          <div className="metric-value">{kpis.storesCount}</div>
        </Card>
      </div>

      <div className="grid-2">
        <Card title="Dados da conta">
          <ul className="plain-list">
            <li>
              <span>Nome</span> <strong>{account.name}</strong>
            </li>
            <li>
              <span>E-mail</span> <strong>{account.email}</strong>
            </li>
            <li>
              <span>Telefone</span> <strong>{account.phone || '—'}</strong>
            </li>
            <li>
              <span>CPF/CNPJ</span> <strong>{account.documentMasked || '—'}</strong>
            </li>
            <li>
              <span>Gateway</span> <strong>{account.gateway || 'PADRAO'}</strong>
            </li>
            <li>
              <span>Cadastro</span> <strong>{formatDate(account.createdAt)}</strong>
            </li>
          </ul>
        </Card>
        <Card title="Taxas e limites">
          <ul className="plain-list">
            <li>
              <span>Taxa Pix</span> <strong>{formatMoney(f.transactionFee)}</strong>
            </li>
            <li>
              <span>Taxa Fixa</span> <strong>{formatMoney(f.fixedFee)}</strong>
            </li>
            <li>
              <span>Taxa de Saque</span> <strong>{formatMoney(f.withdrawalFee)}</strong>
            </li>
            <li>
              <span>MED</span>{' '}
              <strong>
                <span className={`tag ${medClass(f.medPercent)}`}>
                  {formatPercent(f.medPercent)} / {f.medOccurrences}
                </span>
              </strong>
            </li>
            {/* Spec 141: mesmo dispositivo em N contas = sinal de risco (flag). */}
            {data.fingerprintDistinctMerchants != null && data.fingerprintDistinctMerchants > 1 && (
              <li>
                <span>Device</span>{' '}
                <strong>
                  <span className="tag tag-danger">
                    ⚠ {data.fingerprintDistinctMerchants} contas no mesmo dispositivo
                  </span>
                </strong>
              </li>
            )}
            <li>
              <span>Limites Pix</span>{' '}
              <strong>
                {f.pixMin != null && f.pixMax != null ? `${formatMoney(f.pixMin)} – ${formatMoney(f.pixMax)}` : 'global'}
              </strong>
            </li>
            <li>
              <span>Limites Saque</span>{' '}
              <strong>
                {f.withdrawalMin != null && f.withdrawalMax != null
                  ? `${formatMoney(f.withdrawalMin)} – ${formatMoney(f.withdrawalMax)}`
                  : 'global'}
              </strong>
            </li>
          </ul>
        </Card>
      </div>
    </>
  );
}

function txStatusLabel(status: string): string {
  switch (status) {
    case 'APPROVED':
      return 'PAGO';
    case 'PENDING':
      return 'PENDENTE';
    case 'REFUNDED':
      return 'ESTORNADO';
    case 'REJECTED':
      return 'REJEITADO';
    case 'FAILED':
      return 'FALHOU';
    default:
      return status;
  }
}

function txStatusClass(status: string): string {
  switch (status) {
    case 'APPROVED':
      return 'tag-success';
    case 'PENDING':
      return 'tag-warning';
    case 'REFUNDED':
    case 'REJECTED':
    case 'FAILED':
      return 'tag-danger';
    default:
      return 'tag-neutral';
  }
}

function EarningsTab({ id }: { id: string }) {
  const { data, error, loading } = useAsync(
    () => api.get<MerchantEarnings>(`/api/v1/master/merchants/${id}/earnings`),
    [id],
  );
  if (loading) return <Spinner />;
  if (error) return <ErrorBanner error={error} />;
  if (!data) return null;
  return (
    <Card>
      <ul className="plain-list">
        <li>
          <span>Saldo do wallet</span> <strong>{formatMoney(data.walletBalance)}</strong>
        </li>
        <li>
          <span>Volume aprovado</span> <strong>{formatMoney(data.approvedVolume)}</strong>
        </li>
        <li>
          <span>Total sacado</span> <strong>{formatMoney(data.totalWithdrawn)}</strong>
        </li>
        <li>
          <span>Saques bloqueados</span> <strong>{data.withdrawalsBlocked ? 'Sim' : 'Não'}</strong>
        </li>
      </ul>
    </Card>
  );
}

function FeesTab({ id }: { id: string }) {
  const month = new Date().toISOString().slice(0, 7);
  const { data, error, loading } = useAsync(
    () => api.get<MerchantFees>(`/api/v1/master/merchants/${id}/fees?month=${month}`),
    [id],
  );
  if (loading) return <Spinner />;
  if (error) return <ErrorBanner error={error} />;
  if (!data) return null;
  return (
    <Card>
      <ul className="plain-list">
        <li>
          <span>Transações no mês</span> <strong>{data.transactionCount}</strong>
        </li>
        <li>
          <span>Taxas de transação</span> <strong>{formatMoney(data.transactionFees)}</strong>
        </li>
        <li>
          <span>Taxa mensal</span> <strong>{formatMoney(data.monthlyFee)}</strong>
        </li>
        <li>
          <span>Total de taxas</span> <strong>{formatMoney(data.totalFees)}</strong>
        </li>
      </ul>
    </Card>
  );
}

function RiskTab({ id }: { id: string }) {
  const { data, error, loading } = useAsync(
    () => api.get<MerchantRisk>(`/api/v1/master/merchants/${id}/risk`),
    [id],
  );
  if (loading) return <Spinner />;
  if (error) return <ErrorBanner error={error} />;
  if (!data) return null;
  return (
    <Card>
      <ul className="plain-list">
        <li>
          <span>Score de risco</span> <strong>{data.riskScore}</strong>
        </li>
        <li>
          <span>Volume aprovado</span> <strong>{formatMoney(data.approvedVolume)}</strong>
        </li>
        <li>
          <span>Taxa de reembolso</span>{' '}
          <strong>
            {Number(data.refundRatePercent).toFixed(2)}% {data.refundAlert && '⚠'}
          </strong>
        </li>
        <li>
          <span>Taxa de chargeback</span>{' '}
          <strong>
            {Number(data.chargebackRatePercent).toFixed(2)}% {data.chargebackAlert && '⚠'}
          </strong>
        </li>
        <li>
          <span>Denúncias</span> <strong>{data.complaintsCount}</strong>
        </li>
        <li>
          <span>Calculado em</span> <strong>{formatDate(data.computedAt)}</strong>
        </li>
      </ul>
    </Card>
  );
}

function LedgerTab({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<WalletLedgerEntry>>(`/api/v1/master/merchants/${id}/ledger?page=${page}&size=20`),
    [id, page],
  );
  return (
    <Card>
      {loading && <Spinner />}
      <ErrorBanner error={error} />
      {data && (
        <>
          <table className="table">
            <thead>
              <tr>
                <th>Tipo</th>
                <th>Valor</th>
                <th>Saldo após</th>
                <th>Data</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((e: WalletLedgerEntry) => (
                <tr key={e.entryId}>
                  <td>{e.type}</td>
                  <td>{formatMoney(e.amount)}</td>
                  <td>{formatMoney(e.balanceAfter)}</td>
                  <td>{formatDate(e.createdAt)}</td>
                </tr>
              ))}
              {data.items.length === 0 && (
                <tr>
                  <td colSpan={4} className="muted">
                    Nenhum lançamento.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
        </>
      )}
    </Card>
  );
}

function MerchantReceivablesTab({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<Receivable>>(`/api/v1/master/merchants/${id}/receivables?page=${page}&size=20`),
    [id, page],
  );
  return (
    <Card>
      {loading && <Spinner />}
      <ErrorBanner error={error} />
      {data && (
        <>
          <table className="table">
            <thead>
              <tr>
                <th>Origem</th>
                <th>Valor</th>
                <th>Pago</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((r: Receivable) => (
                <tr key={r.id}>
                  <td>{r.origin}</td>
                  <td>{formatMoney(r.amount)}</td>
                  <td>{formatMoney(r.paidAmount)}</td>
                  <td>
                    <span className="tag">{r.status}</span>
                  </td>
                </tr>
              ))}
              {data.items.length === 0 && (
                <tr>
                  <td colSpan={4} className="muted">
                    Nenhum receivable.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
        </>
      )}
    </Card>
  );
}

/** Aba Saques integrada (Spec 99 D7): Valor/Status/Chave Pix/Taxa/Data —
 *  `WithdrawalResponse` já traz `destination` mascarado e `fee` (Spec 88). */
function MerchantWithdrawalsTab({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<Withdrawal>>(`/api/v1/master/withdrawals?merchantId=${id}&page=${page}&size=20`),
    [id, page],
  );
  return (
    <Card>
      {loading && <Spinner />}
      <ErrorBanner error={error} />
      {data && (
        <>
          <table className="table">
            <thead>
              <tr>
                <th>Valor</th>
                <th>Status</th>
                <th>Chave Pix</th>
                <th>Taxa</th>
                <th>Data</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((w: Withdrawal) => (
                <tr key={w.id}>
                  <td>{formatMoney(w.amount)}</td>
                  <td>
                    <span className={`tag ${withdrawalStatusClass(w.status)}`}>{withdrawalStatusLabel(w.status)}</span>
                  </td>
                  <td>{w.destination || '—'}</td>
                  <td>{formatMoney(w.fee)}</td>
                  <td>{formatDate(w.createdAt)}</td>
                </tr>
              ))}
              {data.items.length === 0 && (
                <tr>
                  <td colSpan={5} className="muted">
                    Nenhum saque.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
          <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
        </>
      )}
    </Card>
  );
}

/** Aba Transações (Spec 99 D6): valor/status/comprador/produto/TXID/líquido.
 *  Comprador = nome do lead do CRM (Spec 75/83) ou `buyerIdHash` opaco;
 *  produto = nome do plano (Spec 49); líquido = bruto − taxa (Spec 97). */
function MerchantTransactionsTab({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (status) params.set('status', status);
  if (from) params.set('from', `${from}T00:00:00Z`);
  if (to) params.set('to', `${to}T23:59:59Z`);

  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<MerchantPanelTransaction>>(`/api/v1/master/merchants/${id}/transactions?${params.toString()}`),
    [id, page, params.toString()],
  );

  return (
    <Card>
      <div className="filter-row">
        <select
          className="input input-small"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="">Status (todos)</option>
          <option value="PENDING">PENDENTE</option>
          <option value="APPROVED">PAGO</option>
          <option value="REFUNDED">ESTORNADO</option>
        </select>
        <label className="muted">
          De:{' '}
          <input
            className="input input-small"
            type="date"
            value={from}
            onChange={(e) => {
              setFrom(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label className="muted">
          Até:{' '}
          <input
            className="input input-small"
            type="date"
            value={to}
            onChange={(e) => {
              setTo(e.target.value);
              setPage(0);
            }}
          />
        </label>
      </div>
      {loading && <Spinner />}
      <ErrorBanner error={error} />
      {data && (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Valor</th>
                  <th>Status</th>
                  <th>Comprador</th>
                  <th>Produto</th>
                  <th>TXID</th>
                  <th>Líquido</th>
                  <th>Data</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((t: MerchantPanelTransaction) => (
                  <tr key={t.transactionId}>
                    <td>{formatMoney(t.amount)}</td>
                    <td>
                      <span className={`tag ${txStatusClass(t.status)}`}>{txStatusLabel(t.status)}</span>
                    </td>
                    <td>
                      {t.buyerName || (
                        <span title={t.buyerIdHash}>
                          {t.buyerIdHash ? `${t.buyerIdHash.slice(0, 8)}…` : '—'}
                        </span>
                      )}
                    </td>
                    <td>{t.productName || '—'}</td>
                    <td>
                      <code>{t.externalReference || '—'}</code>
                    </td>
                    <td>
                      <span title="bruto − taxa">{formatMoney(t.netAmount)}</span>
                    </td>
                    <td>{formatDate(t.createdAt)}</td>
                  </tr>
                ))}
                {data.items.length === 0 && (
                  <tr>
                    <td colSpan={7} className="muted">
                      Nenhuma transação no filtro.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
        </>
      )}
    </Card>
  );
}

function MerchantInvoicesTab({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const stepUp = useStepUp();
  const [collectTarget, setCollectTarget] = useState<Invoice | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const { data, error, loading, reload } = useAsync(
    () => api.get<PageResponse<Invoice>>(`/api/v1/master/invoices?merchantId=${id}&page=${page}&size=20`),
    [id, page],
  );

  const runCollect = async () => {
    if (!collectTarget) return;
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      const result = await stepUp.run(() =>
        api.post<CollectionResult>(`/api/v1/master/merchants/${id}/invoices/${collectTarget.id}/collect`),
      );
      setFeedback(`Debitado ${formatMoney(result.debit)} · saldo devedor ${formatMoney(result.remaining)}.`);
      setCollectTarget(null);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <SuccessBanner message={feedback} />
      {loading && <Spinner />}
      <ErrorBanner error={error} />
      {data && (
        <>
          <table className="table">
            <thead>
              <tr>
                <th>Período</th>
                <th>Valor total</th>
                <th>Status</th>
                <th>Vencimento</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {data.items.length === 0 && (
                <tr>
                  <td colSpan={5} className="muted">
                    Nenhuma fatura.
                  </td>
                </tr>
              )}
              {data.items.map((inv: Invoice) => {
                const collectable = inv.status === 'PENDING' || inv.status === 'OVERDUE';
                return (
                  <tr key={inv.id}>
                    <td>{inv.billingPeriod}</td>
                    <td>{formatMoney(inv.totalAmount)}</td>
                    <td>
                      <span className="tag">{inv.status}</span>
                    </td>
                    <td>{inv.dueDate}</td>
                    <td>
                      {collectable && (
                        <RequirePermission permission="invoice.collect">
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() => {
                              setActionError(null);
                              setCollectTarget(inv);
                            }}
                          >
                            Cobrar
                          </button>
                        </RequirePermission>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
        </>
      )}

      <ConfirmDialog
        open={collectTarget !== null}
        title="Cobrança manual de fatura"
        message={`Cobrar a fatura de ${collectTarget?.billingPeriod} (${formatMoney(collectTarget?.totalAmount)}) do wallet do lojista? Ação sensível — exige reautenticação (TOTP).`}
        confirmLabel="Confirmar cobrança"
        tone="primary"
        busy={busy}
        error={actionError}
        onConfirm={runCollect}
        onCancel={() => {
          setCollectTarget(null);
          setActionError(null);
        }}
      />

      {stepUp.promptElement}
    </Card>
  );
}
