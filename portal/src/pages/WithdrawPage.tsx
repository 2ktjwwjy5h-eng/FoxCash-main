// Saque com destino selecionado (Spec 32/36) — POST
// /api/v1/merchants/{merchantId}/withdrawals { amount, destinationId } + listagem.
// Spec 70: card "Saque automático" (GET/PUT /api/v1/portal/auto-withdraw).
// Spec 121: seção "Pagamento via Saldo" (Spec 120) — pagar Pix Copia e Cola
// estático com o saldo (POST /api/v1/portal/pvs/pay + GET /api/v1/portal/pvs/limits).

import { useEffect, useState, type FormEvent } from 'react';
import { Card, ErrorBanner, Field, Spinner, formatMoney, useAsync } from '../components/ui';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { api, ApiError } from '../apiClient';
import { useAuth } from '../auth';

interface Balance {
  walletBalance: number;
  withdrawalsBlocked: boolean;
}
interface Destination {
  id: string;
  type: string;
  label: string;
  mask: string;
  status: string;
}
interface Withdrawal {
  id: string;
  amount: number;
  status: string;
  destination: string;
  createdAt: string;
}
interface AutoWithdrawConfig {
  enabled: boolean;
  threshold: number;
  destinationId: string | null;
}
// Spec 120/121: limites do Pagamento via Saldo (GET /api/v1/portal/pvs/limits).
interface PvsLimits {
  dailyCap: number;
  usedToday: number;
  remaining: number;
  maxPerPayment: number;
}
// Prévia do pagamento (best-effort no cliente — o backend valida o brcode).
interface PvsPreview {
  keyMask: string;
  amount: number | null; // null = o EMV não informa valor → usuário digita
  fee: number;
}

/** Varredura TLV do EMV BR Code (id 2 + len 2 + value) — best-effort para a
 *  prévia do PVS (Spec 121). O BACKEND valida de verdade (CRC/tipo/dinâmico).
 *  Devolve um mapa id → value; malformado → mapa vazio. */
function walkTlv(data: string, from: number, to: number): Map<string, string> {
  const fields = new Map<string, string>();
  let i = from;
  while (i + 4 <= to) {
    const len = Number(data.slice(i + 2, i + 4));
    if (!Number.isInteger(len) || i + 4 + len > to) break;
    fields.set(data.slice(i, i + 2), data.slice(i + 4, i + 4 + len));
    i += 4 + len;
  }
  return fields;
}

/** Extrai chave (26.01), valor (54) e flag de dinâmico (26.25/80/81) de um Pix
 *  Copia e Cola para a prévia. Sem CRC — só o shape TLV (o backend decide). */
function parsePixForPreview(
  pixCode: string,
): { key: string | null; amount: number | null; dynamic: boolean } {
  if (!pixCode?.startsWith('000201')) return { key: null, amount: null, dynamic: false };
  const root = walkTlv(pixCode, 0, pixCode.length);
  if (root.has('80') || root.has('81')) return { key: null, amount: null, dynamic: true };
  const merchantAccount = root.get('26');
  let key: string | null = null;
  let dynamic = false;
  if (merchantAccount) {
    const sub = walkTlv(merchantAccount, 0, merchantAccount.length);
    dynamic = sub.has('25');
    key = sub.get('01') || null;
  }
  const rawAmount = root.get('54');
  const amount = rawAmount ? Number(rawAmount.replace(',', '.')) : null;
  return { key, amount: amount !== null && Number.isFinite(amount) ? amount : null, dynamic };
}

/** Máscara de chave Pix (ex.: 529***725) — mesmo padrão da listagem de saques. */
function maskKey(key: string): string {
  if (key.length <= 4) return '***';
  return `${key.slice(0, 3)}***${key.slice(-3)}`;
}

