// Vendas/operação comercial (Spec 25) — listagem com filtros e métricas de
// topo, consumidos de /api/v1/portal/sales (PageResponse) e /sales/metrics.

import { useEffect, useState } from 'react';
import { Card, ErrorBanner, Spinner, formatMoney, useAsync } from '../components/ui';
import { api } from '../apiClient';

interface Sale {
  transactionId: string;
  buyerIdHash: string;
  method: string;
  grossAmount: number;
  netAmount: number;
  status: string;
  createdAt: string;
}
interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
interface SalesMetrics {
  volume30d: number;
  completedSales: number;
  pendingSales: number;
  conversionRate: number;
}

const PAGE_SIZE = 20;

export default function SalesPage() {
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');

  const metrics = useAsync<SalesMetrics>(() => api.get('/api/v1/portal/sales/metrics'), []);
  const list = useAsync<PageResponse<Sale>>(
    () =>
      api.get(
        `/api/v1/portal/sales?page=${page}&size=${PAGE_SIZE}${statusFilter ? `&status=${statusFilter}` : ''}`,
      ),
    [page, statusFilter],
  );

  useEffect(() => {
    void list.reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, statusFilter]);

  if (metrics.loading) return <Spinner />;
  if (metrics.error) return <ErrorBanner error={metrics.error} />;

  const data = list.data;

  return (
    <div className="page">
      <h1>Vendas</h1>
      <div className="metric-grid">
        <Card title="Volume (30d)">
          <div className="metric-value">{formatMoney(metrics.data?.volume30d)}</div>
        </Card>
        <Card title="Concluídas">
          <div className="metric-value">{metrics.data?.completedSales}</div>
        </Card>
        <Card title="Pendentes">
          <div className="metric-value">{metrics.data?.pendingSales}</div>
        </Card>
        <Card title="Conversão">
          <div className="metric-value">
            {(Number(metrics.data?.conversionRate ?? 0) * 100).toFixed(1)}%
          </div>
        </Card>
      </div>

      <Card title="Transações">
        <div className="filter-row">
          <label>
            Status:
            <select
              className="input"
              value={statusFilter}
              onChange={(e) => {
                setPage(0);
                setStatusFilter(e.target.value);
              }}
            >
              <option value="">Todos</option>
              <option value="PENDING">PENDING</option>
              <option value="APPROVED">APPROVED</option>
              <option value="REJECTED">REJECTED</option>
              <option value="REFUNDED">REFUNDED</option>
            </select>
          </label>
        </div>

        {list.loading ? (
          <Spinner />
        ) : list.error ? (
          <ErrorBanner error={list.error} />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Data</th>
                <th>Valor bruto</th>
                <th>Líquido</th>
                <th>Método</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {(data?.items || []).map((sale) => (
                <tr key={sale.transactionId}>
                  <td>{new Date(sale.createdAt).toLocaleString('pt-BR')}</td>
                  <td>{formatMoney(sale.grossAmount)}</td>
                  <td>{formatMoney(sale.netAmount)}</td>
                  <td>{sale.method}</td>
                  <td>
                    <span className="tag">{sale.status}</span>
                  </td>
                </tr>
              ))}
              {(data?.items || []).length === 0 && (
                <tr>
                  <td colSpan={5} className="muted">
                    Nenhuma venda no período.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        )}

        {data && data.totalPages > 1 && (
          <div className="pagination">
            <button
              type="button"
              className="btn btn-small"
              disabled={page <= 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              Anterior
            </button>
            <span>
              Página {data.page + 1} de {data.totalPages}
            </span>
            <button
              type="button"
              className="btn btn-small"
              disabled={page >= data.totalPages - 1}
              onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))}
            >
              Próxima
            </button>
          </div>
        )}
      </Card>
    </div>
  );
}
