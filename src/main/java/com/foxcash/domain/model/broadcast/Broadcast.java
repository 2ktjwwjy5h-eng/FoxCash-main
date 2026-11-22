package com.foxcash.domain.model.broadcast;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Broadcast (Spec 79) — transmissão em massa do bot (Telegram/Discord) para um
 * canal/grupo (NOW/SCHEDULED/REPOST) ou no evento de entrada de membro
 * (WELCOME). O backend orquestra e persiste; o bot (Spec 52) busca pendências
 * via API e reporta o log por destinatário — sem fila externa (decisão do
 * usuário 2026-08-12: sem BullMQ).
 *
 * <p><b>Máquina de estados</b> ({@link BroadcastStatus}):
 * {@code PENDING → PROCESSING → COMPLETED/FAILED}. {@code NOW}/{@code SCHEDULED}
 * viram {@code PROCESSING} quando o bot consulta {@code due} (pull, padrão Spec
 * 51); {@code REPOST} é re-agendado pelo job ({@code PENDING → PROCESSING →
 * COMPLETED → PENDING}, sempre com {@code scheduled_at} futuro). {@code WELCOME}
 * não participa do {@code due} — é resolvido no evento de entrada
 * ({@code welcome-trigger}).
 *
 * <p><b>Cancelamento</b> (D3): DELETE físico de {@code PENDING} (inclui
 * {@code SCHEDULED}/{@code REPOST} aguardando) é permitido e auditado;
 * {@code PROCESSING} não pode cancelar (409). Sem estado {@code CANCELLED} no
 * schema externo.
 */
public class Broadcast {

    public static final int MIN_REPOST_INTERVAL_SECONDS = 3600; // 1h (D8)
    private static final int MAX_ERROR_MESSAGE = 500;

    private final UUID id;
    private final UUID storeBotId;
    private final BroadcastMode mode;
    private final String targetChatId;
    private final String content;
    private final String mediaUrl;
    private final BroadcastMediaType mediaType;
    private final String mediaFileId;
    private final BroadcastButtons buttons;
    private BroadcastStatus status;
    private String errorMessage;
    private Instant scheduledAt;
    private final Integer intervalSeconds;
    private final Instant createdAt;
    private Instant updatedAt;