/** Mapa de erros do PVS (Spec 120 → mensagem de UI, Spec 121/D4). */
const PVS_ERROR_MESSAGES: Record<string, string> = {
  pvs_invalid_code: 'Código Pix inválido. Confira o Copia e Cola.',
  pvs_dynamic_pix_not_supported:
    'Pix dinâmico não é suportado no Pagamento via Saldo — use um Pix de chave (estático).',
  pvs_unsupported_key: 'A chave Pix desse código não é reconhecida (CPF, CNPJ, telefone ou chave aleatória).',
  pvs_amount_required: 'Esse Pix não informa o valor — digite o valor a pagar.',
  pvs_amount_mismatch: 'O valor informado difere do valor do Pix. Confira.',
  pvs_max_per_payment_exceeded: 'Valor acima do limite por operação.',
  pvs_daily_cap_exceeded: 'Limite diário de Pagamento via Saldo atingido. Tente amanhã.',
};

/** Mensagem amigável de um erro do PVS (fallback para a mensagem do backend). */
function pvsErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.code && PVS_ERROR_MESSAGES[err.code]) {
    return PVS_ERROR_MESSAGES[err.code];
  }
  return err instanceof Error ? err.message : 'Erro inesperado';
}

/** Limite de teto → recarregar o card de limites após erro (Spec 121/D4). */
const PVS_LIMIT_ERROR_CODES = ['pvs_max_per_payment_exceeded', 'pvs_daily_cap_exceeded'];

