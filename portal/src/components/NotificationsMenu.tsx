// NotificationsMenu (Spec 102) — dropdown de notificações do header (Spec 101):
// botão com badge (unreadCount) + menu role="menu" com as items. Padrão UX da
// Spec 73 (fecha no Escape/clique fora; role/aria corretos).

import { useEffect, useRef, useState } from 'react';
import type { ShellNotifications } from './HeaderGlobal';

function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

export default function NotificationsMenu({ notifications }: { notifications: ShellNotifications }) {
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const unread = notifications.unreadCount ?? 0;

  // Fecha no Escape e no clique fora (padrão Spec 73).
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    const onClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onClickOutside);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onClickOutside);
    };
  }, [open]);

  return (
    <div className="notifications-menu" ref={menuRef}>
      <button
        type="button"
        className="header-icon-btn notifications-trigger"
        aria-label={`Notificações${unread > 0 ? ` (${unread} não lidas)` : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        🔔
        {unread > 0 && <span className="notifications-badge">{unread}</span>}
      </button>

      {open && (
        <div className="notifications-dropdown" role="menu" aria-label="Notificações">
          {notifications.items.length === 0 ? (
            <div className="notifications-empty muted">Sem notificações</div>
          ) : (
            notifications.items.map((item) => (
              <div key={item.id} className="notifications-item" role="menuitem">
                <div className="notifications-item-message">{item.message}</div>
                <div className="notifications-item-meta muted">
                  {item.type} · {formatTime(item.createdAt)}
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}
