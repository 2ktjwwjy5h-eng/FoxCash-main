package com.foxcash.domain.port.out;

import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência dos bots de venda (Spec 74).
 *
 * <p>Consultas do portal são sempre por {@code merchantId} do principal (nunca
 * por id solto — IDOR-safe). O lookup do webhook público é por
 * {@code webhookSecretHash} (SHA-256 — nunca pelo ciphertext AES-GCM).
 */
public interface StoreBotRepository {

    StoreBot save(StoreBot bot);

    /** Detalhe por id (master — espelho read-only). */
    Optional<StoreBot> findById(UUID id);

    /** Bot do id + lojista (404 {@code bot_not_found} cross-lojista). */
    Optional<StoreBot> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Todos os bots NÃO removidos do lojista (portal). */
    List<StoreBot> findByMerchantId(UUID merchantId);

    /** Frota do perfil de operação (Spec 124 — bots não-removidos do lojista+perfil). */
    List<StoreBot> findByMerchantIdAndProfileId(UUID merchantId, UUID profileId);

    /** Ids dos bots do perfil para o escopo por bot-set (Spec 124 — subquery com merchant_id). */
    List<UUID> findIdsByMerchantIdAndProfileId(UUID merchantId, UUID profileId);

    /** Lookup do endpoint público de updates por hash do webhook secret. */
    Optional<StoreBot> findByWebhookSecretHash(String webhookSecretHash);

    /** Lookup por hash do token (unicidade — token_hash UNIQUE). */
    Optional<StoreBot> findByTokenHash(String tokenHash);

    /** Todos os bots NÃO removidos (espelho master + job de reconciliação). */
    List<StoreBot> findAllNonDeleted();

    /** Todos os bots NÃO removidos de um status (job de reconciliação). */
    List<StoreBot> findByStatus(BotStatus status);
}
