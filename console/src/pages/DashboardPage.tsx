// Dashboard do Master Console (Spec 71; Specs 15/34): transações recentes +
// resumo de receita da plataforma. Somente-leitura.

import { api } from '../apiClient';
import { Card, ErrorBanner, Spinner, formatDate, formatMoney, useAsync } from '../components/ui';
import type { PageResponse, PixTransaction, RevenueSummary } from '../types';

export default function DashboardPage() {
  const recent = useAsync(
    () => api.get<PageResponse<PixTransaction>>('/api/v1/master/transactions?page=0&size=5'),
    [],
  );
  const revenue = useAsync(() => api.get<RevenueSummary>('/api/v1/master/revenue/summary'), []);

  return (
    <div className="page">
      <h1>Dashboard</h1>

      <div className="metric-grid">
        <Card title="Receita total (plataforma)">
          <div className="metric-value big">{revenue.data ? formatMoney(revenue.data.total) : '—'}</div>
        </Card>
        <Card title="Líquido">
          <div className="metric-value">{revenue.data ? formatMoney(revenue.data.net) : '—'}</div>
        </Card>
        <Card title="Taxas de transação">
          <div className="metric-value">{revenue.data ? formatMoney(revenue.data.transactionFees) : '—'}</div>
        </Card>
        <Card title="Perdas (losses)">
          <div className="metric-value">{revenue.data ? formatMoney(revenue.data.losses) : '—'}</div>
        </Card>
      </div>
      <ErrorBanner error={revenue.error} />

      <Card title="Transações recentes">
        {recent.loading && <Spinner />}
        <ErrorBanner error={recent.error} />
        {recent.data && (
          <table className="table">
            <thead>
              <tr>
                <th>Lojista</th>
                <th>Valor</th>
                <th>Status</th>
                <th>Criada em</th>
              </tr>
            </thead>
            <tbody>
              {recent.data.items.length === 0 && (
                <tr>
                  <td colSpan={4} className="muted">
                    Nenhuma transação ainda.
                  </td>
                </tr>
              )}
              {recent.data.items.map((tx: PixTransaction) => (
                <tr key={tx.id}>
                  <td>{tx.merchantId.slice(0, 8)}…</td>
                  <td>{formatMoney(tx.amount)}</td>
                  <td>
                    <span className="tag">{tx.status}</span>
                  </td>
                  <td>{formatDate(tx.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
