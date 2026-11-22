package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.master.MasterMfaMethod;
import com.foxcash.domain.port.out.MasterMfaMethodRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MasterMfaMethodMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MasterMfaMethodJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MasterMfaMethodRepository} (Spec 44).
 */
@Repository
public class MasterMfaMethodPersistenceAdapter implements MasterMfaMethodRepository {

    private final MasterMfaMethodJpaRepository jpaRepository;

    public MasterMfaMethodPersistenceAdapter(MasterMfaMethodJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MasterMfaMethod save(MasterMfaMethod method) {
        return MasterMfaMethodMapper.toDomain(
                jpaRepository.save(MasterMfaMethodMapper.toEntity(method)));
    }

    @Override
    public Optional<MasterMfaMethod> findActiveByMasterId(UUID masterId) {
        return jpaRepository.findFirstByMasterIdAndStatus(masterId, "ACTIVE")
                .map(MasterMfaMethodMapper::toDomain);
    }

    @Override
    public Optional<MasterMfaMethod> findPendingByMasterId(UUID masterId) {
        return jpaRepository.findFirstByMasterIdAndStatus(masterId, "PENDING")
                .map(MasterMfaMethodMapper::toDomain);
    }

    @Override
    public Optional<MasterMfaMethod> findByIdAndMasterId(UUID id, UUID masterId) {
        return jpaRepository.findById(id)
                .filter(e -> e.getMasterId().equals(masterId))
                .map(MasterMfaMethodMapper::toDomain);
    }

    @Override
    public void deleteByMasterId(UUID masterId) {
        jpaRepository.findFirstByMasterIdAndStatus(masterId, "ACTIVE")
                .ifPresent(jpaRepository::delete);
        jpaRepository.findFirstByMasterIdAndStatus(masterId, "PENDING")
                .ifPresent(jpaRepository::delete);
    }
}
