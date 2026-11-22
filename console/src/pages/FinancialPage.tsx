// Financeiro do master (Spec 71; Spec 34 — GET /api/v1/master/revenue/summary e
// /ledger): resumo de receita/perda + ledger da plataforma. Somente-leitura.
// Spec 72 (fatia 2): seção "Saque administrativo" (Spec 32 — ação sensível com
// step-up TOTP no cliente).

import { useState } from 'react';
import { api } from '../apiClient';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Card, ErrorBanner, Field, Pagination, Spinner, SuccessBanner, formatDate, formatMoney, useAsync } from '../components/ui';
import { RequirePermission } from '../hooks/usePermission';
import { useStepUp } from '../hooks/useStepUp';
import type { MasterLedgerEntry, Merchant, PageResponse, RevenueSummary, Withdrawal } from '../types';

interface AdminWithdrawForm {
  merchantId: string;
  amount: string;
  destinationKey: string;
  reason: string;
}

const EMPTY_FORM: AdminWithdrawForm = { merchantId: '', amount: '', destinationKey: '', reason: '' };

export default function FinancialPage() {
  const summary = useAsync(() => api.get<RevenueSummary>('/api/v1/master/revenue/summary'), []);
  const [page, setPage] = useState(0);
  const ledger = useAsync(
    () => api.get<PageResponse<MasterLedgerEntry>>(`/api/v1/master/revenue/ledger?page=${page}&size=20`),
    [page],
  );

  const merchants = useAsync(() => api.get<PageResponse<Merchant>>('/api/v1/master/merchants?page=0&size=100'), []);

  const stepUp = useStepUp();
  const [form, setForm] = useState<AdminWithdrawForm>(EMPTY_FORM);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const runAdminWithdraw = async () => {
    setBusy(true);
    setActionError(null);
    setFeedback(null);
    try {
      const result = await stepUp.run(() =>
        api.post<Withdrawal>(`/api/v1/master/merchants/${form.merchantId}/withdrawals/administrative`, {
          amount: form.amount,
          destinationKey: form.destinationKey,
          reason: form.reason,
        }),
      );
      setFeedback(`Saque administrativo de ${formatMoney(result.amount)} registrado. Saldo pós-débito: ${formatMoney(result.walletBalance ?? 0)}.`);
      setConfirmOpen(false);
      setForm(EMPTY_FORM);
      summary.reload();
      ledger.reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  const formValid = form.merchantId && Number(form.amount) > 0 && form.destinationKey.trim() && form.reason.trim();

  return (
    <div className="page">
      <h1>Financeiro do master</h1>
      <SuccessBanner message={feedback} />

      <div className="metric-grid">
        <Card title="Receita total">
          <div className="metric-value big">{summary.data ? formatMoney(summary.data.total) : '—'}</div>
        </Card>
        <Card title="Líquido">
          <div className="metric-value">{summary.data ? formatMoney(summary.data.net) : '—'}</div>
        </Card>
        <Card title="Taxas de transação">
          <div className="metric-value">{summary.data ? formatMoney(summary.data.transactionFees) : '—'}</div>
        </Card>
        <Card title="Taxas mensais">
          <div className="metric-value">{summary.data ? formatMoney(summary.data.monthlyFees) : '—'}</div>
        </Card>
        <Card title="Recuperações">
          <div className="metric-value">{summary.data ? formatMoney(summary.data.recoveries) : '—'}</div>
        </Card>
        <Card title="Perdas">
          <div className="metric-value">{summary.data ? formatMoney(summary.data.losses) : '—'}</div>
        </Card>
      </div>
      <ErrorBanner error={summary.error} />

      <RequirePermission permission="withdrawal.administrative">
        <Card title="Saque administrativo (Spec 32)">
          <p className="muted">Recolhe o saldo do lojista para a plataforma (ação sensível — exige reautenticação TOTP).</p>
          <div className="grid-2">
            <label className="field">
              <span className="field-label">Lojista</span>
              <select
                className="input"
                value={form.merchantId}
                disabled={merchants.loading}
                onChange={(e) => setForm((f) => ({ ...f, merchantId: e.target.value }))}
              >
                <option value="">{merchants.loading ? 'Carregando lojistas…' : 'Selecione o lojista'}</option>
                {(merchants.data?.items ?? []).map((m: Merchant) => (
                  <option key={m.id} value={m.id}>
                    {m.name} ({m.id.slice(0, 8)}…)
                  </option>
                ))}
              </select>
            </label>
            <Field
              label="Valor (R$)"
              type="number"
              min="0.01"
              step="0.01"
              required
              value={form.amount}
              onChange={(e) => setForm((f) => ({ ...f, amount: e.target.value }))}
              placeholder="0.00"
            />
            <Field
              label="Chave Pix de destino"
              required
              value={form.destinationKey}
              onChange={(e) => setForm((f) => ({ ...f, destinationKey: e.target.value }))}
              placeholder="CPF/CNPJ/e-mail/telefone/EVP"
            />
            <Field
              label="Motivo (obrigatório)"
              required
              value={form.reason}
              onChange={(e) => setForm((f) => ({ ...f, reason: e.target.value }))}
              placeholder="Ex.: recolhimento administrativo"
            />
          </div>
          <div className="modal-actions" style={{ marginTop: 8 }}>
            <button type="button" className="btn btn-danger" disabled={!formValid} onClick={() => setConfirmOpen(true)}>
              Realizar saque administrativo
            </button>
          </div>
          <ErrorBanner error={merchants.error} />
        </Card>
      </RequirePermission>

      <Card title="Ledger da plataforma">
        {ledger.loading && <Spinner />}
        <ErrorBanner error={ledger.error} />
        {ledger.data && (
          <>
            <table className="table">
              <thead>
                <tr>
                  <th>Tipo</th>
                  <th>Lojista</th>
                  <th>Valor</th>
                  <th>Saldo após</th>
                  <th>Data</th>
                </tr>
              </thead>
              <tbody>
                {ledger.data.items.map((e: MasterLedgerEntry) => (
                  <tr key={e.id}>
                    <td>{e.type}</td>
                    <td>{e.merchantId.slice(0, 8)}…</td>
                    <td>{formatMoney(e.amount)}</td>
                    <td>{formatMoney(e.balanceAfter)}</td>
                    <td>{formatDate(e.createdAt)}</td>
                  </tr>
                ))}
                {ledger.data.items.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">
                      Nenhum lançamento no ledger.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
            <Pagination page={ledger.data.page} pageCount={Math.max(ledger.data.totalPages, 1)} onChange={setPage} />
          </>
        )}
      </Card>

      <ConfirmDialog
        open={confirmOpen}
        title="Saque administrativo"
        message={`Recolher ${formatMoney(form.amount)} do lojista ${form.merchantId.slice(0, 8)}… para a chave Pix informada? Ação sensível — exige reautenticação (TOTP).`}
        confirmLabel="Confirmar saque"
        tone="primary"
        busy={busy}
        error={actionError}
        onConfirm={runAdminWithdraw}
        onCancel={() => {
          setConfirmOpen(false);
          setActionError(null);
        }}
      />

      {stepUp.promptElement}
    </div>
  );
}
