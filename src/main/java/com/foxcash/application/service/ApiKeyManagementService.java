package com.foxcash.application.service;

import com.foxcash.domain.exception.ApiKeyNotFoundException;
import com.foxcash.domain.exception.TooManyApiKeysException;
import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.MerchantApiKey;
import com.foxcash.domain.port.in.ApiKeyManagementUseCase;
import com.foxcash.domain.port.out.ApiKeyGenerator;
import com.foxcash.domain.port.out.MerchantApiKeyRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Aplicacao: gestao de chaves de API do lojista pelo portal (Spec 24).
 *
 * <p>Cria chaves com escopo (vazio = total), respeita o limite de
 * {@value MerchantApiKey#MAX_ACTIVE_KEYS} ativas e revoga. O segredo em claro
 * e devolvido UMA unica vez no {@code create} — apenas o hash SHA-256 e o
 * prefixo sao persistidos (padrao do projeto, Spec 12).
 */
@Service
public class ApiKeyManagementService implements ApiKeyManagementUseCase {

    private final MerchantApiKeyRepository apiKeyRepository;
    private final ApiKeyGenerator apiKeyGenerator;
    private final MerchantRepository merchantRepository;
    private final Clock clock;

    public ApiKeyManagementService(MerchantApiKeyRepository apiKeyRepository,
                                   ApiKeyGenerator apiKeyGenerator,
                                   MerchantRepository merchantRepository,
                                   Clock clock) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyGenerator = apiKeyGenerator;
        this.merchantRepository = merchantRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MerchantApiKey> list(UUID merchantId) {
        return apiKeyRepository.findByMerchantId(merchantId);
    }

    @Override
    @Transactional
    public CreateResult create(UUID merchantId, CreateCommand command) {
        String label = validateLabel(command.label());
        // Escopos vazios = escopo total (chave do cadastro, comportamento padrão).
        Set<ApiKeyScope> scopes = command.scopes() == null || command.scopes().isEmpty()
                ? Set.of(ApiKeyScope.values())
                : Set.copyOf(command.scopes());

        if (apiKeyRepository.countActiveByMerchantId(merchantId) >= MerchantApiKey.MAX_ACTIVE_KEYS) {
            throw new TooManyApiKeysException(MerchantApiKey.MAX_ACTIVE_KEYS);
        }

        ApiKeyGenerator.ApiKeyPair key = apiKeyGenerator.generate();
        Instant now = clock.instant();
        MerchantApiKey saved = apiKeyRepository.save(MerchantApiKey.create(
                merchantId, label, key.plaintext(), key.hash(), scopes, now));
        return new CreateResult(saved.id(), key.plaintext());
    }

    @Override
    @Transactional
    public void revoke(UUID merchantId, UUID keyId) {
        MerchantApiKey key = apiKeyRepository.findByIdAndMerchantId(keyId, merchantId)
                .orElseThrow(() -> new ApiKeyNotFoundException(keyId));
        key.revoke();
        apiKeyRepository.save(key);
        // SEC-24 (revisão de segurança 2026-08-11): a chave do cadastro (Spec 12)
        // também vive em merchants.api_key_hash — o fallback legado do
        // ApiKeyAuthFilter autentica com ela (escopo total implícito) sempre que
        // a linha gerenciável não existir. Sem limpar, a revogação seria
        // reversível: uma remoção futura da linha (delete/limpeza) "ressuscitaria"
        // a chave revogada com escopo total. UPDATE condicional: limpa apenas
        // quando o hash legado é o MESMO da chave sendo revogada.
        merchantRepository.clearLegacyApiKeyHashIfMatches(merchantId, key.keyHash());
    }

    private String validateLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label é obrigatório");
        }
        if (label.length() > 80) {
            throw new IllegalArgumentException("label deve ter no máximo 80 caracteres");
        }
        return label.trim();
    }
}
