// Shell de navegação das rotas privadas (Spec 46 + Spec 102): header global
// (saudação/billing/carrossel/atalhos — Spec 101) + sidebar em seções
// (NAV_GROUPS) + nome do lojista (GET /portal/me) + logout. O ProfileSelector
// da Spec 139 (lente Bot/Gateway + perfil) permanece no topo da sidebar —
// o header NÃO duplica nem quebra o seletor (Spec 102 D6.1).

import { Outlet, useNavigate } from 'react-router-dom';
import { useEffect } from 'react';
import { useAuth } from '../auth';
import { useProfile } from '../profile';
import ProfileSelector from './ProfileSelector';
import Sidebar from './Sidebar';
import HeaderGlobal from './HeaderGlobal';

export default function Layout() {
  const { me, logout, refreshMe } = useAuth();
  const { refreshProfile } = useProfile();
  const navigate = useNavigate();

  useEffect(() => {
    // Spec 123 (D2): perfis de operação carregam junto do refreshMe (login/shell).
    void refreshProfile();
    void refreshMe();
  }, [refreshMe, refreshProfile]);

  const handleLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <>
      {/* Spec 73: skip-link — teclado pula a sidebar para o conteúdo. */}
      <a href="#main-content" className="skip-link">
        Pular para o conteúdo
      </a>
      <div className="layout">
        <aside className="sidebar">
          <div className="brand">FoxCash</div>
          {/* Specs 122/123/139: seletor de contexto de perfil (lente Bot/Gateway
              + dropdown 2 níveis) no topo do shell — preservado (Spec 102 D6.1). */}
          <ProfileSelector />
          <Sidebar />
          <div className="sidebar-footer">
            <div className="merchant-name">{me?.name || '…'}</div>
            <button type="button" className="btn btn-ghost" onClick={handleLogout}>
              Sair
            </button>
          </div>
        </aside>
        <main id="main-content" className="content">
          {/* Spec 102: header global do shell (Spec 101) — acima do conteúdo. */}
          <HeaderGlobal />
          <Outlet />
        </main>
      </div>
    </>
  );
}
