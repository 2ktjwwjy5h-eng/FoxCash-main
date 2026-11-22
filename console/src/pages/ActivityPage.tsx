// Relatório de atividades (Spec 71; Spec 41 — GET /api/v1/master/report/activity):
// trilha unificada MASTER/MERCHANT/SYSTEM. Somente-leitura.

import { useState } from 'react';
import { api } from '../apiClient';
import { Card, ErrorBanner, Pagination, Spinner, formatDate, useAsync } from '../components/ui';
import type { PageResponse, PlatformActivity } from '../types';

export default function ActivityPage() {
  const [page, setPage] = useState(0);

  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<PlatformActivity>>(`/api/v1/master/report/activity?page=${page}&size=20`),
    [page],
  );

  return (
    <div className="page">
      <h1>Relatório de atividades</h1>
      <Card>
        {loading && <Spinner />}
        <ErrorBanner error={error} />
        {data && (
          <>
            <table className="table">
              <thead>
                <tr>
                  <th>Ator</th>
                  <th>Ação</th>
                  <th>Entidade</th>
                  <th>Lojista</th>
                  <th>Data</th>
                </tr>
              </thead>
              <tbody>
                {data.items.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">
                      Nenhuma atividade registrada.
                    </td>
                  </tr>
                )}
                {data.items.map((a: PlatformActivity) => (
                  <tr key={a.id}>
                    <td>{a.actorType}</td>
                    <td>{a.action}</td>
                    <td>{a.entityType}</td>
                    <td>{a.merchantId ? `${a.merchantId.slice(0, 8)}…` : '—'}</td>
                    <td>{formatDate(a.occurredAt)}</td>
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
