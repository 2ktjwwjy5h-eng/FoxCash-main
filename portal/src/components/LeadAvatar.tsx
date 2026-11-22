// LeadAvatar (Spec 102) — avatar circular com as iniciais do lead (Spec 101).
// Clique → abre o Live Chat do CRM (Spec 84) via navegação /crm?lead={id}.

import type { ShellLead } from './HeaderGlobal';

export default function LeadAvatar({ lead, onClick }: { lead: ShellLead; onClick: () => void }) {
  return (
    <button
      type="button"
      className="lead-avatar"
      title={lead.name || 'Cliente'}
      aria-label={`Abrir chat com ${lead.name || 'cliente'}`}
      onClick={onClick}
    >
      {lead.initials || '?'}
    </button>
  );
}
