// Painel "Bots" do Master Console (Spec 76) — espelho READ-ONLY do registro
// central (Spec 74) + métricas por bot (Spec 75), para todos os lojistas:
//   - GET /api/v1/master/bots          → lista com nome do lojista
//   - GET /api/v1/master/bots/metrics  → leads/vendas/receita/conversão
//
// RBAC Spec 69: leitura = OPERATOR (todo master ativo enxerga). SEM ações de
// escrita — nenhum botão de mutação (o backend continua a autoridade; 403 não
// é exposto como erro porque a UI não oferece a ação).

import { useState } from 'react';
import { api } from '../apiClient';
import { Card, ErrorBanner, Spinner, formatMoney, useAsync } from '../components/ui';

interface MasterBot {
  id: string;
  merchantId: string;
  merchantName: string | null;
  platform: string;
  status: string;
  displayName: string;
  username: string | null;
  applicationId: string | null;
  tokenMask: string | null;
  errorMessage: string | null;
  lastHealthCheckAt: string | null;
  createdAt: string;
  updatedAt: string;
}

interface BotMetrics {
  botId: string;
  merchantId: string | null;
  merchantName: string | null;
  leads: number;
  vendas: number;
  receita: number | string;
  conversao: number;
}

interface BotMetricsAggregate {
  bots: BotMetrics[];
  total: { leads: number; vendas: number; receita: number | string; conversao: number };
}

const STATUS_LABEL: Record<string, string> = {
  ACTIVE: 'ONLINE',
  PAUSED: 'OFFLINE',
  REGISTERING: 'CONECTANDO',
  ERROR: 'ERRO',
};

const STATUS_CLASS: Record<string, string> = {
  ACTIVE: 'tag-success',
  PAUSED: 'tag-neutral',
  REGISTERING: 'tag-warning',
  ERROR: 'tag-danger',
};

export default function BotsPage() {
  const { data, error, loading } = useAsync(
    async () => {
      const [bots, metrics] = await Promise.all([
        api.get<MasterBot[]>('/api/v1/master/bots'),
        api.get<BotMetricsAggregate>('/api/v1/master/bots/metrics'),
      ]);
      return { bots, metrics };
    },
    [],
  );

  const [platform, setPlatform] = useState('ALL');
  const [status, setStatus] = useState('ALL');
  const [merchant, setMerchant] = useState('');

  const metricsByBot = new Map((data?.metrics.bots || []).map((m) => [m.botId, m]));

  const rows = (data?.bots || []).filter((b) => {
    if (platform !== 'ALL' && b.platform !== platform) return false;
    if (status !== 'ALL' && b.status !== status) return false;
    if (merchant.trim() && !(b.merchantName || '').toLowerCase().includes(merchant.trim().toLowerCase())) {
      return false;
    }
    return true;
  });

  return (
    <div className="page">
      <h1>Bots</h1>

      <div className="filter-row">
        <select
          className="input input-small"
          value={platform}
          aria-label="Filtrar por plataforma"
          onChange={(e) => setPlatform(e.target.value)}
        >
          <option value="ALL">Plataforma (todas)</option>
          <option value="TELEGRAM">Telegram</option>
          <option value="DISCORD">Discord</option>
        </select>
        <select
          className="input input-small"
          value={status}
          aria-label="Filtrar por status"
          onChange={(e) => setStatus(e.target.value)}
        >
          <option value="ALL">Status (todos)</option>
          <option value="ACTIVE">ONLINE</option>
          <option value="PAUSED">OFFLINE</option>
          <option value="REGISTERING">CONECTANDO</option>
          <option value="ERROR">ERRO</option>
        </select>
        <input
          className="input input-small"
          type="search"
          placeholder="Filtrar por lojista…"
          value={merchant}
          onChange={(e) => setMerchant(e.target.value)}
        />
      </div>

      <Card>
        {loading && <Spinner />}
        <ErrorBanner error={error} />
        {data && (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Lojista</th>
                  <th>Bot</th>
                  <th>Plataforma</th>
                  <th>Status</th>
                  <th>Leads</th>
                  <th>Vendas</th>
                  <th>Receita</th>
                  <th>Conversão</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={8} className="muted">
                      Nenhum bot encontrado.
                    </td>
                  </tr>
                )}
                {rows.map((bot) => {
                  const metrics = metricsByBot.get(bot.id);
                  return (
                    <tr key={bot.id}>
                      <td>{bot.merchantName || bot.merchantId.slice(0, 8)}</td>
                      <td>
                        <strong>{bot.displayName}</strong>
                        {bot.username ? <div className="muted">@{bot.username}</div> : null}
                      </td>
                      <td>{bot.platform}</td>
                      <td>
                        <span className={`tag ${STATUS_CLASS[bot.status] || ''}`}>
                          {STATUS_LABEL[bot.status] || bot.status}
                        </span>
                      </td>
                      <td>{metrics?.leads ?? 0}</td>
                      <td>{metrics?.vendas ?? 0}</td>
                      <td>{formatMoney(metrics?.receita ?? 0)}</td>
                      <td>{metrics ? `${(metrics.conversao * 100).toFixed(0)}%` : '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
