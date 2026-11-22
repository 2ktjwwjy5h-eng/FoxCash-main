package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.CommunityPostJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) dos posts da Comunidade (Spec 119).
 */
public interface CommunityPostJpaRepository extends JpaRepository<CommunityPostJpaEntity, UUID> {

    /** Pinados primeiro, depois por published_at desc (portal — Spec 119 D7.3). */
    List<CommunityPostJpaEntity> findAllByOrderByPinnedDescPublishedAtDesc(
            org.springframework.data.domain.Pageable pageable);

    boolean existsByTitle(String title);
}
