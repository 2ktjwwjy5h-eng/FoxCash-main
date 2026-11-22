// RBAC-aware do Master Console (Spec 71, fatia 1; Spec 69): a UI só decide
// APRESENTAÇÃO — esconde navegação/botões sem a permissão; o backend continua a
// autoridade (403 `forbidden` deny-by-default; a UI nunca autoriza).

import type { ReactNode } from 'react';
import { useAuth } from '../auth';

/** O master autenticado tem a permissão? (catálogo da Spec 69). */
export function usePermission(permission: string): boolean {
  const { me } = useAuth();
  return !!me && me.permissions.includes(permission);
}

/** O master autenticado é ADMIN (can_manage_masters — nível da Spec 69)? */
export function useCanManageMasters(): boolean {
  const { me } = useAuth();
  return !!me && me.canManageMasters;
}

/** Renderiza os filhos apenas com a permissão (botões de ação, etc.). */
export function RequirePermission({ permission, children }: { permission: string; children: ReactNode }) {
  const allowed = usePermission(permission);
  if (!allowed) return null;
  return <>{children}</>;
}
