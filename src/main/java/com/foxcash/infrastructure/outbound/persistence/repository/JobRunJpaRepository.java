package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.JobRunJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Repositorio JPA da trilha {@code job_runs} (Spec 53). O DELETE bulk da
 * limpeza roda via {@code @Modifying} dentro da transacao do adapter.
 */
public interface JobRunJpaRepository extends JpaRepository<JobRunJpaEntity, Long> {

    @Modifying
    @Query("DELETE FROM JobRunJpaEntity j WHERE j.startedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
