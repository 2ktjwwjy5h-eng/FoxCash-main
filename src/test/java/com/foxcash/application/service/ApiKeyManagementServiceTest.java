package com.foxcash.application.service;

import com.foxcash.domain.exception.ApiKeyNotFoundException;
import com.foxcash.domain.exception.TooManyApiKeysException;
import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.ApiKeyStatus;
import com.foxcash.domain.model.apikey.MerchantApiKey;
import com.foxcash.domain.port.in.ApiKeyManagementUseCase;
import com.foxcash.domain.port.out.ApiKeyGenerator;
import com.foxcash.domain.port.out.MerchantApiKeyRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da gestão de chaves de API (Spec 24): criação com limite e escopos,
 * listagem sem segredo, revogação e autorização por posse (404).
 */
class ApiKeyManagementServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final MerchantApiKeyRepository apiKeyRepository = mock(MerchantApiKeyRepository.class);
    private final ApiKeyGenerator apiKeyGenerator = mock(ApiKeyGenerator.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);

    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyManagementService(apiKeyRepository, apiKeyGenerator,
                merchantRepository, CLOCK);
    }

    private ApiKeyGenerator.ApiKeyPair keyPair() {
        return new ApiKeyGenerator.ApiKeyPair(
                "mch_abcdef1234567890abcdef1234567890",
                "hash1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
    }

    private MerchantApiKey key(ApiKeyStatus status, Set<ApiKeyScope> scopes) {
        return MerchantApiKey.restore(KEY_ID, MERCHANT_ID, "Bot", "mch_abcdef",
                "hash", status, scopes, null, NOW);
    }

    @Test
    void listReturnsMerchantKeys() {
        when(apiKeyRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(key(ApiKeyStatus.ACTIVE, Set.of(ApiKeyScope.CHARGES))));

        List<MerchantApiKey> keys = service.list(MERCHANT_ID);

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).prefix()).isEqualTo("mch_abcdef");
        assertThat(keys.get(0).keyHash()).isEqualTo("hash"); // só o hash interno
    }

    @Test
    void createWithScopesReturnsSecretOnce() {
        when(apiKeyRepository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(0L);
        when(apiKeyGenerator.generate()).thenReturn(keyPair());
        when(apiKeyRepository.save(any(MerchantApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyManagementUseCase.CreateResult result = service.create(MERCHANT_ID,
                new ApiKeyManagementUseCase.CreateCommand("Bot de produção",
                        Set.of(ApiKeyScope.CHARGES, ApiKeyScope.WITHDRAWALS)));

        assertThat(result.secret()).isEqualTo("mch_abcdef1234567890abcdef1234567890");
        // Só o hash é persistido — nunca o segredo.
        verify(apiKeyRepository).save(any(MerchantApiKey.class));
    }

    @Test
    void createWithEmptyScopesDefaultsToTotal() {
        when(apiKeyRepository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(0L);
        when(apiKeyGenerator.generate()).thenReturn(keyPair());
        when(apiKeyRepository.save(any(MerchantApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(MERCHANT_ID, new ApiKeyManagementUseCase.CreateCommand("Total", Set.of()));

        // Mutation (NegateConditionals L53, isEmpty negada): escopo vazio DEVE
        // virar escopo total — o mutante salvaria a chave com escopo vazio.
        ArgumentCaptor<MerchantApiKey> captor = ArgumentCaptor.forClass(MerchantApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().scopes())
                .containsExactlyInAnyOrder(ApiKeyScope.values());
    }

    @Test
    void createWithNullScopesDefaultsToTotal() {
        // Mutation (NegateConditionals L53, null check negada): scopes == null
        // DEVE virar escopo total — o mutante tentaria scopes().isEmpty() e
        // estouraria NPE antes do save.
        when(apiKeyRepository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(0L);
        when(apiKeyGenerator.generate()).thenReturn(keyPair());
        when(apiKeyRepository.save(any(MerchantApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(MERCHANT_ID, new ApiKeyManagementUseCase.CreateCommand("Total", null));

        ArgumentCaptor<MerchantApiKey> captor = ArgumentCaptor.forClass(MerchantApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().scopes())
                .containsExactlyInAnyOrder(ApiKeyScope.values());
    }

    @Test
    void createAcceptsLabelAtExactMaxLength() {
        // Mutation (ConditionalsBoundary L81: length() > 80 → >= 80): label com
        // EXATAMENTE 80 caracteres é aceita.
        when(apiKeyRepository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(0L);
        when(apiKeyGenerator.generate()).thenReturn(keyPair());
        when(apiKeyRepository.save(any(MerchantApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyManagementUseCase.CreateResult result = service.create(MERCHANT_ID,
                new ApiKeyManagementUseCase.CreateCommand("a".repeat(80),
                        Set.of(ApiKeyScope.CHARGES)));

        assertThat(result.secret()).isEqualTo("mch_abcdef1234567890abcdef1234567890");
        verify(apiKeyRepository).save(any(MerchantApiKey.class));
    }

    @Test
    void createTrimsLabelWhitespace() {
        // Mutation (EmptyObjectReturnVals L84: label.trim() → ""): o label
        // persistido deve ser o valor SEM espaços nas bordas.
        when(apiKeyRepository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(0L);
        when(apiKeyGenerator.generate()).thenReturn(keyPair());
        when(apiKeyRepository.save(any(MerchantApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(MERCHANT_ID, new ApiKeyManagementUseCase.CreateCommand("  Bot de producao  ",
                Set.of(ApiKeyScope.CHARGES)));

        ArgumentCaptor<MerchantApiKey> captor = ArgumentCaptor.forClass(MerchantApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().label()).isEqualTo("Bot de producao");
    }

    @Test
    void createRejectsBlankLabel() {
        assertThatThrownBy(() -> service.create(MERCHANT_ID,
                new ApiKeyManagementUseCase.CreateCommand("  ", Set.of(ApiKeyScope.CHARGES))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void createRejectsOverLimit() {
        when(apiKeyRepository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(
                (long) MerchantApiKey.MAX_ACTIVE_KEYS);

        assertThatThrownBy(() -> service.create(MERCHANT_ID,
                new ApiKeyManagementUseCase.CreateCommand("Bot", Set.of(ApiKeyScope.CHARGES))))
                .isInstanceOf(TooManyApiKeysException.class);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void revokeMarksKeyRevoked() {
        MerchantApiKey key = key(ApiKeyStatus.ACTIVE, Set.of(ApiKeyScope.CHARGES));
        when(apiKeyRepository.findByIdAndMerchantId(KEY_ID, MERCHANT_ID)).thenReturn(Optional.of(key));

        service.revoke(MERCHANT_ID, KEY_ID);

        assertThat(key.status()).isEqualTo(ApiKeyStatus.REVOKED);
        verify(apiKeyRepository).save(key);
    }

    // ---------- SEC-24 (revisão de segurança 2026-08-11): revogação da credencial legada ----------

    @Test
    void revokeClearsLegacyApiKeyHashWhenItMatches() {
        // SEC-24: a chave do cadastro (Spec 12) também vive em
        // merchants.api_key_hash — o fallback legado do ApiKeyAuthFilter (escopo
        // total implícito) autentica com ela. A revogação precisa limpar o hash
        // legado; sem isso a credencial "revogada" sobrevive (ressuscita se a
        // linha gerenciável for removida — delete futuro/limpeza/edição manual).
        MerchantApiKey key = key(ApiKeyStatus.ACTIVE, Set.of(ApiKeyScope.CHARGES));
        when(apiKeyRepository.findByIdAndMerchantId(KEY_ID, MERCHANT_ID)).thenReturn(Optional.of(key));

        service.revoke(MERCHANT_ID, KEY_ID);

        // UPDATE condicional com o hash da chave revogada (no-op quando o hash
        // legado é de OUTRA chave — a coluna guarda apenas o hash do cadastro).
        verify(merchantRepository).clearLegacyApiKeyHashIfMatches(MERCHANT_ID, key.keyHash());
    }

    @Test
    void revokeOfUnknownKeyDoesNotTouchLegacyHash() {
        when(apiKeyRepository.findByIdAndMerchantId(KEY_ID, MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(MERCHANT_ID, KEY_ID))
                .isInstanceOf(ApiKeyNotFoundException.class);

        verify(merchantRepository, never()).clearLegacyApiKeyHashIfMatches(any(), anyString());
    }

    @Test
    void isActiveReflectsStatus() {
        // Mutation (BooleanTrueReturnVals L92): isActive reflete o status — uma
        // chave REVOKED NÃO é ativa (o mutante sempre retornaria true).
        assertThat(key(ApiKeyStatus.ACTIVE, Set.of(ApiKeyScope.CHARGES)).isActive()).isTrue();
        assertThat(key(ApiKeyStatus.REVOKED, Set.of(ApiKeyScope.CHARGES)).isActive()).isFalse();

        // Round 11 (getters/escopos de MerchantApiKey): NullReturnVals
        // L106/L110/L138 (id/merchantId/createdAt), BooleanTrue/FalseReturnVals
        // L97/L102 (hasScope/hasTotalScope) e NullReturnVals L134 (lastUsedAt
        // após recordUse).
        MerchantApiKey key = key(ApiKeyStatus.ACTIVE, Set.of(ApiKeyScope.CHARGES));
        assertThat(key.id()).isEqualTo(KEY_ID);
        assertThat(key.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(key.createdAt()).isEqualTo(NOW);
        assertThat(key.hasScope(ApiKeyScope.CHARGES)).isTrue();
        assertThat(key.hasScope(ApiKeyScope.WITHDRAWALS)).isFalse();
        assertThat(key.hasTotalScope()).isFalse();
        assertThat(key(ApiKeyStatus.ACTIVE, Set.of(ApiKeyScope.values())).hasTotalScope()).isTrue();
        key.recordUse(NOW);
        assertThat(key.lastUsedAt()).isEqualTo(NOW);
    }

    @Test
    void revokeOfAnotherMerchantKeyThrowsNotFound() {
        when(apiKeyRepository.findByIdAndMerchantId(KEY_ID, MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(MERCHANT_ID, KEY_ID))
                .isInstanceOf(ApiKeyNotFoundException.class);
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void revokeDoesNotRemoveTheKey() {
        MerchantApiKey key = key(ApiKeyStatus.ACTIVE, Set.of(ApiKeyScope.CHARGES));
        when(apiKeyRepository.findByIdAndMerchantId(KEY_ID, MERCHANT_ID)).thenReturn(Optional.of(key));

        service.revoke(MERCHANT_ID, KEY_ID);

        // Revogar ≠ apagar: a chave permanece no histórico do lojista.
        verify(apiKeyRepository).save(key);
        assertThat(key.status()).isEqualTo(ApiKeyStatus.REVOKED);
    }

    @Test
    void createUsesGeneratedHash() {
        when(apiKeyRepository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(0L);
        when(apiKeyGenerator.generate()).thenReturn(keyPair());
        when(apiKeyRepository.save(any(MerchantApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(MERCHANT_ID, new ApiKeyManagementUseCase.CreateCommand("Bot",
                Set.of(ApiKeyScope.TRANSACTIONS)));

        verify(apiKeyRepository).save(any(MerchantApiKey.class));
    }
}