    private Broadcast(UUID id, UUID storeBotId, BroadcastMode mode, String targetChatId,
                      String content, String mediaUrl, BroadcastMediaType mediaType,
                      String mediaFileId, BroadcastButtons buttons, BroadcastStatus status,
                      String errorMessage, Instant scheduledAt, Integer intervalSeconds,
                      Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.storeBotId = storeBotId;
        this.mode = mode;
        this.targetChatId = targetChatId;
        this.content = content;
        this.mediaUrl = mediaUrl;
        this.mediaType = mediaType;
        this.mediaFileId = mediaFileId;
        this.buttons = buttons;
        this.status = status;
        this.errorMessage = errorMessage;
        this.scheduledAt = scheduledAt;
        this.intervalSeconds = intervalSeconds;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Cria um broadcast (status {@code PENDING}). Validações (D3):
     * {@code target_chat_id} obrigatório para NOW/SCHEDULED/REPOST (WELCOME pode
     * ser sem destino fixo — o disparo é por evento); {@code scheduled_at} no
     * futuro para SCHEDULED; {@code interval_seconds >= 3600} para REPOST;
     * conteúdo e/ou mídia (pelo menos um).
     *
     * <p>{@code buttons} já deve vir validado pela aplicação
     * ({@link BroadcastButtons#parse(String, com.foxcash.domain.model.storebot.BotPlatform,
     * java.util.function.Function)} — a URL SSRF-safe é validada fora do domínio).
     */
    public static Broadcast create(UUID storeBotId, BroadcastMode mode, String targetChatId,
                                   String content, String mediaUrl, BroadcastMediaType mediaType,
                                   String mediaFileId, BroadcastButtons buttons,
                                   Instant scheduledAt, Integer intervalSeconds, Instant now) {
        Objects.requireNonNull(storeBotId, "storeBotId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(now, "now must not be null");
        validate(mode, targetChatId, content, mediaUrl, mediaType, mediaFileId,
                scheduledAt, intervalSeconds, now);
        return new Broadcast(UUID.randomUUID(), storeBotId, mode, targetChatId,
                content == null ? "" : content, mediaUrl, mediaType, mediaFileId, buttons,
                BroadcastStatus.PENDING, null, scheduledAt, intervalSeconds, now, now);
    }

    /** Restaura do banco (sem validações — o dado persistido é confiável). */
    public static Broadcast restore(UUID id, UUID storeBotId, BroadcastMode mode, String targetChatId,
                                    String content, String mediaUrl, BroadcastMediaType mediaType,
                                    String mediaFileId, BroadcastButtons buttons, BroadcastStatus status,
                                    String errorMessage, Instant scheduledAt, Integer intervalSeconds,
                                    Instant createdAt, Instant updatedAt) {
        return new Broadcast(id, storeBotId, mode, targetChatId, content, mediaUrl, mediaType,
                mediaFileId, buttons, status, errorMessage, scheduledAt, intervalSeconds,
                createdAt, updatedAt);
    }

    // ---- Máquina de estados ----

    /**
     * O bot pegou a pendência: {@code PENDING → PROCESSING}. Válido apenas em
     * {@code PENDING} (replay/concorrência → no-op {@code false}).
     */
    public boolean markProcessing(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != BroadcastStatus.PENDING) {
            return false;
        }
        status = BroadcastStatus.PROCESSING;
        updatedAt = now;
        return true;
    }

