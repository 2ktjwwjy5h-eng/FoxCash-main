// Seletor de contexto de perfil (Specs 122/123/139): lente de contexto (Bot |
// Gateway) + dropdown de perfis no shell do portal. Trocar o perfil/lente é
// local (o header X-Profile-ID sai em cada chamada); criar perfil (POST {name,
// scope}), atribuir bots (PUT /{id}/bots) e trocar a rota de gateway (PATCH)
// chamam o backend. Estados loading/saving/erro (padrão UX Spec 73) + banner
// transitório na troca (auto-dismiss, aria-live).

import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useProfile, type Lens, type OperationProfile, type ProfileScope, SCOPE_LABEL, routeLabel } from '../profile';
import { api } from '../apiClient';
import { ErrorBanner, Spinner } from './ui';

/** Rótulo curto do escopo (trigger/menu — o tooltip usa SCOPE_LABEL completo). */
const SCOPE_SHORT: Record<ProfileScope, string> = {
  GENERAL: '⚡ Completo',
  BOT_ONLY: '🤖 Somente Bot',
  GATEWAY_ONLY: '🌐 Somente Gateway',
};

const SCOPE_DESCRIPTION: Record<ProfileScope, string> = {
  GENERAL: 'Bot + Gateway integrados',
  BOT_ONLY: 'Foco em chat e automações',
  GATEWAY_ONLY: 'Foco em páginas web e tráfego',
};

/** Bot do lojista (GET /api/v1/portal/bots — Spec 74). */
interface StoreBot {
  id: string;
  displayName: string;
  platform: 'TELEGRAM' | 'DISCORD';
  status: string;
}

function focusableElements(root: HTMLElement): HTMLElement[] {
  return Array.from(
    root.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    ),
  );
}

/** Dialog acessível local (padrão ConfirmDialog Spec 73 — focus trap + Escape + aria). */
function ProfileDialog({
  open,
  title,
  onClose,
  children,
  footer,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const titleId = useId();
  const ref = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    restoreRef.current = document.activeElement as HTMLElement | null;
    const el = ref.current;
    if (el) {
      const focusables = focusableElements(el);
      focusables[0]?.focus();
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onCloseRef.current();
        return;
      }
      if (e.key !== 'Tab' || !el) return;
      const focusables = focusableElements(el);
      if (!focusables.length) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      restoreRef.current?.focus();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- callbacks lidos via ref
  }, [open]);

  if (!open) return null;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <div className="modal" ref={ref}>
        <h2 id={titleId} style={{ margin: 0, fontSize: 18 }}>
          {title}
        </h2>
        {children}
        {footer && <div className="modal-actions">{footer}</div>}
      </div>
    </div>
  );
}

