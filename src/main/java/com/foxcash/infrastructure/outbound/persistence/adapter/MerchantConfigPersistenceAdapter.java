package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.port.out.MerchantConfigProvider;
import com.foxcash.domain.port.out.MerchantRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador do port {@link MerchantConfigProvider} (Spec 98): leitura/escrita
 * da config fina nas colunas de {@code merchants} (V61). Delega ao
 * {@link MerchantRepository} — o save reutiliza o {@code MerchantMapper} (padrão
 * M8/Spec 30) e recalcula o {@code document_hash} com HMAC-pepper (auditoria
 * 2026-08-05/B-A1): passar {@code null} no hash apagaria a coluna, por isso não
 * há toEntity direto aqui.
 */
@Repository
public class MerchantConfigPersistenceAdapter implements MerchantConfigProvider {

    private final MerchantRepository merchantRepository;

    public MerchantConfigPersistenceAdapter(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Override
    public Optional<Merchant> findWithConfig(UUID merchantId) {
        return merchantRepository.findById(merchantId);
    }

    @Override
    public Optional<Merchant> findWithConfigForUpdate(UUID merchantId) {
        return merchantRepository.findByIdForUpdate(merchantId);
    }

    @Override
    public Optional<MerchantConfig> config(UUID merchantId) {
        return merchantRepository.findById(merchantId).map(Merchant::config);
    }

    @Override
    public Optional<RateLimitOverride> rateLimit(UUID merchantId) {
        return merchantRepository.findById(merchantId).map(m -> new RateLimitOverride(
                m.config().rateLimitRps(), m.config().rateLimitRpm()));
    }

    @Override
    public Optional<MerchantConfig> pixFee(UUID merchantId) {
        return merchantRepository.findById(merchantId).map(Merchant::config);
    }

    @Override
    @Transactional
    public Merchant save(Merchant merchant) {
        return merchantRepository.save(merchant);
    }
}
