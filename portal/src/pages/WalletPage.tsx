// Carteira/financeiro (Spec 22) — saldo, resumo do mês, entradas da semana e
// fluxo de caixa, consumidos de /api/v1/portal/wallet/*.

import { Card, ErrorBanner, Spinner, formatMoney, useAsync } from '../components/ui';
import { api } from '../apiClient';

interface Balance {
  walletBalance: number;
  withdrawalsBlocked: boolean;
  saldoCalculadoPelaApi?: boolean;
}
interface MonthlySummary {
  grossRevenue: number;
  netRevenue: number;
  transactionCount: number;
}
interface TimeSeriesPoint {
  label: string;
  value: number;
}
interface CashFlow {
  inflows: number;
  outflows: number;
  net: number;
}

const currentMonth = new Date().toISOString().slice(0, 7);

export default function WalletPage() {
  const balance = useAsync<Balance>(() => api.get('/api/v1/portal/wallet/balance'), []);
  const monthly = useAsync<MonthlySummary>(
    () => api.get(`/api/v1/portal/wallet/monthly-summary?month=${currentMonth}`),
    [],
  );
  const inflows = useAsync<TimeSeriesPoint[]>(() => api.get('/api/v1/portal/wallet/weekly-inflows'), []);
  const cashflow = useAsync<CashFlow>(() => api.get('/api/v1/portal/wallet/cash-flow'), []);

  if (balance.loading) return <Spinner />;
  if (balance.error) return <ErrorBanner error={balance.error} />;

  return (
    <div className="page">
      <h1>Carteira</h1>
      <Card title="Saldo disponível">
        <div className="metric-value big">{formatMoney(balance.data?.walletBalance)}</div>
        {balance.data?.withdrawalsBlocked && (
          <div className="alert alert-warning">Saques bloqueados pela administração.</div>
        )}
      </Card>

      <div className="grid-2">
        <Card title={`Resumo de ${currentMonth}`}>
          {monthly.loading ? (
            <Spinner />
          ) : (
            <ul className="plain-list">
              <li>
                <span>Faturamento bruto</span>
                <span>{formatMoney(monthly.data?.grossRevenue)}</span>
              </li>
              <li>
                <span>Faturamento líquido</span>
                <span>{formatMoney(monthly.data?.netRevenue)}</span>
              </li>
              <li>
                <span>Transações</span>
                <span>{monthly.data?.transactionCount}</span>
              </li>
            </ul>
          )}
        </Card>

        <Card title="Fluxo de caixa (semana)">
          {cashflow.loading ? (
            <Spinner />
          ) : (
            <ul className="plain-list">
              <li>
                <span>Entradas</span>
                <span>{formatMoney(cashflow.data?.inflows)}</span>
              </li>
              <li>
                <span>Saídas</span>
                <span>{formatMoney(cashflow.data?.outflows)}</span>
              </li>
              <li>
                <span>Líquido</span>
                <span>{formatMoney(cashflow.data?.net)}</span>
              </li>
            </ul>
          )}
        </Card>
      </div>

      <Card title="Entradas da semana">
        {inflows.loading ? (
          <Spinner />
        ) : (
          <ul className="series-list">
            {(inflows.data || []).map((p) => (
              <li key={p.label}>
                <span>{p.label}</span>
                <span>{formatMoney(p.value)}</span>
              </li>
            ))}
            {(inflows.data || []).length === 0 && <li className="muted">Nenhuma entrada na semana.</li>}
          </ul>
        )}
      </Card>
    </div>
  );
}
