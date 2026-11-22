package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.master.MasterTotp;
import com.foxcash.domain.port.out.MasterTotpRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MasterTotpMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MasterTotpJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MasterTotpRepository} (Spec 44).
 */
@Repository
public class MasterTotpPersistenceAdapter implements MasterTotpRepository {

    private final MasterTotpJpaRepository jpaRepository;

    public MasterTotpPersistenceAdapter(MasterTotpJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MasterTotp save(MasterTotp totp) {
        return MasterTotpMapper.toDomain(jpaRepository.save(MasterTotpMapper.toEntity(totp)));
    }

    @Override
    public Optional<MasterTotp> findByMethodId(UUID methodId) {
        return jpaRepository.findById(methodId).map(MasterTotpMapper::toDomain);
    }

    @Override
    public Optional<MasterTotp> findByIdForUpdate(UUID methodId) {
        return jpaRepository.findByIdForUpdate(methodId).map(MasterTotpMapper::toDomain);
    }
}
