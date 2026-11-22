package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.BroadcastJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para broadcasts (Spec 79).
 * As consultas do portal E do bot (API key, principal = {@code merchantId}) são
 * sempre por lojista (join com {@code store_bots} — nunca por id solto,
 * IDOR-safe); o job de REPOST varre os {@code COMPLETED} (sistema). O master
 * (Spec 89) usa {@link JpaSpecificationExecutor} (filtros dinâmicos no adapter
 * — mesmo padrão do {@code MasterAuditPersistenceAdapter}).
 */
public interface BroadcastJpaRepository extends JpaRepository<BroadcastJpaEntity, UUID>,
        JpaSpecificationExecutor<BroadcastJpaEntity> {

    @Query("SELECT b FROM BroadcastJpaEntity b JOIN StoreBotJpaEntity sb ON sb.id = b.storeBotId "
            + "WHERE b.id = :id AND sb.merchantId = :merchantId")
    Optional<BroadcastJpaEntity> findByIdAndMerchantId(@Param("id") UUID id,
                                                       @Param("merchantId") UUID merchantId);

    @Query("SELECT b FROM BroadcastJpaEntity b JOIN StoreBotJpaEntity sb ON sb.id = b.storeBotId "
            + "WHERE sb.merchantId = :merchantId "
            + "AND (:mode IS NULL OR b.mode = :mode) "
            + "AND (:status IS NULL OR b.status = :status) "
            + "ORDER BY b.createdAt DESC")
    List<BroadcastJpaEntity> findByMerchantId(@Param("merchantId") UUID merchantId,
                                              @Param("mode") BroadcastMode mode,
                                              @Param("status") BroadcastStatus status);

    /**
     * Spec 124 (filtro rígido por bot-set): mesma consulta da {@link
     * #findByMerchantId} com {@code store_bot_id IN :botIds} — lista vazia →
     * vazio (header inválido nunca vaza).
     */
    @Query("SELECT b FROM BroadcastJpaEntity b JOIN StoreBotJpaEntity sb ON sb.id = b.storeBotId "
            + "WHERE sb.merchantId = :merchantId AND b.storeBotId IN :botIds "
            + "AND (:mode IS NULL OR b.mode = :mode) "
            + "AND (:status IS NULL OR b.status = :status) "
            + "ORDER BY b.createdAt DESC")
    List<BroadcastJpaEntity> findByMerchantIdAndBotIds(@Param("merchantId") UUID merchantId,
                                                       @Param("botIds") List<UUID> botIds,
                                                       @Param("mode") BroadcastMode mode,
                                                       @Param("status") BroadcastStatus status);

    /**
     * Pendências do lojista para o bot (pull): {@code PENDING} com
     * {@code scheduled_at <= dueBefore} OU {@code scheduled_at} nulo (NOW).
     * WELCOME nunca é "vencida" (resolvida por evento — filtro
     * {@code mode != WELCOME}).
     */
    @Query("SELECT b FROM BroadcastJpaEntity b JOIN StoreBotJpaEntity sb ON sb.id = b.storeBotId "
            + "WHERE sb.merchantId = :merchantId "
            + "AND b.status = com.foxcash.domain.model.broadcast.BroadcastStatus.PENDING "
            + "AND b.mode <> com.foxcash.domain.model.broadcast.BroadcastMode.WELCOME "
            + "AND (b.scheduledAt IS NULL OR b.scheduledAt <= :dueBefore) "
            + "ORDER BY b.createdAt ASC")
    List<BroadcastJpaEntity> findDue(@Param("merchantId") UUID merchantId,
                                     @Param("dueBefore") Instant dueBefore);

    /**
     * WELCOME ativo de um bot para um chat: o broadcast WELCOME mais recente com
     * status PENDING que se aplica ao chat — sem destino fixo
     * ({@code target_chat_id} NULL — o editor cria o modo Bem-vindos sem destino,
     * Spec 80) OU com {@code target_chat_id} = chat. Sem a cláusula NULL a query
     * nunca casa com o broadcast criado pelo portal (NULL = :chatId é sempre
     * falso em SQL) e a boas-vindas nunca dispara.
     */
    @Query("SELECT b FROM BroadcastJpaEntity b WHERE b.storeBotId = :storeBotId "
            + "AND b.mode = com.foxcash.domain.model.broadcast.BroadcastMode.WELCOME "
            + "AND b.status = com.foxcash.domain.model.broadcast.BroadcastStatus.PENDING "
            + "AND (b.targetChatId IS NULL OR b.targetChatId = :chatId) "
            + "ORDER BY b.createdAt DESC")
    List<BroadcastJpaEntity> findWelcomeForChat(@Param("storeBotId") UUID storeBotId,
                                                @Param("chatId") String chatId);

    /** REPOST em {@code COMPLETED} — candidatos a re-agendamento pelo job (D10). */
    @Query("SELECT b FROM BroadcastJpaEntity b WHERE b.mode = com.foxcash.domain.model.broadcast.BroadcastMode.REPOST "
            + "AND b.status = com.foxcash.domain.model.broadcast.BroadcastStatus.COMPLETED "
            + "ORDER BY b.updatedAt ASC")
    List<BroadcastJpaEntity> findCompletedReposts();
}
