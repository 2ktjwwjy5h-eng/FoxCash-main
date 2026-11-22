package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.broadcast.BroadcastMediaType;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TelegramBotApi;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Aplicação: upload de mídia do broadcast (Spec 79/D7) —
 * {@code POST /api/v1/portal/broadcasts/media} (multipart). Valida o tipo e o
 * tamanho (imagem ≤10MB; vídeo/áudio ≤50MB) e devolve
 * {@code {media_url, media_file_id, media_type}} para o editor (Spec 80).
 *
 * <p><b>Telegram</b>: envia o binário ao canal CDN do bot uma vez
 * ({@code foxcash.bots.telegram-cdn-channel-id}) e reusa o {@code file_id} nos
 * disparos — o bot NUNCA re-uploada por destinatário. <b>Discord</b>: sem
 * re-upload no backend (o renderizador Spec 52 gerencia o attachment) — o
 * editor envia a URL da CDN do Discord como {@code media_url}.
 */
@Service
public class BroadcastMediaService {

    /** Imagem ≤ 10MB (D7). */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    /** Vídeo/áudio ≤ 50MB (D7). */
    static final long MAX_VIDEO_AUDIO_BYTES = 50L * 1024 * 1024;

    private final StoreBotRepository botRepository;
    private final TelegramBotApi telegramApi;
    private final DiscordBotApi discordApi;

    public BroadcastMediaService(StoreBotRepository botRepository,
                                 TelegramBotApi telegramApi,
                                 DiscordBotApi discordApi) {
        this.botRepository = botRepository;
        this.telegramApi = telegramApi;
        this.discordApi = discordApi;
    }

    /**
     * Valida e processa o upload da mídia do broadcast.
     *
     * @param merchantId   lojista do principal (escopo IDOR-safe).
     * @param storeBotId   bot do lojista (define a plataforma).
     * @param mediaType    tipo da mídia (image/video/audio).
     * @param bytes        binário do arquivo (multipart).
     * @param filename     nome original (para o multipart da plataforma).
     * @return {@code {media_url, media_file_id, media_type}} — Telegram devolve
     *         {@code media_file_id} (file_id da CDN); Discord devolve a URL.
     */
    public MediaUploadResult upload(UUID merchantId, UUID storeBotId, String mediaType,
                                    byte[] bytes, String filename) {
        StoreBot bot = botRepository.findByIdAndMerchantId(storeBotId, merchantId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(StoreBotNotFoundException::new);
        BroadcastMediaType type = requireType(mediaType);
        validateSize(type, bytes);
        if (bot.platform() == BotPlatform.TELEGRAM) {
            // Canal CDN: envia 1× e reusa o file_id nos disparos (sem re-upload).
            String fileId = telegramApi.sendMedia(bot.token(), type.name(), bytes, filename);
            return new MediaUploadResult(null, fileId, type);
        }
        // Discord: o attachment é gerenciado pelo renderizador (Spec 52) — o
        // editor fornece a URL da CDN. Sem re-upload no backend.
        return new MediaUploadResult(null, null, type);
    }

    private static BroadcastMediaType requireType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("media_type e obrigatorio (image, video ou audio)");
        }
        try {
            return BroadcastMediaType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("media_type invalido: " + raw
                    + " (use image, video ou audio)");
        }
    }

    private static void validateSize(BroadcastMediaType type, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("arquivo de midia vazio");
        }
        long max = type == BroadcastMediaType.IMAGE ? MAX_IMAGE_BYTES : MAX_VIDEO_AUDIO_BYTES;
        if (bytes.length > max) {
            throw new IllegalArgumentException(
                    "midia excede o limite de " + (max / (1024 * 1024)) + "MB para " + type);
        }
    }

    /** Resultado do upload — o disparo reusa {@code media_file_id} (Telegram). */
    public record MediaUploadResult(String mediaUrl, String mediaFileId, BroadcastMediaType mediaType) {
    }
}
