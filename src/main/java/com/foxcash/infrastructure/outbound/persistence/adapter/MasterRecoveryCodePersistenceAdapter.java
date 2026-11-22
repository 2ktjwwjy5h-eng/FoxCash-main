package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.master.MasterRecoveryCode;
import com.foxcash.domain.port.out.MasterRecoveryCodeRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MasterRecoveryCodeMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MasterRecoveryCodeJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MasterRecoveryCodeRepository} (Spec 44).
 */
@Repository
public class MasterRecoveryCodePersistenceAdapter implements MasterRecoveryCodeRepository {

    private final MasterRecoveryCodeJpaRepository jpaRepository;

    public MasterRecoveryCodePersistenceAdapter(MasterRecoveryCodeJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<MasterRecoveryCode> saveAll(List<MasterRecoveryCode> codes) {
        return jpaRepository.saveAll(codes.stream().map(MasterRecoveryCodeMapper::toEntity).toList())
                .stream().map(MasterRecoveryCodeMapper::toDomain).toList();
    }

    @Override
    public List<MasterRecoveryCode> findByMethodId(UUID methodId) {
        return jpaRepository.findByMethodId(methodId).stream()
                .map(MasterRecoveryCodeMapper::toDomain).toList();
    }

    @Override
    public boolean consumeIfOpen(UUID methodId, String codeHash, Instant now) {
        return jpaRepository.consumeIfOpen(methodId, codeHash, now) > 0;
    }

    @Override
    public void deleteByMethodId(UUID methodId) {
        jpaRepository.deleteByMethodId(methodId);
    }
}
