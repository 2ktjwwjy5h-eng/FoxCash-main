// Receivables (Spec 71; Spec 33 — GET /api/v1/master/receivables): leitura
// paginada + write-off (Spec 34 — ação sensível, fatia 2/Spec 72) com step-up
// TOTP no cliente (403 step_up_required → prompt → repete).

import { useState } from 'react';
import { api } from '../apiClient';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Card, ErrorBanner, Pagination, Spinner, SuccessBanner, formatDate, formatMoney, useAsync } from '../components/ui';
import { RequirePermission } from '../hooks/usePermission';
import { useStepUp } from '../hooks/useStepUp';
import type { PageResponse, Receivable, WriteOffResult } from '../types';

interface WriteOffTarget {
  receivable: Receivable;
  reason: string;
}

export default function ReceivablesPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const stepUp = useStepUp();

  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (status) params.set('status', status);

  const { data, error, loading, reload } = useAsync(
    () => api.get<PageResponse<Receivable>>(`/api/v1/master/receivables?${params.toString()}`),
    [params.toString()],
  );

  const [writeOffTarget, setWriteOffTarget] = useState<WriteOffTarget | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const runWriteOff = async () => {
    if (!writeOffTarget) return;
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      const result = await stepUp.run(() =>
        api.post<WriteOffResult>(`/api/v1/master/receivables/${writeOffTarget.receivable.id}/write-off`, {
          reason: writeOffTarget.reason,
        }),
      );
      setFeedback(`Baixa de ${formatMoney(result.loss)} registrada.`);
      setWriteOffTarget(null);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <h1>Receivables</h1>
      <SuccessBanner message={feedback} />
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
            <option value="PARTIAL">PARTIAL</option>
            <option value="PAID">PAID</option>
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
                  <th>Origem</th>
                  <th>Valor</th>
                  <th>Pago</th>
                  <th>Status</th>
                  <th>Criado em</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.items.length === 0 && (
                  <tr>
                    <td colSpan={7} className="muted">
                      Nenhum receivable no filtro.
                    </td>
                  </tr>
                )}
                {data.items.map((r: Receivable) => {
                  const canWriteOff = r.status === 'OPEN' || r.status === 'PARTIAL';
                  return (
                    <tr key={r.id}>
                      <td>{r.merchantId.slice(0, 8)}…</td>
                      <td>{r.origin}</td>
                      <td>{formatMoney(r.amount)}</td>
                      <td>{formatMoney(r.paidAmount)}</td>
                      <td>
                        <span className="tag">{r.status}</span>
                      </td>
                      <td>{formatDate(r.createdAt)}</td>
                      <td>
                        {canWriteOff && (
                          <RequirePermission permission="receivable.write_off">
                            <button
                              type="button"
                              className="btn btn-small btn-danger"
                              onClick={() => {
                                setActionError(null);
                                setWriteOffTarget({ receivable: r, reason: '' });
                              }}
                            >
                              Write-off
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
      </Card>

      <ConfirmDialog
        open={writeOffTarget !== null}
        title="Write-off de receivable"
        message={`Registrar baixa (perda) do receivable de ${formatMoney(writeOffTarget?.receivable.amount)} (${writeOffTarget?.receivable.origin})? Esta ação é sensível e exige reautenticação (TOTP).`}
        confirmLabel="Confirmar baixa"
        busy={busy}
        error={actionError}
        onConfirm={runWriteOff}
        onCancel={() => {
          setWriteOffTarget(null);
          setActionError(null);
        }}
      >
        <label className="field">
          <span className="field-label">Motivo (obrigatório)</span>
          <input
            className="input"
            value={writeOffTarget?.reason ?? ''}
            onChange={(e) => setWriteOffTarget((t) => (t ? { ...t, reason: e.target.value } : t))}
            placeholder="Ex.: dívida incobrável"
          />
        </label>
      </ConfirmDialog>

      {stepUp.promptElement}
    </div>
  );
}
