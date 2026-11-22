// Transações (Spec 71; Spec 15 — GET /api/v1/master/transactions): listagem
// paginada com filtros de status/período. Somente-leitura. O backend exige
// ISO-8601 com Z (Instant.parse) — os inputs date enviam 00:00:00Z/23:59:59Z.

import { useState } from 'react';
import { api } from '../apiClient';
import { Card, ErrorBanner, Pagination, Spinner, formatDate, formatMoney, useAsync } from '../components/ui';
import type { PageResponse, PixTransaction } from '../types';

export default function TransactionsPage() {
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);

  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (status) params.set('status', status);
  if (from) params.set('from', `${from}T00:00:00Z`);
  if (to) params.set('to', `${to}T23:59:59Z`);

  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<PixTransaction>>(`/api/v1/master/transactions?${params.toString()}`),
    [params.toString()],
  );

  return (
    <div className="page">
      <h1>Transações</h1>
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
            <option value="PENDING">PENDING</option>
            <option value="APPROVED">APPROVED</option>
            <option value="REFUNDED">REFUNDED</option>
            <option value="CHARGEBACK">CHARGEBACK</option>
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
            <table className="table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Lojista</th>
                  <th>Valor</th>
                  <th>Status</th>
                  <th>Criada em</th>
                </tr>
              </thead>
              <tbody>
                {data.items.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">
                      Nenhuma transação no filtro.
                    </td>
                  </tr>
                )}
                {data.items.map((tx: PixTransaction) => (
                  <tr key={tx.id}>
                    <td>{tx.id.slice(0, 8)}…</td>
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
            <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
          </>
        )}
      </Card>
    </div>
  );
}