export default function WithdrawPage() {
  const { me } = useAuth();
  const merchantId = me?.merchantId;
  const [amount, setAmount] = useState('');
  const [destinationId, setDestinationId] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Spec 70: estado do card de saque automático.
  const [autoEnabled, setAutoEnabled] = useState(false);
  const [autoThreshold, setAutoThreshold] = useState('');
  const [autoDestinationId, setAutoDestinationId] = useState('');
  const [autoError, setAutoError] = useState<unknown>(null);
  const [autoSuccess, setAutoSuccess] = useState<string | null>(null);
  const [autoSaving, setAutoSaving] = useState(false);

  // Spec 120/121: estado do Pagamento via Saldo.
  const [pixCode, setPixCode] = useState('');
  const [pvsAmount, setPvsAmount] = useState('');
  const [pvsPreview, setPvsPreview] = useState<PvsPreview | null>(null);
  const [pvsError, setPvsError] = useState<unknown>(null);
  const [pvsSuccess, setPvsSuccess] = useState<string | null>(null);
  const [pvsSubmitting, setPvsSubmitting] = useState(false);
  const [pvsConfirmOpen, setPvsConfirmOpen] = useState(false);

  const balance = useAsync<Balance>(() => api.get('/api/v1/portal/wallet/balance'), []);
  const destinations = useAsync<Destination[]>(() => api.get('/api/v1/portal/destinations'), []);
  const autoConfig = useAsync<AutoWithdrawConfig>(
    () => api.get('/api/v1/portal/auto-withdraw'),
    [],
  );
  const withdrawals = useAsync<Withdrawal[]>(
    () => (merchantId ? api.get(`/api/v1/merchants/${merchantId}/withdrawals`) : Promise.resolve([])),
    [merchantId],
  );
  const pvsLimits = useAsync<PvsLimits>(() => api.get('/api/v1/portal/pvs/limits'), []);

  const activeDestinations = (destinations.data || []).filter((d) => d.status === 'ACTIVE');

  // Spec 70: pré-preenche o formulário com a config do backend (threshold
  // default da plataforma quando nunca configurado — GET nunca 404).
  useEffect(() => {
    if (autoConfig.data) {
      setAutoEnabled(autoConfig.data.enabled);
      setAutoThreshold(String(autoConfig.data.threshold));
      setAutoDestinationId(autoConfig.data.destinationId || '');
    }
  }, [autoConfig.data]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    if (!merchantId) return;
    const value = Number(amount);
    if (!Number.isFinite(value) || value <= 0) {
      setError(new Error('Informe um valor maior que zero'));
      return;
    }
    if (!destinationId) {
      setError(new Error('Selecione um destino de saque'));
      return;
    }
    setSubmitting(true);
    try {
      const res = await api.post<Withdrawal>(`/api/v1/merchants/${merchantId}/withdrawals`, {
        amount: value,
        destinationId,
      });
      setSuccess(`Saque de ${formatMoney(res.amount)} solicitado (${res.status}).`);
      setAmount('');
      balance.reload();
      withdrawals.reload();
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  const handleAutoSave = async (e: FormEvent) => {
    e.preventDefault();
    setAutoError(null);
    setAutoSuccess(null);
    const threshold = Number(autoThreshold);
    if (!Number.isFinite(threshold) || threshold <= 0) {
      setAutoError(new Error('Informe um limiar maior que zero'));
      return;
    }
    if (autoEnabled && !autoDestinationId) {
      setAutoError(new Error('Selecione um destino para o saque automático'));
      return;
    }
    setAutoSaving(true);
    try {
      const res = await api.put<AutoWithdrawConfig>('/api/v1/portal/auto-withdraw', {
        enabled: autoEnabled,
        threshold,
        destinationId: autoDestinationId || null,
      });
      setAutoEnabled(res.enabled);
      setAutoThreshold(String(res.threshold));
      setAutoDestinationId(res.destinationId || '');
      setAutoSuccess(res.enabled ? 'Saque automático ativado.' : 'Saque automático desativado.');
    } catch (err) {
      setAutoError(err);
    } finally {
      setAutoSaving(false);
    }
  };

  // Spec 121: monta a prévia do Pagamento via Saldo (best-effort no cliente —
  // o backend valida o brcode de verdade na confirmação).
  const handleShowPreview = () => {
    setPvsError(null);
    setPvsSuccess(null);
    const code = pixCode.trim();
    if (!code) {
      setPvsError(new Error('Cole o Pix Copia e Cola para ver a prévia'));
      return;
    }
    if (!code.startsWith('000201')) {
      setPvsError(new Error('Parece que o código não é um Pix Copia e Cola'));
      return;
    }
    const { key, amount, dynamic } = parsePixForPreview(code);
    if (dynamic) {
      setPvsError(new Error(PVS_ERROR_MESSAGES.pvs_dynamic_pix_not_supported));
      return;
    }
    if (!key) {
      setPvsError(new Error('Não foi possível ler a chave Pix desse código'));
      return;
    }
    if (amount !== null) {
      setPvsAmount(String(amount)); // valor do próprio brcode (campo 54)
    }
    setPvsPreview({ keyMask: maskKey(key), amount, fee: 2.5 });
  };

  // Confirma o pagamento (ConfirmDialog) → POST /api/v1/portal/pvs/pay.
  const handlePvsConfirm = async () => {
    if (!pvsPreview) return;
    const value = pvsPreview.amount ?? Number(pvsAmount);
    if (!Number.isFinite(value) || value <= 0) {
      setPvsError(new Error('Informe o valor a pagar'));
      setPvsConfirmOpen(false);
      return;
    }
    setPvsSubmitting(true);
    setPvsError(null);
    setPvsSuccess(null);
    try {
      const res = await api.post<{ status: string; amount: number }>('/api/v1/portal/pvs/pay', {
        pixCode: pixCode.trim(),
        ...(pvsPreview.amount === null ? { amount: value } : {}),
      });
      setPvsSuccess(`Pagamento de ${formatMoney(res.amount)} solicitado (${res.status}).`);
      setPixCode('');
      setPvsAmount('');
      setPvsPreview(null);
      setPvsConfirmOpen(false);
      balance.reload();
      withdrawals.reload();
      pvsLimits.reload();
    } catch (err) {
      setPvsError(new Error(pvsErrorMessage(err)));
      // Erro de teto → recarrega os limites (usado/restante mudaram).
      if (err instanceof ApiError && err.code && PVS_LIMIT_ERROR_CODES.includes(err.code)) {
        pvsLimits.reload();
      }
    } finally {
      setPvsSubmitting(false);
    }
  };

  // Valores da prévia calculados ao vivo (o valor é editável quando o EMV não
  // informa valor).
  const pvsValue = pvsPreview ? (pvsPreview.amount ?? Number(pvsAmount)) : 0;
  const pvsTotal = pvsPreview && pvsValue > 0 ? pvsValue + pvsPreview.fee : 0;
  const pvsLimitsBlocked = pvsPreview && pvsLimits.data
    ? pvsTotal > pvsLimits.data.remaining
    : false;

  return (
    <div className="page">
      <h1>Saque</h1>
      <div className="grid-2">
        <Card title="Solicitar saque">
          <div className="metric-value">{formatMoney(balance.data?.walletBalance)}</div>
          <div className="muted">Saldo disponível</div>
          {balance.data?.withdrawalsBlocked && (
            <div className="alert alert-warning">Saques bloqueados pela administração.</div>
          )}
          <form className="form" onSubmit={handleSubmit}>
            <ErrorBanner error={error} />
            {success && <div className="alert alert-success">{success}</div>}
            <Field
              label="Valor (R$)"
              type="number"
              step="0.01"
              min="0.01"
              required
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
            <label className="field">
              <span className="field-label">Destino</span>
              <select
                className="input"
                value={destinationId}
                onChange={(e) => setDestinationId(e.target.value)}
                required
              >
                <option value="">Selecione…</option>
                {activeDestinations.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.label || d.type} — {d.mask}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={submitting || !activeDestinations.length || balance.data?.withdrawalsBlocked}
            >
              {submitting ? 'Solicitando…' : 'Solicitar saque'}
            </button>
          </form>
        </Card>

        <Card title="Saques recentes">
          {withdrawals.loading ? (
            <Spinner />
          ) : (
            <ul className="plain-list">
              {(withdrawals.data || []).map((w) => (
                <li key={w.id}>
                  <span>
                    {formatMoney(w.amount)} — {w.destination}
                  </span>
                  <span className="tag">{w.status}</span>
                </li>
              ))}
              {(withdrawals.data || []).length === 0 && <li className="muted">Nenhum saque ainda.</li>}
            </ul>
          )}
        </Card>
      </div>

      {/* Spec 120/121: Pagamento via Saldo — pagar Pix Copia e Cola com o saldo. */}
      <div className="grid-2">
        <Card title="Pagamento via Saldo">
          <p className="muted">
            Pague um Pix Copia e Cola (chave) usando o saldo — sem cadastrar destino. Pix dinâmico
            e boleto não são suportados.
          </p>
          <ErrorBanner error={pvsError} />
          {pvsSuccess && (
            <div className="alert alert-success" role="status">
              {pvsSuccess}
            </div>
          )}
          <label className="field">
            <span className="field-label">Pix Copia e Cola (cole o código)</span>
            <textarea
              id="pvs-pix-code"
              className="input"
              rows={3}
              autoFocus
              value={pixCode}
              onChange={(e) => {
                setPixCode(e.target.value);
                setPvsPreview(null);
              }}
              placeholder="0002012658…"
            />
          </label>
          {pixCode.trim() && !pixCode.trim().startsWith('000201') && (
            <div className="alert alert-warning">Parece que o código não é um Pix Copia e Cola.</div>
          )}
          <button
            type="button"
            className="btn"
            onClick={handleShowPreview}
            disabled={!pixCode.trim()}
          >
            Ver prévia
          </button>
        </Card>

        <Card title="Limites do dia">
          {pvsLimits.loading ? (
            <Spinner />
          ) : pvsLimits.data ? (
            (() => {
              const pct = Math.min(
                100,
                Math.round((pvsLimits.data.usedToday / pvsLimits.data.dailyCap) * 100),
              );
              return (
                <>
                  <div className="metric-value">{formatMoney(pvsLimits.data.remaining)}</div>
                  <div className="muted">Restante hoje (teto {formatMoney(pvsLimits.data.dailyCap)})</div>
                  <div
                    role="progressbar"
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={pct}
                    style={{ height: 8, background: '#e5e7eb', borderRadius: 4, overflow: 'hidden' }}
                  >
                    <div style={{ width: `${pct}%`, height: '100%', background: '#2563eb' }} />
                  </div>
                  <p className="muted">
                    Usado hoje: {formatMoney(pvsLimits.data.usedToday)} · Teto por operação:{' '}
                    {formatMoney(pvsLimits.data.maxPerPayment)}
                  </p>
                  {pct >= 80 && (
                    <div className="alert alert-warning">Você já usou 80%+ do limite diário.</div>
                  )}
                </>
              );
            })()
          ) : (
            <p className="muted">Não foi possível carregar os limites.</p>
          )}
        </Card>
      </div>

      {pvsPreview && (
        <Card title="Prévia do pagamento">
          <dl className="details">
            <div>
              <dt>Destino (chave)</dt>
              <dd>{pvsPreview.keyMask}</dd>
            </div>
            <div>
              <dt>Valor</dt>
              <dd>
                <Field
                  label="Valor (R$)"
                  type="number"
                  step="0.01"
                  min="0.01"
                  required
                  readOnly={pvsPreview.amount !== null}
                  value={pvsAmount}
                  onChange={(e) => setPvsAmount(e.target.value)}
                />
                {pvsPreview.amount !== null && (
                  <span className="muted">Valor informado pelo próprio Pix.</span>
                )}
              </dd>
            </div>
            <div>
              <dt>Taxa</dt>
              <dd>{formatMoney(pvsPreview.fee)}</dd>
            </div>
            <div>
              <dt>Total a debitar</dt>
              <dd className="strong">{formatMoney(pvsTotal)}</dd>
            </div>
          </dl>
          {pvsLimitsBlocked && (
            <div className="alert alert-warning">Limite diário insuficiente para esse valor.</div>
          )}
          <button
            type="button"
            className="btn btn-primary"
            disabled={pvsSubmitting || pvsTotal <= 0 || pvsLimitsBlocked}
            onClick={() => setPvsConfirmOpen(true)}
          >
            {pvsSubmitting ? 'Pagar…' : `Pagar ${formatMoney(pvsTotal)}`}
          </button>
        </Card>
      )}

      {/* Spec 70: saque automático por limiar de saldo. */}
      <Card title="Saque automático">
        <p className="muted">
          Quando o saldo atingir {formatMoney(autoThreshold || 0)}, sacamos automaticamente todo o
          valor para o destino escolhido.
        </p>
        {activeDestinations.length === 0 ? (
          <div className="alert alert-warning">
            Cadastre um destino de saque primeiro para ativar o saque automático.
          </div>
        ) : (
          <form className="form" onSubmit={handleAutoSave}>
            <ErrorBanner error={autoError} />
            {autoSuccess && <div className="alert alert-success">{autoSuccess}</div>}
            <label className="field">
              <span className="field-label">Ativo</span>
              <input
                type="checkbox"
                checked={autoEnabled}
                onChange={(e) => setAutoEnabled(e.target.checked)}
              />
            </label>
            <Field
              label="Limiar (R$)"
              type="number"
              step="0.01"
              min="0.01"
              required
              value={autoThreshold}
              onChange={(e) => setAutoThreshold(e.target.value)}
            />
            <label className="field">
              <span className="field-label">Destino</span>
              <select
                className="input"
                value={autoDestinationId}
                onChange={(e) => setAutoDestinationId(e.target.value)}
                disabled={!autoEnabled}
              >
                <option value="">Selecione…</option>
                {activeDestinations.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.label || d.type} — {d.mask}
                  </option>
                ))}
              </select>
            </label>
            <button type="submit" className="btn btn-primary" disabled={autoSaving}>
              {autoSaving ? 'Salvando…' : 'Salvar configuração'}
            </button>
          </form>
        )}
      </Card>

      {/* Spec 121: confirmação do Pagamento via Saldo (ação financeira → danger). */}
      <ConfirmDialog
        open={pvsConfirmOpen}
        title="Confirmar pagamento"
        message={`Pagar ${formatMoney(pvsTotal)} via saldo (inclui taxa de ${formatMoney(2.5)})?`}
        confirmLabel={`Pagar ${formatMoney(pvsTotal)}`}
        busy={pvsSubmitting}
        error={pvsError}
        tone="danger"
        onConfirm={handlePvsConfirm}
        onCancel={() => setPvsConfirmOpen(false)}
      />
    </div>
  );
}
