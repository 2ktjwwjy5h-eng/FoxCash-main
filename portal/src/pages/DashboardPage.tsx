// Dashboard do lojista (Spec 21) — métricas de topo, série de faturamento,
// últimas vendas e pontos de atenção, consumidos de /api/v1/portal/dashboard/*.

import { Card, ErrorBanner, Spinner, formatMoney, useAsync } from '../components/ui';
import { api } from '../apiClient';

interface Summary {
  grossRevenue: number;
  sales: number;
  pixConversionRate: number;
  journeyProgress: number;
}
interface TimeSeriesPoint {
  label: string;
  value: number;
}
interface RecentSale {
  transactionId: string;
  amount: number;
  status: string;
  createdAt: string;
  buyerIdHash: string;
}
interface AttentionPoint {
  type: string;
  severity: string;
  message: string;
}

export default function DashboardPage() {
  const summary = useAsync<Summary>(() => api.get('/api/v1/portal/dashboard/summary'), []);
  const series = useAsync<TimeSeriesPoint[]>(() => api.get('/api/v1/portal/dashboard/revenue-series'), []);
  const recent = useAsync<RecentSale[]>(() => api.get('/api/v1/portal/dashboard/recent-sales'), []);
  const attention = useAsync<AttentionPoint[]>(() => api.get('/api/v1/portal/dashboard/attention-points'), []);

  if (summary.loading) return <Spinner />;
  if (summary.error) return <ErrorBanner error={summary.error} />;

  const s = summary.data!;

  return (
    <div className="page">
      <h1>Dashboard</h1>
      <div className="metric-grid">
        <Card title="Faturamento bruto">
          <div className="metric-value">{formatMoney(s.grossRevenue)}</div>
        </Card>
        <Card title="Vendas">
          <div className="metric-value">{s.sales}</div>
        </Card>
        <Card title="Conversão Pix">
          <div className="metric-value">{(Number(s.pixConversionRate) * 100).toFixed(1)}%</div>
        </Card>
        <Card title="Progresso da jornada">
          <div className="metric-value">{s.journeyProgress}%</div>
        </Card>
      </div>

      <div className="grid-2">
        <Card title="Faturamento (últimos dias)">
          {series.loading ? (
            <Spinner />
          ) : (
            <ul className="series-list">
              {(series.data || []).map((p) => (
                <li key={p.label}>
                  <span>{p.label}</span>
                  <span>{formatMoney(p.value)}</span>
                </li>
              ))}
              {(series.data || []).length === 0 && <li className="muted">Sem dados de faturamento.</li>}
            </ul>
          )}
        </Card>

        <Card title="Últimas vendas">
          {recent.loading ? (
            <Spinner />
          ) : (
            <ul className="plain-list">
              {(recent.data || []).map((sale) => (
                <li key={sale.transactionId}>
                  <span>{formatMoney(sale.amount)}</span>
                  <span className="tag">{sale.status}</span>
                </li>
              ))}
              {(recent.data || []).length === 0 && <li className="muted">Nenhuma venda ainda.</li>}
            </ul>
          )}
        </Card>
      </div>

      {attention.data && attention.data.length > 0 && (
        <Card title="Pontos de atenção">
          <ul className="plain-list">
            {attention.data.map((p, i) => (
              <li key={i} className={`alert alert-${p.severity}`}>
                {p.message}
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}