    /**
     * Envio concluído: {@code PROCESSING → COMPLETED}. {@code COMPLETED} se
     * houver ≥1 {@code SUCCESS}; caso contrário {@link #fail(String, Instant)}.
     * Replay de um broadcast já concluído → no-op {@code false}.
     */
    public boolean complete(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != BroadcastStatus.PROCESSING) {
            return false;
        }
        status = BroadcastStatus.COMPLETED;
        errorMessage = null;
        updatedAt = now;
        return true;
    }

    /**
     * Falha: {@code PROCESSING → FAILED} com {@code error_message} (nenhum
     * {@code SUCCESS} no log — os logs individuais são sempre preservados).
     */
    public boolean fail(String message, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != BroadcastStatus.PROCESSING) {
            return false;
        }
        status = BroadcastStatus.FAILED;
        errorMessage = message == null ? null : (message.length() > MAX_ERROR_MESSAGE
                ? message.substring(0, MAX_ERROR_MESSAGE) : message);
        updatedAt = now;
        return true;
    }

    /**
     * Cancelamento forçado pelo Master (Spec 89): {@code PENDING → FAILED} com
     * {@code error_message} (registro permanece — não é delete físico, trilha de
     * auditoria do operador). Aditivo ao {@link #fail(String, Instant)} (que só
     * aceita {@code PROCESSING}): o master cancela campanhas que ainda não
     * começaram a disparar. Válido apenas em {@code PENDING} (retorna
     * {@code false} caso contrário — o service valida antes e lança 409).
     */
    public boolean forceCancel(String reason, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != BroadcastStatus.PENDING) {
            return false;
        }
        status = BroadcastStatus.FAILED;
        errorMessage = reason == null ? null : (reason.length() > MAX_ERROR_MESSAGE
                ? reason.substring(0, MAX_ERROR_MESSAGE) : reason);
        updatedAt = now;
        return true;
    }

    /**
     * Re-agenda (job REPOST, Spec 53): {@code COMPLETED → PENDING} com
     * {@code scheduled_at = now + interval_seconds}. Apenas modo {@code REPOST}
     * com {@code interval_seconds} definido (D10).
     */
    public boolean reschedule(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (mode != BroadcastMode.REPOST || intervalSeconds == null) {
            return false;
        }
        if (status != BroadcastStatus.COMPLETED) {
            return false;
        }
        status = BroadcastStatus.PENDING;
        errorMessage = null;
        scheduledAt = now.plusSeconds(intervalSeconds);
        updatedAt = now;
        return true;
    }

    /** O broadcast pode ser cancelado (DELETE físico — D3: apenas PENDING)? */
    public boolean isCancellable() {
        return status == BroadcastStatus.PENDING;
    }

    /**
     * Está pendente de disparo até {@code dueBefore}? O bot (pull, padrão Spec
     * 51) busca: {@code PENDING} com {@code scheduled_at <= dueBefore} OU
     * {@code NOW} sem agendamento ({@code scheduled_at} nulo). WELCOME nunca é
     * "vencida" — é resolvida no evento de entrada.
     */
    public boolean isDue(Instant dueBefore) {
        Objects.requireNonNull(dueBefore, "dueBefore must not be null");
        if (mode == BroadcastMode.WELCOME || status != BroadcastStatus.PENDING) {
            return false;
        }
        return scheduledAt == null || !scheduledAt.isAfter(dueBefore);
    }

    // ---- Leitura ----

    public UUID id() {
        return id;
    }

    public UUID storeBotId() {
        return storeBotId;
    }

    public BroadcastMode mode() {
        return mode;
    }

    /** Canal/grupo destino (NOW/SCHEDULED/REPOST); null para WELCOME sem destino fixo. */
    public String targetChatId() {
        return targetChatId;
    }

    /** Rich-text da plataforma (tags {primeiro_nome} etc. resolvidas pelo bot). */
    public String content() {
        return content;
    }

    public String mediaUrl() {
        return mediaUrl;
    }

    public BroadcastMediaType mediaType() {
        return mediaType;
    }

    public String mediaFileId() {
        return mediaFileId;
    }

    public BroadcastButtons buttons() {
        return buttons;
    }

    public BroadcastStatus status() {
        return status;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant scheduledAt() {
        return scheduledAt;
    }

    public Integer intervalSeconds() {
        return intervalSeconds;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private static void validate(BroadcastMode mode, String targetChatId, String content,
                                 String mediaUrl, BroadcastMediaType mediaType, String mediaFileId,
                                 Instant scheduledAt, Integer intervalSeconds, Instant now) {
        if (mode != BroadcastMode.WELCOME && (targetChatId == null || targetChatId.isBlank())) {
            throw new IllegalArgumentException(
                    "target_chat_id e obrigatorio para o modo " + mode);
        }
        boolean hasContent = content != null && !content.isBlank();
        boolean hasMedia = (mediaUrl != null && !mediaUrl.isBlank())
                || (mediaFileId != null && !mediaFileId.isBlank());
        if (!hasContent && !hasMedia) {
            throw new IllegalArgumentException("informe content e/ou midia (media_url/media_file_id)");
        }
        if (mode == BroadcastMode.SCHEDULED) {
            if (scheduledAt == null) {
                throw new IllegalArgumentException("scheduled_at e obrigatorio no modo SCHEDULED");
            }
            if (!scheduledAt.isAfter(now)) {
                throw new IllegalArgumentException("scheduled_at deve ser no futuro (modo SCHEDULED)");
            }
        }
        if (mode == BroadcastMode.REPOST) {
            if (intervalSeconds == null || intervalSeconds < MIN_REPOST_INTERVAL_SECONDS) {
                throw new IllegalArgumentException(
                        "interval_seconds e obrigatorio no modo REPOST (minimo "
                                + MIN_REPOST_INTERVAL_SECONDS + "s)");
            }
        } else if (intervalSeconds != null) {
            throw new IllegalArgumentException("interval_seconds e exclusivo do modo REPOST");
        }
    }
}