export default function ProfileSelector() {
  const {
    visibleProfiles,
    loading,
    activeProfile,
    activeProfileId,
    lens,
    setLens,
    setActiveProfileId,
    setGatewayRoute,
    createProfile,
    assignBots,
  } = useProfile();

  const [menuOpen, setMenuOpen] = useState(false);
  const [tooltipProfile, setTooltipProfile] = useState<OperationProfile | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [botsOpen, setBotsOpen] = useState(false);
  const [newName, setNewName] = useState('');
  const [newScope, setNewScope] = useState<ProfileScope>('GENERAL');
  const [allBots, setAllBots] = useState<StoreBot[]>([]);
  const [selectedBotIds, setSelectedBotIds] = useState<string[]>([]);
  const [botsLoading, setBotsLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [banner, setBanner] = useState<string | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const listboxRef = useRef<HTMLDivElement>(null);
  const tooltipId = useId();

  // Banner transitório (Spec 139 — T5.1): auto-dismiss ~3s.
  useEffect(() => {
    if (!banner) return;
    const t = window.setTimeout(() => setBanner(null), 3000);
    return () => window.clearTimeout(t);
  }, [banner]);

  // Carrega os bots do lojista ao abrir o dialog "gerenciar bots".
  useEffect(() => {
    if (!botsOpen) return;
    let cancelled = false;
    setBotsLoading(true);
    setError(null);
    api
      .get<StoreBot[]>('/api/v1/portal/bots')
      .then((bots) => {
        if (cancelled) return;
        setAllBots(bots);
        setSelectedBotIds(
          (activeProfile?.bots || []).map((b) => b.id).filter((id) => bots.some((b) => b.id === id)),
        );
      })
      .catch((err) => {
        if (!cancelled) setError(err);
      })
      .finally(() => {
        if (!cancelled) setBotsLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- abre ao abrir o dialog
  }, [botsOpen]);

  if (loading) {
    return (
      <div className="profile-selector" aria-label="Perfil de operação">
        <Spinner />
      </div>
    );
  }
  if (visibleProfiles.length === 0) {
    return null;
  }

  const handleLensChange = (l: Lens) => {
    setLens(l);
    setBanner(`Contexto: ${l === 'BOT' ? 'Bot' : 'Gateway'}`);
  };

  const handleSelectProfile = (id: string) => {
    setActiveProfileId(id);
    const profile = visibleProfiles.find((p) => p.id === id);
    if (profile) {
      setBanner(`Perfil ativo: ${profile.name} · ${SCOPE_LABEL[profile.scope]}`);
    }
    setMenuOpen(false);
  };

  const handleRouteChange = async (route: string) => {
    if (!activeProfile) return;
    setSaving(true);
    setError(null);
    try {
      await setGatewayRoute(activeProfile.id, route);
    } catch (err) {
      setError(err);
    } finally {
      setSaving(false);
    }
  };

  const handleCreate = async (event: FormEvent) => {
    event.preventDefault();
    if (!newName.trim()) return;
    setSaving(true);
    setError(null);
    try {
      await createProfile({ name: newName.trim(), scope: newScope });
      setNewName('');
      setNewScope('GENERAL');
      setCreateOpen(false);
      setBanner(`Perfil criado: ${newName.trim()}`);
    } catch (err) {
      setError(err);
    } finally {
      setSaving(false);
    }
  };

  const handleAssignBots = async () => {
    if (!activeProfile) return;
    setSaving(true);
    setError(null);
    try {
      await assignBots(activeProfile.id, selectedBotIds);
      setBotsOpen(false);
      setBanner(`Frota do perfil atualizada (${selectedBotIds.length} bots)`);
    } catch (err) {
      setError(err);
    } finally {
      setSaving(false);
    }
  };

  const toggleBot = (id: string) => {
    setSelectedBotIds((prev) => (prev.includes(id) ? prev.filter((b) => b !== id) : [...prev, id]));
  };

  // Navegação por teclado do menu customizado (↑/↓/Enter/Esc — ARIA combobox/menu).
  const onMenuKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const dir = e.key === 'ArrowDown' ? 1 : -1;
      const current = visibleProfiles.findIndex((p) => p.id === (activeProfileId ?? ''));
      const next = (current + dir + visibleProfiles.length) % visibleProfiles.length;
      const el = listboxRef.current?.querySelector<HTMLElement>(`[data-profile-id="${visibleProfiles[next].id}"]`);
      el?.focus();
      setTooltipProfile(visibleProfiles[next]);
    } else if (e.key === 'Escape') {
      setMenuOpen(false);
      triggerRef.current?.focus();
    } else if (e.key === 'Enter' || e.key === ' ') {
      const el = document.activeElement as HTMLElement | null;
      const id = el?.dataset?.profileId;
      if (id) handleSelectProfile(id);
    }
  };

  const fleet = activeProfile?.bots || [];

  return (
    <div className="profile-selector">
      {/* Nível 1 — contexto (lente): Bot | Gateway */}
      <div className="profile-lens" role="group" aria-label="Contexto de operação">
        <button
          type="button"
          className={`lens-btn${lens === 'BOT' ? ' active' : ''}`}
          aria-pressed={lens === 'BOT'}
          onClick={() => handleLensChange('BOT')}
          disabled={saving}
        >
          🤖 Bot
        </button>
        <button
          type="button"
          className={`lens-btn${lens === 'GATEWAY' ? ' active' : ''}`}
          aria-pressed={lens === 'GATEWAY'}
          onClick={() => handleLensChange('GATEWAY')}
          disabled={saving}
        >
          🌐 Gateway
        </button>
      </div>

      {/* Nível 2 — perfil (menu customizado com tooltip de resumo) */}
      <div className="profile-menu">
        <button
          ref={triggerRef}
          type="button"
          className="profile-menu-trigger"
          aria-haspopup="listbox"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((o) => !o)}
          onKeyDown={(e) => {
            // Escape fecha o combobox mesmo com o foco no trigger (ARIA combobox).
            if (e.key === 'Escape') setMenuOpen(false);
          }}
          disabled={saving}
        >
          <span className="profile-menu-trigger-name">{activeProfile?.name ?? 'Perfil'}</span>
          {activeProfile && <span className="profile-menu-trigger-scope">{SCOPE_SHORT[activeProfile.scope]}</span>}
        </button>

        {menuOpen && (
          <div className="profile-menu-dropdown" role="listbox" aria-label="Perfis" ref={listboxRef} onKeyDown={onMenuKeyDown}>
            {visibleProfiles.map((p) => (
              <button
                key={p.id}
                type="button"
                role="option"
                data-profile-id={p.id}
                aria-selected={p.id === activeProfileId}
                aria-describedby={tooltipId}
                className={`profile-option${p.id === activeProfileId ? ' selected' : ''}`}
                onClick={() => handleSelectProfile(p.id)}
                onMouseEnter={() => setTooltipProfile(p)}
                onMouseLeave={() => setTooltipProfile(null)}
                onFocus={() => setTooltipProfile(p)}
                onBlur={() => setTooltipProfile(null)}
              >
                <span className="profile-option-name">{p.name}</span>
                <span className="profile-option-scope">{SCOPE_SHORT[p.scope]}</span>
              </button>
            ))}
          </div>
        )}

        {/* Tooltip de resumo do perfil (Spec 139 — D5.2): hover/foco na opção. */}
        {menuOpen && tooltipProfile && (
          <div className="profile-tooltip" id={tooltipId} role="tooltip">
            <div className="profile-tooltip-scope">{SCOPE_LABEL[tooltipProfile.scope]}</div>
            <div className="profile-tooltip-row">
              <strong>Bots:</strong>{' '}
              {tooltipProfile.bots.length === 0
                ? 'sem bots na frota'
                : tooltipProfile.bots.slice(0, 5).map((b) => b.displayName).join(', ') +
                  (tooltipProfile.bots.length > 5 ? ` +${tooltipProfile.bots.length - 5}` : '')}
            </div>
            <div className="profile-tooltip-row">
              <strong>Rota:</strong>{' '}
              {tooltipProfile.scope === 'BOT_ONLY'
                ? 'rota por risco (padrão)'
                : routeLabel(tooltipProfile.gatewayRoute)}
            </div>
          </div>
        )}
      </div>

      {/* Painel do contexto (resumo do perfil ativo por lente) */}
      {activeProfile && (
        <div className="profile-context">
          {lens === 'BOT' ? (
            <div className="profile-fleet">
              {fleet.length === 0 ? (
                <span className="muted">Este perfil ainda não tem bots na frota.</span>
              ) : (
                fleet.slice(0, 3).map((b) => (
                  <span key={b.id} className="profile-fleet-chip" title={b.status}>
                    {b.displayName}
                  </span>
                ))
              )}
              <button
                type="button"
                className="btn btn-small btn-ghost"
                onClick={() => {
                  setMenuOpen(false);
                  setBotsOpen(true);
                }}
                disabled={saving}
              >
                gerenciar bots
              </button>
            </div>
          ) : (
            <div className="profile-route">
              <span className="muted">
                Rota: <strong>{routeLabel(activeProfile.gatewayRoute)}</strong>
              </span>
              {activeProfile.availableRoutes.length > 1 && (
                <select
                  className="input"
                  aria-label="Rota de gateway do perfil"
                  value={activeProfile.gatewayRoute}
                  disabled={saving}
                  onChange={(e) => void handleRouteChange(e.target.value)}
                >
                  {activeProfile.availableRoutes.map((route) => (
                    <option key={route} value={route}>
                      {routeLabel(route)}
                    </option>
                  ))}
                </select>
              )}
              <span className="muted">A rota define qual adquirente processa o Pix deste perfil.</span>
            </div>
          )}
        </div>
      )}

      <button
        type="button"
        className="btn btn-small btn-ghost"
        onClick={() => {
          setMenuOpen(false);
          setCreateOpen(true);
        }}
        disabled={saving}
      >
        + criar perfil
      </button>

      <div className="profile-selector-error" aria-live="polite">
        <ErrorBanner error={error} />
      </div>

      {/* Banner transitório (Spec 139 — T5.1): acessível, auto-dismiss. */}
      {banner && (
        <div className="profile-banner" role="status" aria-live="polite">
          {banner}
        </div>
      )}

      {/* Dialog: Criar Novo Perfil (modal limpo — nome + Tipo de Operação) */}
      <ProfileDialog
        open={createOpen}
        title="Criar Novo Perfil"
        onClose={() => {
          if (!saving) setCreateOpen(false);
        }}
        footer={
          <>
            <button type="button" className="btn" onClick={() => setCreateOpen(false)} disabled={saving}>
              Cancelar
            </button>
            <button type="submit" form="profile-create-form" className="btn btn-primary" disabled={saving || !newName.trim()}>
              {saving ? 'Criando…' : 'Criar'}
            </button>
          </>
        }
      >
        <form id="profile-create-form" onSubmit={(e) => void handleCreate(e)}>
          <label className="field">
            <span className="field-label">Nome</span>
            <input
              className="input"
              aria-label="Nome do novo perfil"
              placeholder="Ex.: Padrão BR"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              maxLength={64}
              autoFocus
            />
          </label>
          <fieldset className="profile-scope-options">
            <legend className="field-label">Tipo de Operação</legend>
            {(Object.keys(SCOPE_LABEL) as ProfileScope[]).map((scope) => (
              <label key={scope} className={`profile-scope-option${newScope === scope ? ' selected' : ''}`}>
                <input
                  type="radio"
                  name="profile-scope"
                  value={scope}
                  checked={newScope === scope}
                  onChange={() => setNewScope(scope)}
                />
                <span className="profile-scope-option-label">{SCOPE_SHORT[scope]}</span>
                <span className="profile-scope-option-desc">{SCOPE_DESCRIPTION[scope]}</span>
              </label>
            ))}
          </fieldset>
        </form>
      </ProfileDialog>

      {/* Dialog: gerenciar bots (atribuição da frota — PUT /{id}/bots) */}
      <ProfileDialog
        open={botsOpen}
        title="Frota do perfil"
        onClose={() => {
          if (!saving) setBotsOpen(false);
        }}
        footer={
          <>
            <button type="button" className="btn" onClick={() => setBotsOpen(false)} disabled={saving}>
              Cancelar
            </button>
            <button type="button" className="btn btn-primary" onClick={() => void handleAssignBots()} disabled={saving || botsLoading}>
              {saving ? 'Salvando…' : 'Salvar frota'}
            </button>
          </>
        }
      >
        {botsLoading ? (
          <Spinner />
        ) : allBots.length === 0 ? (
          <p className="muted">Nenhum bot registrado. Crie um bot para atribuir à frota.</p>
        ) : (
          <div className="profile-bot-list">
            {allBots.map((b) => (
              <label key={b.id} className="profile-bot-option">
                <input type="checkbox" checked={selectedBotIds.includes(b.id)} onChange={() => toggleBot(b.id)} />
                <span>
                  {b.displayName} <span className="muted">({b.platform.toLowerCase()} · {b.status.toLowerCase()})</span>
                </span>
              </label>
            ))}
          </div>
        )}
      </ProfileDialog>
    </div>
  );
}
