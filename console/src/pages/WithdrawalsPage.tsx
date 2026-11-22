// Saques (Spec 71; Specs 14/32/36 — GET /api/v1/master/withdrawals): listagem
// com filtros + ações reversíveis de OPERATOR (block/release — Spec 14) com
// confirmação. A UI esconde botões sem a permissão (Spec 69).

import { useState } from 'react';
import { api } from '../apiClient';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Card, ErrorBanner, Pagination, Spinner, formatDate, formatMoney, useAsync } from '../components/ui';
import { RequirePermission } from '../hooks/usePermission';
import type { PageResponse, Withdrawal } from '../types';

export default function WithdrawalsPage() {
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [blockTarget, setBlockTarget] = useState<{ merchantId: string; action: 'block' | 'release' } | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);

  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (status) params.set('status', status);
  if (from) params.set('from', `${from}T00:00:00Z`);
  if (to) params.set('to', `${to}T23:59:59Z`);

  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<Withdrawal>>(`/api/v1/master/withdrawals?${params.toString()}`),
    [params.toString(), refresh],
  );

  const runBlockAction = async () => {
    if (!blockTarget) return;
    setBusy(true);
    setActionError(null);
    try {
      await api.post<void>(`/api/v1/master/merchants/${blockTarget.merchantId}/withdrawals/${blockTarget.action}`);
      setFeedback(`Saques do lojista ${blockTarget.action === 'block' ? 'bloqueados' : 'liberados'}.`);
      setBlockTarget(null);
      setRefresh((r) => r + 1);
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <h1>Saques</h1>
      {feedback && <div className="alert alert-success">{feedback}</div>}

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
            <option value="PROCESSING">PROCESSING</option>
            <option value="COMPLETED">COMPLETED</option>
            <option value="FAILED">FAILED</option>
            <option value="REJECTED">REJECTED</option>
            <option value="PENDING">PENDING</option>
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
                  <th>Lojista</th>
                  <th>Valor</th>
                  <th>Status</th>
                  <th>Tipo</th>
                  <th>Data</th>
                  <th>Controle</th>
                </tr>
              </thead>
              <tbody>
                {data.items.length === 0 && (
                  <tr>
                    <td colSpan={6} className="muted">
                      Nenhum saque no filtro.
                    </td>
                  </tr>
                )}
                {data.items.map((w: Withdrawal) => (
                  <tr key={w.id}>
                    <td>{w.merchantId.slice(0, 8)}…</td>
                    <td>{formatMoney(w.amount)}</td>
                    <td>
                      <span className="tag">{w.status}</span>
                    </td>
                    <td>{w.type}</td>
                    <td>{formatDate(w.createdAt)}</td>
                    <td>
                      <RequirePermission permission="withdrawal.block">
                        <button
                          type="button"
                          className="btn btn-small btn-danger"
                          onClick={() => setBlockTarget({ merchantId: w.merchantId, action: 'block' })}
                        >
                          Bloquear saques
                        </button>
                      </RequirePermission>{' '}
                      <RequirePermission permission="withdrawal.release">
                        <button
                          type="button"
                          className="btn btn-small"
                          onClick={() => setBlockTarget({ merchantId: w.merchantId, action: 'release' })}
                        >
                          Liberar saques
                        </button>
                      </RequirePermission>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
          </>
        )}
      </Card>

      <ConfirmDialog
        open={blockTarget !== null}
        title={blockTarget?.action === 'block' ? 'Bloquear saques' : 'Liberar saques'}
        message={
          blockTarget
            ? `${blockTarget.action === 'block' ? 'Bloquear' : 'Liberar'} os saques do lojista ${blockTarget.merchantId.slice(0, 8)}…?`
            : ''
        }
        confirmLabel={blockTarget?.action === 'block' ? 'Bloquear' : 'Liberar'}
        busy={busy}
        error={actionError}
        onConfirm={runBlockAction}
        onCancel={() => {
          setBlockTarget(null);
          setActionError(null);
        }}
      />
    </div>
  );
}
