package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência dos broadcasts (Spec 79).
 *
 * <p>Consultas do portal E do bot (API key, principal = {@code merchantId}) são
 * sempre escopadas por lojista (join com {@code store_bots} — nunca por id
 * solto, IDOR-safe); o job de REPOST (sistema) varre os {@code COMPLETED}. O
 * master (Spec 89) é cross-merchant — {@link #findById(UUID)} e
 * {@link #searchMaster(MasterBroadcastSearchQuery)} sem escopo de lojista (o
 * master não tem merchant; lê a plataforma inteira).
 */
public interface BroadcastRepository {

    Broadcast save(Broadcast broadcast);

    /** Detalhe por id + lojista (404 {@code broadcast_not_found} cross-lojista). */
    Optional<Broadcast> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Detalhe por id SEM escopo (Spec 89 — master cross-merchant). */
    Optional<Broadcast> findById(UUID id);

    /** Lista do portal (filtro opcional por modo/status). */
    List<Broadcast> findByMerchantId(UUID merchantId, BroadcastMode mode, BroadcastStatus status);

    /**
     * Lista do portal escopada por bot-set (Spec 124 — filtro rígido por
     * perfil): {@code store_bot_id IN :botIds} (+ join com {@code store_bots}
     * por lojista — IDOR-safe). Lista vazia → vazio (header inválido nunca
     * vaza).
     */
    List<Broadcast> findByMerchantIdAndBotIds(UUID merchantId, List<UUID> botIds,
                                              BroadcastMode mode, BroadcastStatus status);

    /**
     * Busca paginada cross-merchant para o Master Console (Spec 89): filtros
     * {@code merchantId}, {@code botId}, {@code status}, {@code mode},
     * {@code from}/{@code to} (sobre {@code created_at}), ordenação
     * {@code created_at DESC}. A lista NÃO devolve {@code content} (só no
     * detalhe) — evita payloads pesados na visão geral.
     */
    PageResult<Broadcast> searchMaster(MasterBroadcastSearchQuery query);

    /**
     * Pendências do lojista para o bot (pull, padrão Spec 51): {@code PENDING}
     * com {@code scheduled_at <= dueBefore} ou {@code NOW} sem agendamento.
     * WELCOME nunca é "vencida" (resolvida por evento).
     */
    List<Broadcast> findDue(UUID merchantId, Instant dueBefore);

    /** WELCOME ativo de um bot para um chat (o mais recente com {@code target_chat_id}). */
    List<Broadcast> findWelcomeForChat(UUID storeBotId, String chatId);

    /** REPOST em {@code COMPLETED} — candidatos a re-agendamento pelo job (D10). */
    List<Broadcast> findCompletedReposts();

    /** DELETE físico (cancelamento de PENDING — D3; auditado na aplicação). */
    void delete(Broadcast broadcast);

    /** Filtros da busca cross-merchant do Master Console (Spec 89). */
    record MasterBroadcastSearchQuery(
            UUID merchantId,
            UUID botId,
            BroadcastStatus status,
            BroadcastMode mode,
            Instant from,
            Instant to,
            int page,
            int size) {
    }
}
