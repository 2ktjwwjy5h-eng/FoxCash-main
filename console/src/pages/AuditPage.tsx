// Auditoria (Spec 71; Spec 37 — GET /api/v1/master/audit): trilha imutável das
// ações administrativas. Somente-leitura.

import { useState } from 'react';
import { api } from '../apiClient';
import { Card, ErrorBanner, Pagination, Spinner, formatDate, useAsync } from '../components/ui';
import type { AuditEntry, PageResponse } from '../types';

export default function AuditPage() {
  const [page, setPage] = useState(0);

  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<AuditEntry>>(`/api/v1/master/audit?page=${page}&size=20`),
    [page],
  );

  return (
    <div className="page">
      <h1>Auditoria</h1>
      <Card>
        {loading && <Spinner />}
        <ErrorBanner error={error} />
        {data && (
          <>
            <table className="table">
              <thead>
                <tr>
                  <th>Ação</th>
                  <th>Ator</th>
                  <th>Lojista</th>
                  <th>IP</th>
                  <th>Data</th>
                </tr>
              </thead>
              <tbody>
                {data.items.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">
                      Nenhuma entrada de auditoria.
                    </td>
                  </tr>
                )}
                {data.items.map((e: AuditEntry) => (
                  <tr key={e.id}>
                    <td>{e.action}</td>
                    <td>{e.actorId.slice(0, 8)}…</td>
                    <td>{e.merchantId ? `${e.merchantId.slice(0, 8)}…` : '—'}</td>
                    <td>{e.ip || '—'}</td>
                    <td>{formatDate(e.createdAt)}</td>
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
