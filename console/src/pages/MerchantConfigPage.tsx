// Config fina por lojista (Spec 100) — UI do Master Console sobre o backend
// da Spec 98: regra de taxa (FIXED/PERCENT), taxas e limites de saque, rate
// limit por lojista, kill switch (activate/deactivate), debitar saldo
// (step-up TOTP), honeypot, bloquear API e banir (step-up). PUT parcial por
// seção (null = usa o default global). RBAC-aware (Spec 69) + UX Spec 73.
//
// Desvio deliberado (registrado): a seção **Gateway (Adquirente)** do design
// D4.3 NÃO existe nesta tela — o backend da Spec 98 não tem `gateway_route`
// (coordenação 2026-08-15: rota por lojista virou `merchant_profiles`, Spec
// 122, migration V69). Sem endpoint → sem campo (thin client).

import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../apiClient';
import BanModal from '../components/BanModal';
import { ConfirmDialog } from '../components/ConfirmDialog';
import ConfigSection from '../components/ConfigSection';
import DebitModal from '../components/DebitModal';
import { Card, ErrorBanner, Spinner, SuccessBanner, formatMoney, useAsync } from '../components/ui';
import { RequirePermission } from '../hooks/usePermission';
import type { DebitResult, Merchant, MerchantConfig, MerchantEarnings, PageResponse, PixFeeType } from '../types';

/** Formulário local (strings — campos vazios = "usa o padrão global"). */
interface ConfigForm {
  pixFeeType: PixFeeType;
  pixFeeFixed: string;
  pixFeePercent: string;
  pixFeeThreshold: string;
  withdrawalFee: string;
  pixMin: string;
  pixMax: string;
  withdrawalMin: string;
  withdrawalMax: string;
  rateLimitRps: string;
  rateLimitRpm: string;
}

function fromConfig(c: MerchantConfig): ConfigForm {
  const s = (v: string | number | null | undefined): string => (v == null ? '' : String(v));
  return {
    pixFeeType: c.pixFeeType ?? null,
    pixFeeFixed: s(c.pixFeeFixed),
    pixFeePercent: s(c.pixFeePercent),
    pixFeeThreshold: s(c.pixFeeThreshold),
    withdrawalFee: s(c.withdrawalFee),
    pixMin: s(c.pixMin),
    pixMax: s(c.pixMax),
    withdrawalMin: s(c.withdrawalMin),
    withdrawalMax: s(c.withdrawalMax),
    rateLimitRps: s(c.rateLimitRps),
    rateLimitRpm: s(c.rateLimitRpm),
  };
}

