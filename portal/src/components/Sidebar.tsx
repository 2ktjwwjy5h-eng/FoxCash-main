// Sidebar (Spec 102) — menu lateral do shell do portal em seções (NAV_GROUPS):
// Visão Geral / Financeiro / Clientes / Automações / Ferramentas / Mais.
// Itens reais = rotas existentes (NavLink); itens futuros (specs 105-119) =
// desabilitados com tooltip "Em breve" (Spec 102 C3/D3 — não quebram navegação).

import { NavLink } from 'react-router-dom';

interface NavItem {
  label: string;
  icon?: string;
  to?: string;
  soon?: boolean;
}

interface NavGroup {
  section: string;
  items: NavItem[];
}

const NAV_GROUPS: NavGroup[] = [
  {
    section: 'Visão Geral',
    items: [
      { label: 'Dashboard', icon: '📊', to: '/dashboard' },
      { label: 'Ranking', icon: '🏆', soon: true },
      { label: 'Premiações', icon: '🎁', soon: true },
      { label: 'Análises', icon: '📈', soon: true },
    ],
  },
  {
    section: 'Financeiro',
    items: [
      { label: 'Carteira', icon: '💰', to: '/wallet' },
      { label: 'Vendas', icon: '💳', to: '/sales' },
      { label: 'Cobrança', icon: '🧾', to: '/charges' },
      { label: 'Saque', icon: '💸', to: '/withdraw' },
      { label: 'Destinos', icon: '🏦', to: '/destinations' },
      { label: 'Financeiro', icon: '📉', soon: true },
    ],
  },
  {
    section: 'Clientes',
    items: [{ label: 'Clientes', icon: '👥', to: '/crm' }],
  },
  {
    section: 'Automações',
    items: [
      { label: 'Meus Robôs', icon: '🤖', to: '/bots' },
      { label: 'Postagens', icon: '📣', to: '/broadcasts' },
      { label: 'Meus Fluxos', icon: '🔄', soon: true },
      { label: 'Redirecionadores', icon: '🔗', soon: true },
      { label: 'Remarketing', icon: '🎯', soon: true },
    ],
  },
  {
    section: 'Ferramentas',
    items: [
      { label: 'Gateways', icon: '🏧', to: '/gateways' },
      { label: 'Trackeamento', icon: '📍', to: '/tracking' },
      { label: 'Webhooks', icon: '🔌', to: '/webhooks' },
      { label: 'Checkout', icon: '🛒', soon: true },
      { label: 'Bio Link', icon: '🌿', soon: true },
      { label: 'Cupons & Sorteios', icon: '🎁', soon: true },
    ],
  },
  {
    section: 'Mais',
    items: [
      { label: 'Credenciais', icon: '🔑', to: '/credentials' },
      { label: 'Segurança', icon: '🔒', to: '/security' },
      { label: 'Comunidade', icon: '🌐', to: '/community' },
      { label: 'Afiliado', icon: '🤝', soon: true },
    ],
  },
];

export default function Sidebar() {
  return (
    <nav aria-label="Navegação principal">
      {NAV_GROUPS.map((group) => (
        <div key={group.section} className="nav-group">
          <div className="nav-group-title">{group.section}</div>
          {group.items.map((item) =>
            item.soon || !item.to ? (
              <span key={item.label} className="nav-link nav-link-soon" title="Em breve" aria-disabled="true">
                <span className="nav-link-label">
                  {item.icon && <span className="nav-icon" aria-hidden="true">{item.icon}</span>}
                  {item.label}
                </span>
                <span className="nav-soon-tag">em breve</span>
              </span>
            ) : (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
              >
                {item.icon && <span className="nav-icon" aria-hidden="true">{item.icon}</span>}
                {item.label}
              </NavLink>
            ),
          )}
        </div>
      ))}
    </nav>
  );
}
