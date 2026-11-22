// Reconciliação (Spec 71; Spec 30 — GET /api/v1/master/reconciliation/issues):
// issues de divergência saldo×ledger. READ-ONLY na fatia 1 (sem botão run —
// D7.7: o run não é ação permissionada no catálogo da Spec 69).

import { useState } from 'react';
import { api } from '../apiClient';
import { Card, ErrorBanner, Pagination, Spinner, formatDate, formatMoney, useAsync } from '../components/ui';
import type { PageResponse, ReconciliationIssue } from '../types';

export default function ReconciliationPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);

  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (status) params.set('status', status);

  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<ReconciliationIssue>>(`/api/v1/master/reconciliation/issues?${params.toString()}`),
    [params.toString()],
  );

  return (
    <div className="page">
      <h1>Reconciliação</h1>
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
            <option value="OPEN">OPEN</option>
            <option value="RESOLVED">RESOLVED</option>
          </select>
        </div>

        {loading && <Spinner />}
        <ErrorBanner error={error} />
        {data && (
          <>
            <table className="table">
              <thead>
                <tr>
                  <th>Lojista</th>
                  <th>Esperado</th>
                  <th>Real</th>
                  <th>Status</th>
                  <th>Detectado em</th>
                </tr>
              </thead>
              <tbody>
                {data.items.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">
                      Nenhuma issue de reconciliação (zona sagrada OK).
                    </td>
                  </tr>
                )}
                {data.items.map((i: ReconciliationIssue) => (
                  <tr key={i.issueId}>
                    <td>{i.merchantId.slice(0, 8)}…</td>
                    <td>{formatMoney(i.expectedBalance)}</td>
                    <td>{formatMoney(i.actualBalance)}</td>
                    <td>
                      <span className="tag">{i.status}</span>
                    </td>
                    <td>{formatDate(i.detectedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
          </>
        )}
      </Card>
    </div>
  );
}