/** '' → null; senão Number (valida no envio). */
function numOrNull(v: string): number | null {
  const t = v.trim();
  if (t === '') return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

function intOrNull(v: string): number | null {
  const t = v.trim();
  if (t === '') return null;
  const n = Number(t);
  return Number.isInteger(n) && n > 0 ? n : null;
}

export default function MerchantConfigPage() {
  const { id = '' } = useParams();
  const { data: config, error: configError, loading: configLoading, reload: reloadConfig } = useAsync(
    () => api.get<MerchantConfig>(`/api/v1/master/merchants/${id}/config`),
    [id],
  );
  const earnings = useAsync(() => api.get<MerchantEarnings>(`/api/v1/master/merchants/${id}/earnings`), [id]);
  const merchants = useAsync(
    () => api.get<PageResponse<Merchant>>('/api/v1/master/merchants?page=0&size=100'),
    [],
  );
  const merchant = merchants.data?.items.find((m) => m.id === id);
  const merchantName = merchant?.name || id.slice(0, 8);
  const status = merchant?.status || '';

  const [form, setForm] = useState<ConfigForm | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [killConfirm, setKillConfirm] = useState<'activate' | 'deactivate' | null>(null);
  const [killBusy, setKillBusy] = useState(false);
  const [killError, setKillError] = useState<unknown>(null);
  const [debitOpen, setDebitOpen] = useState(false);
  const [banOpen, setBanOpen] = useState(false);
  const [honeypotConfirm, setHoneypotConfirm] = useState(false);
  const [apiBlockConfirm, setApiBlockConfirm] = useState(false);
  const [toggleBusy, setToggleBusy] = useState(false);
  const [toggleError, setToggleError] = useState<unknown>(null);

  // Inicializa o formulário UMA vez quando a config carrega.
  useEffect(() => {
    if (config && !form) {
      setForm(fromConfig(config));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config]);

  const applyConfig = (next: MerchantConfig) => {
    setForm(fromConfig(next));
  };

  const reloadAll = () => {
    reloadConfig();
    merchants.reload();
    earnings.reload();
  };

  if (configLoading && !config) return <Spinner />;
  if (configError && !config) return <ErrorBanner error={configError} />;
  if (!form || !config) return null;

  const banned = status === 'BANNED';
  const killToggleable = status === 'ACTIVE' || status === 'INACTIVE';

  const runKill = async () => {
    if (!killConfirm) return;
    setKillBusy(true);
    setKillError(null);
    try {
      await api.post<void>(`/api/v1/master/merchants/${id}/config/${killConfirm}`);
      setFeedback(`Lojista ${killConfirm === 'activate' ? 'ativado' : 'desativado'}.`);
      setKillConfirm(null);
      reloadAll();
    } catch (err) {
      setKillError(err);
    } finally {
      setKillBusy(false);
    }
  };

  const toggleHoneypot = async () => {
    setToggleBusy(true);
    setToggleError(null);
    try {
      await api.post<void>(`/api/v1/master/merchants/${id}/config/honeypot`, { enabled: !config.honeypot });
      setFeedback(`Honeypot ${config.honeypot ? 'desativado' : 'ativado'}.`);
      setHoneypotConfirm(false);
      reloadConfig();
    } catch (err) {
      setToggleError(err);
    } finally {
      setToggleBusy(false);
    }
  };

  const toggleApiBlock = async () => {
    setToggleBusy(true);
    setToggleError(null);
    try {
      await api.put<void>(`/api/v1/master/merchants/${id}/config`, {
        apiBlocked: !config.apiBlocked,
      });
      setFeedback(`API do lojista ${config.apiBlocked ? 'desbloqueada' : 'bloqueada'}.`);
      setApiBlockConfirm(false);
      // reloadConfig (não applyConfig): o card métrico e o switch leem
      // `config.apiBlocked` (estado useAsync) — applyConfig só atualiza o form.
      reloadConfig();
    } catch (err) {
      setToggleError(err);
    } finally {
      setToggleBusy(false);
    }
  };

  const saveFeeRule = async () => {
    if (!form) return;
    let payload: Record<string, unknown>;
    if (form.pixFeeType === 'FIXED') {
      const fixed = numOrNull(form.pixFeeFixed);
      if (fixed === null || fixed < 0) throw invalid('Informe um valor de taxa fixa válido (≥ 0).');
      payload = { pixFeeType: 'FIXED', pixFeeFixed: fixed };
    } else if (form.pixFeeType === 'PERCENT') {
      const percent = numOrNull(form.pixFeePercent);
      const threshold = numOrNull(form.pixFeeThreshold);
      if (percent === null || percent < 0 || percent > 100) throw invalid('Percentual deve estar entre 0 e 100.');
      if (threshold === null || threshold <= 0) throw invalid('O limite de corte (threshold) deve ser maior que zero.');
      payload = { pixFeeType: 'PERCENT', pixFeePercent: percent, pixFeeThreshold: threshold };
    } else {
      // "Padrão da plataforma" — limpa os overrides da regra de taxa.
      payload = { pixFeeType: null, pixFeeFixed: null, pixFeePercent: null, pixFeeThreshold: null };
    }
    const next = await api.put<MerchantConfig>(`/api/v1/master/merchants/${id}/config`, payload);
    applyConfig(next);
  };

  const saveLimits = async () => {
    if (!form) return;
    const withdrawalFee = numOrNull(form.withdrawalFee);
    const pixMin = numOrNull(form.pixMin);
    const pixMax = numOrNull(form.pixMax);
    const withdrawalMin = numOrNull(form.withdrawalMin);
    const withdrawalMax = numOrNull(form.withdrawalMax);
    const neg = (v: number | null) => v !== null && v < 0;
    if (neg(withdrawalFee) || neg(pixMin) || neg(pixMax) || neg(withdrawalMin) || neg(withdrawalMax)) {
      throw invalid('Valores não podem ser negativos.');
    }
    if (pixMin != null && pixMax != null && pixMin > pixMax) throw invalid('Limite Pix mínimo não pode ser maior que o máximo.');
    if (withdrawalMin != null && withdrawalMax != null && withdrawalMin > withdrawalMax) {
      throw invalid('Limite de saque mínimo não pode ser maior que o máximo.');
    }
    const next = await api.put<MerchantConfig>(`/api/v1/master/merchants/${id}/config`, {
      withdrawalFee,
      pixMin,
      pixMax,
      withdrawalMin,
      withdrawalMax,
    });
    applyConfig(next);
  };

  const saveRateLimit = async () => {
    if (!form) return;
    const rps = intOrNull(form.rateLimitRps);
    const rpm = intOrNull(form.rateLimitRpm);
    if (form.rateLimitRps.trim() !== '' && rps === null) throw invalid('Req/s deve ser um inteiro maior que zero.');
    if (form.rateLimitRpm.trim() !== '' && rpm === null) throw invalid('Req/min deve ser um inteiro maior que zero.');
    const next = await api.put<MerchantConfig>(`/api/v1/master/merchants/${id}/config`, { rateLimitRps: rps, rateLimitRpm: rpm });
    applyConfig(next);
  };

  const set = (field: keyof ConfigForm) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setForm((f) => (f ? { ...f, [field]: e.target.value } : f));
  };

  return (
    <div className="page">
      <p>
        <Link to={`/console/merchants/${id}`} className="btn btn-small">
          ← Detalhe do lojista
        </Link>
      </p>
      <h1>Config de {merchantName}</h1>

      <SuccessBanner message={feedback} />

      {banned && (
        <div className="alert alert-error" role="alert">
          Conta banida (status BANNED — irreversível). As ações de configuração estão desabilitadas.
        </div>
      )}

      <div className="metric-grid">
        <Card title="Status">
          <div className="metric-value">{status || '—'}</div>
        </Card>
        <Card title="Saldo do wallet">
          <div className="metric-value">{earnings.data ? formatMoney(earnings.data.walletBalance) : '—'}</div>
        </Card>
        <Card title="API">
          <div className="metric-value">{config.apiBlocked ? 'BLOQUEADA' : 'OK'}</div>
        </Card>
        <Card title="Honeypot">
          <div className="metric-value">{config.honeypot ? 'SIM' : 'não'}</div>
        </Card>
      </div>

      {!banned && (
        <RequirePermission permission="merchant.config">
          <div className="config-grid">
            <ConfigSection
              title="Regra de taxa"
              description="Pix até determinado valor cobra taxa fixa; acima disso, percentual. Vazio = padrão da plataforma (R$ 0,50)."
              onSave={saveFeeRule}
            >
              <label className="field">
                <span className="field-label">Tipo de taxa</span>
                <select
                  className="input"
                  value={form.pixFeeType ?? ''}
                  onChange={(e) => {
                    const v = e.target.value;
                    setForm((f) => (f ? { ...f, pixFeeType: (v === '' ? null : v) as PixFeeType } : f));
                  }}
                >
                  <option value="">Padrão da plataforma</option>
                  <option value="FIXED">Taxa fixa (R$)</option>
                  <option value="PERCENT">Percentual (%)</option>
                </select>
              </label>
              {form.pixFeeType === 'FIXED' && (
                <label className="field">
                  <span className="field-label">Taxa fixa (R$)</span>
                  <input className="input" type="number" min="0" step="0.01" placeholder="0,50" value={form.pixFeeFixed} onChange={set('pixFeeFixed')} />
                </label>
              )}
              {form.pixFeeType === 'PERCENT' && (
                <>
                  <label className="field">
                    <span className="field-label">Percentual (%)</span>
                    <input className="input" type="number" min="0" max="100" step="0.01" placeholder="1,50" value={form.pixFeePercent} onChange={set('pixFeePercent')} />
                  </label>
                  <label className="field">
                    <span className="field-label">Cobrar percentual acima de (R$)</span>
                    <input className="input" type="number" min="0" step="0.01" placeholder="100,00" value={form.pixFeeThreshold} onChange={set('pixFeeThreshold')} />
                  </label>
                </>
              )}
            </ConfigSection>

            <ConfigSection
              title="Taxas e limites de saque"
              description="Taxa de saque e limites operacionais de Pix/saque. Vazio = limites globais (taxa R$ 2,50)."
              onSave={saveLimits}
            >
              <label className="field">
                <span className="field-label">Taxa de saque (R$)</span>
                <input className="input" type="number" min="0" step="0.01" placeholder="R$ 2,50 padrão" value={form.withdrawalFee} onChange={set('withdrawalFee')} />
              </label>
              <label className="field">
                <span className="field-label">Limite Pix mín (R$)</span>
                <input className="input" type="number" min="0" step="0.01" placeholder="limite global" value={form.pixMin} onChange={set('pixMin')} />
              </label>
              <label className="field">
                <span className="field-label">Limite Pix máx (R$)</span>
                <input className="input" type="number" min="0" step="0.01" placeholder="limite global" value={form.pixMax} onChange={set('pixMax')} />
              </label>
              <label className="field">
                <span className="field-label">Limite saque mín (R$)</span>
                <input className="input" type="number" min="0" step="0.01" placeholder="limite global" value={form.withdrawalMin} onChange={set('withdrawalMin')} />
              </label>
              <label className="field">
                <span className="field-label">Limite saque máx (R$)</span>
                <input className="input" type="number" min="0" step="0.01" placeholder="limite global" value={form.withdrawalMax} onChange={set('withdrawalMax')} />
              </label>
            </ConfigSection>

            <ConfigSection
              title="Rate limit integrado"
              description="Proteção contra força bruta ou robôs de teste de cartão/chave. Vazio = limite global."
              onSave={saveRateLimit}
            >
              <label className="field">
                <span className="field-label">Requisições por segundo (req/s)</span>
                <input className="input" type="number" min="1" step="1" placeholder="global" value={form.rateLimitRps} onChange={set('rateLimitRps')} />
              </label>
              <label className="field">
                <span className="field-label">Requisições por minuto (req/min)</span>
                <input className="input" type="number" min="1" step="1" placeholder="global" value={form.rateLimitRpm} onChange={set('rateLimitRpm')} />
              </label>
            </ConfigSection>
          </div>
        </RequirePermission>
      )}

      {!banned && (
        <div className="grid-2">
          <Card title="Controle de conta">
            {status === 'SUSPENDED' ? (
              <p className="muted">Suspenso — o status é gerenciado por "Suspender/Liberar" no detalhe do lojista.</p>
            ) : (
              <RequirePermission permission="merchant.config">
                <div className="field">
                  <span className="field-label">Kill switch</span>
                  <label className="radio-row">
                    <input
                      type="radio"
                      name="kill"
                      checked={status === 'ACTIVE'}
                      onChange={() => setKillConfirm('activate')}
                      disabled={!killToggleable}
                    />{' '}
                    Ativo
                  </label>
                  <label className="radio-row">
                    <input
                      type="radio"
                      name="kill"
                      checked={status === 'INACTIVE'}
                      onChange={() => setKillConfirm('deactivate')}
                      disabled={!killToggleable}
                    />{' '}
                    Inativo (Bloqueado)
                  </label>
                </div>
              </RequirePermission>
            )}
            <RequirePermission permission="merchant.debit">
              <button type="button" className="btn btn-danger" onClick={() => setDebitOpen(true)}>
                Debitar saldo
              </button>
            </RequirePermission>
            <p className="muted">Débito administrativo com step-up TOTP (Spec 98) — registro no ledger e auditoria.</p>
          </Card>

          <Card title="Honeypot e API">
            <RequirePermission permission="merchant.config">
              <label className="switch-row">
                <span className="field-label">Honeypot</span>
                <label className="switch">
                  <input
                    type="checkbox"
                    checked={config.honeypot}
                    onChange={() => setHoneypotConfirm(true)}
                    disabled={toggleBusy}
                  />
                  <span className="switch-slider" />
                </label>
              </label>
              <p className="muted">
                Ambiente controlado de monitoramento antifraude: a conta continua operando, cobranças vão para o fluxo trap,
                saques ficam bloqueados — sem alertar o infrator.
              </p>
              <label className="switch-row">
                <span className="field-label">Bloquear API</span>
                <label className="switch">
                  <input
                    type="checkbox"
                    checked={config.apiBlocked}
                    onChange={() => setApiBlockConfirm(true)}
                    disabled={toggleBusy}
                  />
                  <span className="switch-slider" />
                </label>
              </label>
              <p className="muted">Bloqueia a API key do lojista (403); o portal continua funcionando.</p>
            </RequirePermission>
          </Card>
        </div>
      )}

      {!banned && (
        <RequirePermission permission="merchant.ban">
          <Card title="Risco">
            <button type="button" className="btn btn-danger" onClick={() => setBanOpen(true)}>
              Banir conta
            </button>
            <p className="muted">Banimento irreversível — bloqueia API, portal, saques e cobranças (com step-up TOTP).</p>
          </Card>
        </RequirePermission>
      )}

      <ErrorBanner error={configError} />

      {/* Kill switch */}
      <ConfirmDialog
        open={killConfirm !== null}
        title={killConfirm === 'deactivate' ? 'Desativar lojista' : 'Ativar lojista'}
        message={
          killConfirm === 'deactivate'
            ? 'Desativar bloqueia login, API e saques do lojista (status INACTIVE). Confirmar?'
            : 'Ativar devolve o acesso completo ao lojista (status ACTIVE). Confirmar?'
        }
        confirmLabel={killConfirm === 'deactivate' ? 'Desativar' : 'Ativar'}
        tone={killConfirm === 'deactivate' ? 'danger' : 'primary'}
        busy={killBusy}
        error={killError}
        onConfirm={runKill}
        onCancel={() => {
          setKillConfirm(null);
          setKillError(null);
        }}
      />

      {/* Honeypot */}
      <ConfirmDialog
        open={honeypotConfirm}
        title="Marcar como honeypot"
        message="A conta continua operando, mas as cobranças passam por um fluxo de monitoramento controlado (provedor trap) e os saques ficam bloqueados — sem alertar o atacante. Desligável a qualquer momento. Confirmar?"
        confirmLabel={config.honeypot ? 'Desativar honeypot' : 'Ativar honeypot'}
        tone="danger"
        busy={toggleBusy}
        error={toggleError}
        onConfirm={toggleHoneypot}
        onCancel={() => {
          setHoneypotConfirm(false);
          setToggleError(null);
        }}
      />

      {/* Bloquear API */}
      <ConfirmDialog
        open={apiBlockConfirm}
        title="Bloquear API do lojista"
        message="A API key do lojista passa a responder 403; o portal continua funcionando. Confirmar?"
        confirmLabel={config.apiBlocked ? 'Desbloquear API' : 'Bloquear API'}
        tone="danger"
        busy={toggleBusy}
        error={toggleError}
        onConfirm={toggleApiBlock}
        onCancel={() => {
          setApiBlockConfirm(false);
          setToggleError(null);
        }}
      />

      {debitOpen && (
        <DebitModal
          merchantId={id}
          merchantName={merchantName}
          currentBalance={earnings.data?.walletBalance ?? 0}
          onClose={() => setDebitOpen(false)}
          onSuccess={(result: DebitResult) => {
            setFeedback(`Débito de ${formatMoney(result.debitedAmount)} · novo saldo ${formatMoney(result.newBalance)}.`);
            earnings.reload();
          }}
        />
      )}

      {banOpen && (
        <BanModal
          merchantId={id}
          merchantName={merchantName}
          onClose={() => setBanOpen(false)}
          onSuccess={() => {
            setFeedback('Conta banida.');
            reloadAll();
          }}
        />
      )}
    </div>
  );
}

function invalid(message: string): Error {
  return new Error(message);
}
