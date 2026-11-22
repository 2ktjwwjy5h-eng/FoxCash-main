// Shell de navegação do Master Console (Spec 71, fatia 1): sidebar filtrada por
// permissões (Spec 69 — a UI esconde, o backend decide), header com badge de
// papel (me.role/ADMIN), nome e logout (revoga no servidor + limpa storage).
//
// NavigationMap: rota de leitura SEM `permission` = visível a todo master
// autenticado. Na fatia 1 todo master ativo tem as 7 permissões operacionais —
// o filtro só ocultaria a seção de gestão de perfis master (admin-only, fatia 2).

import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useEffect } from 'react';
import { useAuth } from '../auth';
import { usePermission } from '../hooks/usePermission';

interface NavItem {
  to: string;
  label: string;
  section?: string;
  /** Ausente = rota de leitura, visível a todo master autenticado. */
  permission?: string;
}

const NAV_ITEMS: NavItem[] = [
  { to: '/console/dashboard', label: 'Dashboard', section: 'Operação' },
  { to: '/console/transactions', label: 'Transações' },
  { to: '/console/merchants', label: 'Lojistas' },
  { to: '/console/withdrawals', label: 'Saques' },
  { to: '/console/financial', label: 'Financeiro' },
  { to: '/console/receivables', label: 'Receivables' },
  { to: '/console/reconciliation', label: 'Reconciliação' },
  { to: '/console/lookup', label: 'Lookup IP' },
  { to: '/console/bots', label: 'Bots' },
  { to: '/console/broadcasts', label: 'Broadcasts' },
  // Spec 127: trace de transação — seção Operação (junto de dashboard/transações).
  { to: '/console/trace', label: 'Trace' },
  { to: '/console/audit', label: 'Auditoria', section: 'Trilha' },
  { to: '/console/activity', label: 'Atividades' },
  { to: '/console/security', label: 'Segurança (MFA)', section: 'Conta' },
  // Spec 72 (fatia 2): gestão de perfis master — admin-only (master.profiles.manage).
  { to: '/console/profiles', label: 'Perfis master', section: 'Conta', permission: 'master.profiles.manage' },
];

export default function Layout() {
  const { me, logout, refreshMe } = useAuth();
  const navigate = useNavigate();
  const canManageMasters = usePermission('master.profiles.manage');

  useEffect(() => {
    void refreshMe();
  }, [refreshMe]);

  const handleLogout = async () => {
    await logout();
    navigate('/console/login', { replace: true });
  };

  const visible = NAV_ITEMS.filter((item) => !item.permission || (item.permission === 'master.profiles.manage' ? canManageMasters : false));

  return (
    <>
      {/* Spec 73: skip-link — teclado pula a sidebar para o conteúdo. */}
      <a href="#main-content" className="skip-link">
        Pular para o conteúdo
      </a>
      <div className="layout">
        <aside className="sidebar">
        <div className="brand">FoxCash</div>
        <span className="role-badge">{me?.canManageMasters ? 'ADMIN' : me?.role || '…'}</span>
        <nav>
          {visible.map((item, idx) => (
            <div key={item.to}>
              {item.section && (idx === 0 || visible[idx - 1]?.section !== item.section) && (
                <div className="nav-section">{item.section}</div>
              )}
              <NavLink
                to={item.to}
                className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}
              >
                {item.label}
              </NavLink>
            </div>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="merchant-name">{me?.name || me?.username || '…'}</div>
          <button type="button" className="btn btn-ghost" onClick={handleLogout}>
            Sair
          </button>
        </div>
      </aside>
      <main id="main-content" className="content">
        <Outlet />
      </main>
      </div>
    </>
  );
}
