package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.ChatMessageJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para mensagens do chat do CRM
 * (Spec 83) — histórico paginado, contagem de não lidas e marcação de lida.
 */
public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageJpaEntity, UUID> {

    Page<ChatMessageJpaEntity> findByLeadIdOrderByCreatedAtAsc(UUID leadId, Pageable pageable);

    /**
     * Mensagens {@code CLIENT} não lidas do lead — o indicador de não lida do
     * painel (Spec 83 C11 / Spec 84 C7) conta APENAS as mensagens do cliente
     * (uma mensagem ADMIN enviada pelo próprio lojista nunca é "não lida").
     */
    @Query("SELECT COUNT(m) FROM ChatMessageJpaEntity m " +
            "WHERE m.leadId = :leadId AND m.senderType = 'CLIENT' AND m.read = false")
    long countUnreadClient(@Param("leadId") UUID leadId);

    @Modifying
    @Query("UPDATE ChatMessageJpaEntity m SET m.read = true " +
            "WHERE m.leadId = :leadId AND m.senderType = 'CLIENT' AND m.read = false")
    void markAllRead(@Param("leadId") UUID leadId);
}
