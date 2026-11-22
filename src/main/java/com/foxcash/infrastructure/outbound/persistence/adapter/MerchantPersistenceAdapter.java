package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantJpaRepository;
import com.foxcash.infrastructure.security.CryptoProperties;
import com.foxcash.infrastructure.security.DocumentHasher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port MerchantRepository.
 */
@Repository
public class MerchantPersistenceAdapter implements MerchantRepository {

    private final MerchantJpaRepository jpaRepository;
    private final CryptoProperties cryptoProperties;

    public MerchantPersistenceAdapter(MerchantJpaRepository jpaRepository,
                                      CryptoProperties cryptoProperties) {
        this.jpaRepository = jpaRepository;
        this.cryptoProperties = cryptoProperties;
    }

    @Override
    public Merchant save(Merchant merchant) {
        // document_hash com HMAC-pepper (auditoria 2026-08-05/B-A1): o documento
        // (CPF/CNPJ) e identificador de baixa entropia — SHA-256 puro seria
        // brute-forcavel offline. O hash e write-only (nenhum lookup o usa).
        String documentHash = merchant.document() == null ? null
                : DocumentHasher.hmac(merchant.document(), cryptoProperties.getSecret());
        return MerchantMapper.toDomain(
                jpaRepository.save(MerchantMapper.toEntity(merchant, documentHash)));
    }

    @Override
    public Optional<Merchant> findById(UUID merchantId) {
        return jpaRepository.findById(merchantId).map(MerchantMapper::toDomain);
    }

    @Override
    public Optional<Merchant> findByIdForUpdate(UUID merchantId) {
        return jpaRepository.findByIdForUpdate(merchantId).map(MerchantMapper::toDomain);
    }

    @Override
    public Optional<Merchant> findByEmail(String email) {
        // E-mail normalizado em minúsculas no cadastro/login; o índice é sobre lower(email).
        return jpaRepository.findByEmail(email.trim().toLowerCase())
                .map(MerchantMapper::toDomain);
    }

    @Override
    public Optional<Merchant> findByApiKeyHash(String apiKeyHash) {
        return jpaRepository.findByApiKeyHash(apiKeyHash)
                .map(MerchantMapper::toDomain);
    }

    @Override
    public void clearLegacyApiKeyHashIfMatches(UUID merchantId, String keyHash) {
        // SEC-24 (revisão de segurança 2026-08-11): UPDATE condicional — limpa o
        // hash da chave legada (Spec 12, merchants.api_key_hash) SOMENTE quando
        // casa com a chave revogada (a coluna guarda apenas o hash do cadastro).
        jpaRepository.clearLegacyApiKeyHashIfMatches(merchantId, keyHash);
    }

    @Override
    public List<Merchant> findAllActive() {
        return jpaRepository.findByStatus(MerchantStatus.ACTIVE).stream()
                .map(MerchantMapper::toDomain)
                .toList();
    }

    @Override
    public List<Merchant> findAll() {
        return jpaRepository.findAll().stream()
                .map(MerchantMapper::toDomain)
                .toList();
    }

    @Override
    public PageResult<Merchant> findAll(int page, int size) {
        org.springframework.data.domain.Page<MerchantJpaEntity> result =
                jpaRepository.findAll(PageRequest.of(page, size));
        return PageResult.of(result.getContent().stream()
                        .map(MerchantMapper::toDomain)
                        .toList(),
                page, size, result.getTotalElements());
    }
}
