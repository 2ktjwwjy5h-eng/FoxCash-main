// Tipos do módulo de Tracking no portal (Spec 82).
// Espelham os contratos da Spec 81 (/api/v1/portal/tracking) e da Spec 74
// (/api/v1/portal/bots — multiselect de vínculo e chips).

export type TrackingStatus = 'ACTIVE' | 'INACTIVE';
export type TrackingEventLogStatus = 'OK' | 'ERROR';

/** Perfil de tracking (Spec 81/D7) — tokens NUNCA em claro (só máscara). */
export interface TrackingProfile {
  id: string;
  name: string;
  status: TrackingStatus;
  facebookPixelId: string | null;
  facebookTokenMask: string | null;
  tiktokPixelId: string | null;
  tiktokTokenMask: string | null;
  utmifyTokenMask: string | null;
  otimizeyTokenMask: string | null;
  /** Badges dos serviços conectados: facebook/tiktok/utmify/otimizey. */
  services: string[];
  botIds: string[];
  okEvents: number;
  errorEvents: number;
  createdAt: string;
  updatedAt: string;
}

/** Linha do log de disparos (Spec 81 — GET /{id}/events). */
export interface TrackingEventLog {
  eventType: string;
  eventId: string;
  status: TrackingEventLogStatus;
  responsePayload: string | null;
  createdAt: string | null;
}

/** Resultado do teste de conexão por provedor (Spec 81 — POST /{id}/sync). */
export interface TrackingSyncResult {
  service: string;
  ok: boolean;
  message: string | null;
}

/** Envelope paginado do backend (PageResponse). */
export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
