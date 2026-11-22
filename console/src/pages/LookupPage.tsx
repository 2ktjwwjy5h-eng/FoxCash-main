// Lookup por IP (Spec 71; Spec 67 — GET /api/v1/master/lookup/ip/{ip}): espelho
// read-only do !ip do bot; PII já mascarada no contrato.

import { useState, type FormEvent } from 'react';
import { api } from '../apiClient';
import { Card, ErrorBanner, formatDate, formatMoney } from '../components/ui';
import type { IpLookup } from '../types';

export default function LookupPage() {
  const [ip, setIp] = useState('');
  const [result, setResult] = useState<IpLookup | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setResult(null);
    setBusy(true);
    try {
      setResult(await api.get<IpLookup>(`/api/v1/master/lookup/ip/${encodeURIComponent(ip.trim())}`));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <h1>Lookup por IP</h1>
      <Card>
        <form className="form" onSubmit={handleSubmit}>
          <label className="field">
            <span className="field-label">Endereço IP (IPv4 ou IPv6)</span>
            <input
              className="input"
              placeholder="ex.: 192.168.1.10"
              value={ip}
              onChange={(e) => setIp(e.target.value)}
              required
            />
          </label>
          <div>
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? 'Consultando…' : 'Consultar'}
            </button>
          </div>
        </form>
        <ErrorBanner error={error} />
      </Card>

      {result && (
        <Card title={`Associações para ${result.ip}`}>
          {result.matches.length === 0 && <p className="muted">Nenhum lojista associado a este IP.</p>}
          {result.matches.map((m) => (
            <ul className="plain-list" key={m.merchantId}>
              <li>
                <span>Lojista</span> <strong>{m.name}</strong>
              </li>
              <li>
                <span>Status</span> <strong>{m.status}</strong>
              </li>
              <li>
                <span>Vendas (total)</span> <strong>{m.totalSales}</strong>
              </li>
              <li>
                <span>Ticket médio</span> <strong>{formatMoney(m.averageTicket)}</strong>
              </li>
              <li>
                <span>Última venda</span> <strong>{formatDate(m.lastSaleAt)}</strong>
              </li>
              <li>
                <span>Vendas 7d / receita 7d</span>{' '}
                <strong>
                  {m.sales7d} / {formatMoney(m.revenue7d)}
                </strong>
              </li>
              {m.bot && (
                <li>
                  <span>Bot da loja</span> <strong>{m.bot.storeName}</strong>
                </li>
              )}
            </ul>
          ))}
        </Card>
      )}
    </div>
  );
}
