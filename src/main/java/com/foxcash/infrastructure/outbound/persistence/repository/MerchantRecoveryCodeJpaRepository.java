package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MerchantRecoveryCodeJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para códigos de recuperação do MFA
 * do lojista no portal (Spec 94). Espelho do
 * {@code MasterRecoveryCodeJpaRepository} (Spec 44).
 */
public interface MerchantRecoveryCodeJpaRepository extends JpaRepository<MerchantRecoveryCodeJpaEntity, UUID> {

    List<MerchantRecoveryCodeJpaEntity> findByMethodId(UUID methodId);

    /**
     * SEC-20: consume ATÔMICO (UPDATE condicional) — devolve o nº de linhas
     * afetadas (1 = consumiu; 0 = já usado/inexistente). Mesmo padrão do
     * {@code MasterRecoveryCodeJpaRepository.consumeIfOpen} (SEC-20) e do
     * {@code PasswordResetTokenJpaRepository.consumeIfOpen} (SEC-10).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MerchantRecoveryCodeJpaEntity c set c.usedAt = :now "
            + "where c.methodId = :methodId and c.codeHash = :codeHash and c.usedAt is null")
    int consumeIfOpen(@Param("methodId") UUID methodId, @Param("codeHash") String codeHash,
                      @Param("now") Instant now);

    void deleteByMethodId(UUID methodId);
}
