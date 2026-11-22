// Tipos compartilhados do módulo de Broadcast no portal (Spec 80).
// Espelham os contratos da Spec 79 (/api/v1/portal/broadcasts) e da
// Spec 74 (/api/v1/portal/bots).

export type BroadcastMode = 'NOW' | 'SCHEDULED' | 'WELCOME' | 'REPOST';
export type BroadcastStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
export type BroadcastMediaType = 'IMAGE' | 'VIDEO' | 'AUDIO';
export type BotPlatform = 'TELEGRAM' | 'DISCORD';

/** Bot de venda (Spec 74) — resposta do portal/bots. */
export interface StoreBot {
  id: string;
  platform: BotPlatform;
  status: 'REGISTERING' | 'ACTIVE' | 'PAUSED' | 'ERROR';
  displayName: string;
  username: string | null;
  applicationId: string | null;
  tokenMask: string | null;
  errorMessage: string | null;
  lastHealthCheckAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Destino dinâmico do broadcast (Spec 79/D5) — portal/bots/{id}/chats. */
export interface BroadcastChat {
  id: string;
  title: string;
  type: string;
}

/** Broadcast na listagem (Spec 79) — metadados, sem conteúdo em claro. */
export interface BroadcastSummary {
  id: string;
  storeBotId: string;
  mode: BroadcastMode;
  status: BroadcastStatus;
  targetChatId: string | null;
  mediaType: BroadcastMediaType | null;
  errorMessage: string | null;
  scheduledAt: string | null;
  intervalSeconds: number | null;
  createdAt: string;
  updatedAt: string;
}

/** Detalhe do broadcast (Spec 79) — conteúdo + mídia + botões + logs. */
export interface BroadcastDetail extends BroadcastSummary {
  content: string;
  mediaUrl: string | null;
  mediaFileId: string | null;
  buttonsJson: string | null;
}

/** Mídia enviada (resposta do upload da Spec 79). */
export interface UploadedMedia {
  mediaUrl: string | null;
  mediaFileId: string | null;
  mediaType: BroadcastMediaType;
}

/** Botão inline em edição — label + tipo (Link com URL ou Callback bot:comando). */
export interface ButtonDraft {
  label: string;
  type: 'link' | 'callback';
  url: string;
  callback: string;
}
export type ButtonRow = ButtonDraft[];
/** Grade completa de botões (array de linhas) — estrutura do buttons_json. */
export type ButtonGrid = ButtonRow[];

/** Rascunho do formulário do editor (Spec 80). */
export interface BroadcastDraft {
  botId: string;
  targetChatId: string;
  mode: BroadcastMode;
  content: string;
  media: UploadedMedia | null;
  buttons: ButtonGrid;
  scheduledAt: string; // 'YYYY-MM-DDTHH:mm' (datetime-local)
  intervalSeconds: number | null; // apenas REPOST (>= 3600)
}
