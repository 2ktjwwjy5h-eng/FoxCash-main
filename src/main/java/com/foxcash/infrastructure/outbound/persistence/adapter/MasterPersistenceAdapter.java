package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.MasterMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MasterJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MasterRepository} (Spec 13; gestão Spec 41).
 */
@Repository
public class MasterPersistenceAdapter implements MasterRepository {

    private final MasterJpaRepository jpaRepository;

    public MasterPersistenceAdapter(MasterJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Master save(Master master) {
        return MasterMapper.toDomain(jpaRepository.save(MasterMapper.toEntity(master)));
    }

    @Override
    public Optional<Master> findById(UUID masterId) {
        return jpaRepository.findById(masterId).map(MasterMapper::toDomain);
    }

    @Override
    public Optional<Master> findByIdForUpdate(UUID masterId) {
        return jpaRepository.findByIdForUpdate(masterId).map(MasterMapper::toDomain);
    }

    @Override
    public Optional<Master> findByUsername(String username) {
        return jpaRepository.findByUsername(username).map(MasterMapper::toDomain);
    }

    @Override
    public List<Master> findAll() {
        return jpaRepository.findAll().stream().map(MasterMapper::toDomain).toList();
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }
}
