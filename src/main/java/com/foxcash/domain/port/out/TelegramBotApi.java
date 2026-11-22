package com.foxcash.domain.port.out;

import java.util.List;

/**
 * Porta de saída: cliente da Bot API do Telegram (Spec 74) —
 * {@code https://api.telegram.org/bot{token}/{method}}. A integração registra o
 * webhook com {@code secret_token} aleatório e valida o token via {@code getMe}
 * (nome/username gravados no registro). Mock de contrato em teste (padrão
 * {@code PayoutProviderContractTest}).
 */
public interface TelegramBotApi {

    /** Valida o token e devolve a identidade do bot (getMe). */
    TelegramUserInfo getMe(String token);

    /** Registra o webhook de updates com {@code secret_token} (URL com o segredo). */
    void setWebhook(String token, String url, String secretToken);

    /** Remove o webhook (pause/remover; idempotente). */
    void deleteWebhook(String token);

    /** Descrição do bot exibida no perfil (Spec 74: edição de descrição). */
    void setMyDescription(String token, String description);

    /** Descrição curta do bot (setMyShortDescription). */
    void setMyShortDescription(String token, String shortDescription);

    /**
     * Foto de perfil do bot (setMyPhoto — Spec 77, wizard): multipart com o
     * arquivo de imagem (Telegram: JPEG ≤ 5MB).
     */
    void setMyPhoto(String token, byte[] photo, String filename);

    /** Comandos do menu do bot (setMyCommands). */
    void setMyCommands(String token, List<BotCommand> commands);

    /** Estado atual do webhook (health check do job de reconciliação). */
    TelegramWebhookInfo getWebhookInfo(String token);

    /**
     * Canais/grupos onde o bot é membro/admin (Spec 79/D5 — destino dinâmico do
     * broadcast). Fonte: {@code getUpdates} (dedup por chat) + {@code getChat}
     * para título/tipo. Quando o webhook está ativo (produção), {@code getUpdates}
     * devolve 409 — o adapter retorna lista vazia e o renderizador (Spec 52)
     * reporta os chats via API; em dev/E2E (sem webhook) lista os chats reais.
     */
    List<ChatInfo> listChats(String token);

    /**
     * Envia a mídia do broadcast ao canal CDN do bot (Spec 79/D7) e devolve o
     * {@code file_id} para reuso nos disparos — o bot NUNCA re-uploada o
     * binário por destinatário ({@code sendPhoto}/{@code sendVideo}/
     * {@code sendAudio} conforme o tipo).
     *
     * @param mediaType tipo da mídia (image/video/audio) — define o método.
     * @param bytes     binário validado (imagem ≤10MB; vídeo/áudio ≤50MB).
     * @return o {@code file_id} retornado pela Bot API.
     */
    String sendMedia(String token, String mediaType, byte[] bytes, String filename);

    /**
     * Envia uma mensagem de texto ao usuário (Spec 83 D5 — envio do painel do
     * CRM). {@code chatId} é o {@code platform_user_id} do lead (DM). Se o
     * usuário bloqueou o bot (403 "bot was blocked by the user") → lança
     * {@link com.foxcash.domain.exception.LeadBlockedException} (o CRM marca o
     * lead {@code BLOQUEADO} com auditoria — Spec 83 D6).
     */
    void sendMessage(String token, String chatId, String text);

    /** Identidade do bot retornada pelo {@code getMe}. */
    record TelegramUserInfo(long id, String username, String displayName) {
    }

    /** Canal/grupo onde o bot participa (destino do broadcast). */
    record ChatInfo(String id, String title, String type) {
    }

    /** Comando do menu do bot (setMyCommands). */
    record BotCommand(String command, String description) {
    }

    /** Estado do webhook registrado (getWebhookInfo). */
    record TelegramWebhookInfo(String url, int pendingUpdateCount) {
    }
}
