// Lojistas — Painel Geral de Usuários (Spec 99): tabela rica sobre o espelho
// read-only da Spec 97 (`GET /api/v1/master/merchants/panel`) — MED% colorido
// por faixa com contagem, taxa de conversão, Pix pagos, gateway efetivo e
// taxas aplicadas. Filtros status/q/período + risco; paginação server-side.
// Menu de ações por linha (MerchantActionsMenu) navega para detalhe/config.

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../apiClient';
import MerchantActionsMenu from '../components/MerchantActionsMenu';
import { Card, ErrorBanner, Pagination, Spinner, formatDate, formatMoney, useAsync } from '../components/ui';
import { formatPercent, medClass, statusClass, statusLabel } from '../merchantFormat';
import StoresModal from './StoresModal';
import type { MerchantPanelItem, PageResponse } from '../types';

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'Aprovado (ACTIVE)' },
  { value: 'SUSPENDED', label: 'Suspenso (SUSPENDED)' },
  { value: 'INACTIVE', label: 'Inativo (INACTIVE)' },
  { value: 'BANNED', label: 'Banido (BANNED)' },
];

const RISK_OPTIONS = [
  { value: 'LOW', label: 'Baixo (MED < 2%)' },
  { value: 'MEDIUM', label: 'Médio (MED 2–5%)' },
  { value: 'HIGH', label: 'Alto (MED > 5%)' },
];

export default function MerchantsPage() {
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [risk, setRisk] = useState('');
  const [page, setPage] = useState(0);
  const [shopsFor, setShopsFor] = useState<MerchantPanelItem | null>(null);

  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (status) params.set('status', status);
  if (q) params.set('q', q);
  if (from) params.set('from', `${from}T00:00:00Z`);
  if (to) params.set('to', `${to}T23:59:59Z`);
  if (risk) params.set('risk', risk);

  const { data, error, loading } = useAsync(
    () => api.get<PageResponse<MerchantPanelItem>>(`/api/v1/master/merchants/panel?${params.toString()}`),
    [params.toString()],
  );

  return (
    <div className="page">
      <h1>Lojistas</h1>
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
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="input input-small"
            value={risk}
            onChange={(e) => {
              setRisk(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Risco (todos)</option>
            {RISK_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <input
            className="input input-small"
            placeholder="Buscar por nome/e-mail…"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(0);
            }}
          />
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
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Lojista</th>
                    <th>Status</th>
                    <th>Taxas (Pix/Fixa/Saque)</th>
                    <th>Gateway</th>
                    <th>Conversão</th>
                    <th>Pix pagos</th>
                    <th>MED%</th>
                    <th>Criado em</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {data.items.length === 0 && (
                    <tr>
                      <td colSpan={9} className="muted">
                        Nenhum lojista no filtro.
                      </td>
                    </tr>
                  )}
                  {data.items.map((m: MerchantPanelItem) => (
                    <tr key={m.id}>
                      <td>
                        <strong>{m.name}</strong>
                        <div className="muted">{m.email}</div>
                      </td>
                      <td>
                        <span className={`tag ${statusClass(m.status)}`}>{statusLabel(m.status)}</span>
                      </td>
                      <td className="muted">
                        {formatMoney(m.transactionFee)} / {formatMoney(m.monthlyFee)} / {formatMoney(m.withdrawalFee)}
                      </td>
                      <td>{m.gateway || 'PADRAO'}</td>
                      <td>
                        <span title="Pix pagos ÷ cobranças criadas">{formatPercent(m.conversionRate)}</span>
                      </td>
                      <td>{m.paidPixCount}</td>
                      <td>
                        <span className={`tag ${medClass(m.medPercent)}`} title="Índice de devoluções/chargebacks">
                          {formatPercent(m.medPercent)} / {m.medOccurrences}
                        </span>
                      </td>
                      <td>{formatDate(m.createdAt)}</td>
                      <td>
                        {/* Link 'Detalhe →' preserva o contrato da Spec 71
                            (master-console-ui.spec.js) — além do menu Spec 99. */}
                        <Link className="btn btn-small" to={`/console/merchants/${m.id}`}>
                          Detalhe →
                        </Link>{' '}
                        <MerchantActionsMenu merchant={m} onViewShops={setShopsFor} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={data.page} pageCount={Math.max(data.totalPages, 1)} onChange={setPage} />
          </>
        )}
      </Card>

      {shopsFor && (
        <StoresModal merchantId={shopsFor.id} merchantName={shopsFor.name} onClose={() => setShopsFor(null)} />
      )}
    </div>
  );
}
