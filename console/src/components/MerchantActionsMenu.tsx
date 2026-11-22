// Menu de ações administrativas por linha da tabela de lojistas (Spec 99 D3):
// navegação (Ver detalhes / Ver shops / Config / Sacar) + atalhos de controle
// (Desativar/Ativar, Bloquear API, Banir, Marcar honeypot) que APONTAM para a
// tela de config (Spec 100) — a tabela exibe e navega; a escrita mora na config.
// RBAC-aware (Spec 69 — a UI esconde itens sem permissão; o backend decide).

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { RequirePermission } from '../hooks/usePermission';
import type { MerchantPanelItem } from '../types';

export interface MerchantActionsMenuProps {
  merchant: MerchantPanelItem;
  /** Abre o modal "Ver Shops" (Spec 97 D3.4). */
  onViewShops: (merchant: MerchantPanelItem) => void;
}

export default function MerchantActionsMenu({ merchant, onViewShops }: MerchantActionsMenuProps) {
  const [open, setOpen] = useState(false);
  const id = merchant.id;
  const banned = merchant.status === 'BANNED';

  const close = () => setOpen(false);

  return (
    <div className="menu-wrap">
      <button type="button" className="btn btn-small" onClick={() => setOpen((o) => !o)}>
        Ações ▾
      </button>
      {open && (
        <>
          {/* Backdrop transparente fecha o menu em clique externo. */}
          <div className="menu-backdrop" onClick={close} />
          <div className="menu-dropdown" role="menu">
            <Link className="menu-item" to={`/console/merchants/${id}`} onClick={close}>
              Ver detalhes
            </Link>
            <button type="button" className="menu-item" onClick={() => { close(); onViewShops(merchant); }}>
              Ver shops
            </button>
            <RequirePermission permission="merchant.config">
              <Link className="menu-item" to={`/console/merchants/${id}/config`} onClick={close}>
                Config
              </Link>
            </RequirePermission>
            <Link className="menu-item" to={`/console/merchants/${id}/withdrawals`} onClick={close}>
              Sacar
            </Link>

            {!banned && (
              <>
                <div className="menu-separator" />
                {/* Atalhos de controle → tela de config (Spec 100): a tabela
                    navega; o estado real + toggles vivem na config. */}
                <RequirePermission permission="merchant.config">
                  <Link className="menu-item" to={`/console/merchants/${id}/config`} onClick={close}>
                    {merchant.status === 'INACTIVE' ? 'Ativar' : 'Desativar'}
                  </Link>
                </RequirePermission>
                <RequirePermission permission="merchant.config">
                  <Link className="menu-item" to={`/console/merchants/${id}/config`} onClick={close}>
                    Bloquear API
                  </Link>
                </RequirePermission>
                <RequirePermission permission="merchant.config">
                  <Link className="menu-item" to={`/console/merchants/${id}/config`} onClick={close}>
                    Marcar honeypot
                  </Link>
                </RequirePermission>
                <RequirePermission permission="merchant.ban">
                  <Link className="menu-item menu-danger" to={`/console/merchants/${id}/config`} onClick={close}>
                    Banir
                  </Link>
                </RequirePermission>
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}
