// HeaderGlobal (Spec 102) — barra de topo do shell do portal (sobre o backend
// da Spec 101): indicador de faturamento com barra de meta, saudação/data,
// carrossel de leads recentes (→ Live Chat do CRM) e atalhos (ajuda,
// notificações com badge, perfil, configurações). useAsync + refresh 60s
// para o "tempo real" do faturamento. Padrão UX da Spec 73 (CSS puro).

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../apiClient';
import { ErrorBanner, formatMoney, Spinner, useAsync } from './ui';
import LeadAvatar from './LeadAvatar';
import NotificationsMenu from './NotificationsMenu';
import { useDialogFocus } from '../hooks/useDialogFocus';

export interface ShellGreeting {
  salutation: string;
  name: string;
  date: string;
  time: string;
}

export interface ShellBilling {
  periodStart: string;
  periodEnd: string;
  billedAmount: number;
  goalAmount: number;
  progressPercent: number;
}

export interface ShellLead {
  id: string;
  name: string;
  initials: string;
  lastInteractionAt: string;
}

export interface ShellNotificationItem {
  id: string;
  type: string;
  message: string;
  createdAt: string;
}

export interface ShellNotifications {
  unreadCount: number;
  items: ShellNotificationItem[];
}

export interface PortalShell {
  greeting: ShellGreeting;
  billing: ShellBilling;
  recentLeads: ShellLead[];
  notifications: ShellNotifications;
}

/** Cor da barra de progresso por faixa (verde ≥100, amarelo ≥50, vermelho <50 — Spec 102 D2). */
function progressClass(percent: number): string {
  if (percent >= 100) return 'header-progress-fill header-progress-good';
  if (percent >= 50) return 'header-progress-fill header-progress-mid';
  return 'header-progress-fill header-progress-low';
}

/** Modal de ajuda simples (FAQ — Spec 102 C4: "ajuda (modal FAQ simples)"). */
function HelpModal({ onClose }: { onClose: () => void }) {
  const dialogRef = useDialogFocus({ open: true, onClose });
  const faqs: Array<[string, string]> = [
    ['Como criar uma cobrança PIX?', 'Vá em Cobrança, preencha valor e gere o QR Code.'],
    ['Como configurar um webhook?', 'Em Webhooks, adicione a URL de destino e o evento.'],
    ['Como funciona o saque?', 'Em Saque, cadastre um destino e escolha o valor.'],
    ['Onde vejo minhas vendas?', 'Em Vendas, com filtros por período.'],
  ];
  return (
    <div className="modal-backdrop">
      <div className="modal" role="dialog" aria-modal="true" aria-label="Ajuda" ref={dialogRef}>
        <h2 style={{ margin: 0, fontSize: 18 }}>Ajuda</h2>
        {faqs.map(([q, a]) => (
          <details key={q} className="help-faq-item">
            <summary>{q}</summary>
            <p className="muted">{a}</p>
          </details>
        ))}
        <div className="modal-actions">
          <button type="button" className="btn btn-primary" onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}

export default function HeaderGlobal() {
  const navigate = useNavigate();
  const [helpOpen, setHelpOpen] = useState(false);
  const shell = useAsync<PortalShell>(() => api.get<PortalShell>('/api/v1/portal/shell'), []);

  // "Tempo real" do faturamento: refresh a cada 60s (Spec 102 D1).
  useEffect(() => {
    const t = window.setInterval(() => shell.reload(), 60_000);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const data = shell.data;

  const openChat = (leadId: string) => {
    // Spec 102 C2: avatar do carrossel → Live Chat do CRM (Spec 84). O CrmPage
    // lê ?lead= e abre o modal com o lead.
    navigate(`/crm?lead=${leadId}`);
  };

  return (
    <header className="header-global">
      {shell.loading && !data ? (
        <div className="header-global-loading">
          <Spinner />
        </div>
      ) : (
        <>
          {shell.error && !data && <ErrorBanner error={shell.error} />}
          {/* Faturamento do período + barra de meta (Spec 101 billing). */}
          <div className="header-billing">
            <div className="header-billing-value">
              {data ? formatMoney(data.billing.billedAmount) : '—'}
            </div>
            <div className="header-billing-goal">
              Meta do mês: {data ? formatMoney(data.billing.goalAmount) : '—'}
            </div>
            <div
              className="header-progress"
              role="progressbar"
              aria-valuenow={data?.billing.progressPercent ?? 0}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label="Progresso da meta"
            >
              <div
                className={progressClass(data?.billing.progressPercent ?? 0)}
                style={{ width: `${Math.min(data?.billing.progressPercent ?? 0, 100)}%` }}
              />
            </div>
            <span className="header-billing-percent">
              {data?.billing.progressPercent ?? 0}%
            </span>
          </div>

          {/* Saudação + data (Spec 101 greeting — fuso do cliente via X-Timezone-Offset). */}
          <div className="header-greeting">
            <div className="header-greeting-title">
              {data ? `${data.greeting.salutation}, ${data.greeting.name}` : '…'}
            </div>
            <div className="header-greeting-date muted">
              {data ? `${data.greeting.date} · ${data.greeting.time}` : ''}
            </div>
          </div>

          {/* Carrossel de leads recentes (Spec 101 recentLeads → CRM). */}
          <div className="header-leads" aria-label="Leads recentes">
            {(data?.recentLeads ?? []).length === 0 ? (
              <span className="muted header-leads-empty">Sem leads ainda</span>
            ) : (
              (data?.recentLeads ?? []).map((lead) => (
                <LeadAvatar
                  key={lead.id}
                  lead={lead}
                  onClick={() => openChat(lead.id)}
                />
              ))
            )}
          </div>

          {/* Atalhos: ajuda / notificações (badge) / perfil / configurações. */}
          <div className="header-actions">
            <button
              type="button"
              className="header-icon-btn"
              aria-label="Ajuda"
              title="Ajuda"
              onClick={() => setHelpOpen(true)}
            >
              ?
            </button>
            <NotificationsMenu
              notifications={data?.notifications ?? { unreadCount: 0, items: [] }}
            />
            <button
              type="button"
              className="header-icon-btn"
              aria-label="Perfil e segurança"
              title="Perfil e segurança"
              onClick={() => navigate('/security')}
            >
              ⚙
            </button>
            <button
              type="button"
              className="header-icon-btn"
              aria-label="Credenciais e configurações"
              title="Credenciais"
              onClick={() => navigate('/credentials')}
            >
              🔑
            </button>
          </div>
        </>
      )}

      {helpOpen && <HelpModal onClose={() => setHelpOpen(false)} />}
    </header>
  );
}
