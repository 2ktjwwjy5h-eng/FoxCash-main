package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.profile.ProfileScope;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantProfileJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.StoreBotJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantProfileJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.StoreBotJpaRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador JPA dos bots de venda (Spec 74/T0.3): delegação dos
 * lookups (por merchant, por hash do webhook secret, por hash do token) e
 * mapeamento domínio ↔ entidade (o token viaja em claro em memória; o
 * {@code webhook_secret} em claro nunca é persistido — só o hash).
 */
class StoreBotPersistenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID DEFAULT_PROFILE_ID = UUID.randomUUID();

    private final StoreBotJpaRepository repository = mock(StoreBotJpaRepository.class);
    private final MerchantProfileJpaRepository profileRepository = mock(MerchantProfileJpaRepository.class);
    private final StoreBotPersistenceAdapter adapter =
            new StoreBotPersistenceAdapter(repository, profileRepository);

    private StoreBotJpaEntity entity() {
        return new StoreBotJpaEntity(ID, MERCHANT_ID, BotPlatform.TELEGRAM, "token-em-claro",
                "token-hash", "wh-hash", "wh-token-hash", "Bot Vendas", "vendasbot", null,
                BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
    }

    @Test
    void saveMapsDomainToEntityAndBack() {
        StoreBot bot = StoreBot.restore(ID, MERCHANT_ID, BotPlatform.TELEGRAM, "token",
                "token-hash", "wh-hash", "wh-token-hash", "Bot Vendas", "vendasbot", null,
                BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
        when(repository.save(any())).thenReturn(entity());

        StoreBot saved = adapter.save(bot);

        assertThat(saved.id()).isEqualTo(ID);
        assertThat(saved.token()).isEqualTo("token-em-claro");
        assertThat(saved.webhookSecret()).isNull(); // só o hash é persistido
        assertThat(saved.webhookSecretHash()).isEqualTo("wh-hash");
        assertThat(saved.webhookSecretToken()).isNull(); // só o hash é persistido
        assertThat(saved.webhookSecretTokenHash()).isEqualTo("wh-token-hash");
    }

    @Test
    void findByIdAndMerchantIdDelegates() {
        when(repository.findByIdAndMerchantId(ID, MERCHANT_ID)).thenReturn(Optional.of(entity()));

        Optional<StoreBot> found = adapter.findByIdAndMerchantId(ID, MERCHANT_ID);

        assertThat(found).isPresent();
        assertThat(found.get().merchantId()).isEqualTo(MERCHANT_ID);
    }

    @Test
    void findByWebhookSecretHashDelegates() {
        when(repository.findByWebhookSecretHash("wh-hash")).thenReturn(Optional.of(entity()));

        Optional<StoreBot> found = adapter.findByWebhookSecretHash("wh-hash");

        assertThat(found).isPresent();
        assertThat(found.get().webhookSecretHash()).isEqualTo("wh-hash");
    }

    @Test
    void findByMerchantIdDelegatesAndMaps() {
        when(repository.findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(MERCHANT_ID))
                .thenReturn(List.of(entity()));

        assertThat(adapter.findByMerchantId(MERCHANT_ID)).hasSize(1);
        verify(repository).findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(MERCHANT_ID);
    }

    @Test
    void findAllNonDeletedDelegates() {
        when(repository.findByDeletedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(entity()));

        assertThat(adapter.findAllNonDeleted()).hasSize(1);
        verify(repository).findByDeletedAtIsNullOrderByCreatedAtAsc();
    }

    // ---- Spec 122/124 — frota/bot-set por perfil inclui bots SEM perfil quando
    // o perfil é o DEFAULT do lojista (achado de auditoria 2026-08-15) ----

    @Test
    void findByMerchantIdAndDefaultProfileIncludesUnassignedBots() {
        // Perfil DEFAULT → includeUnassigned=true → bots sem perfil (NULL) entram.
        when(profileRepository.findByMerchantIdAndIsDefaultTrueAndDeletedAtIsNull(MERCHANT_ID))
                .thenReturn(Optional.of(defaultProfileEntity()));
        when(repository.findFleetByMerchantIdAndProfile(MERCHANT_ID, DEFAULT_PROFILE_ID, true))
                .thenReturn(List.of(entity()));

        assertThat(adapter.findByMerchantIdAndProfileId(MERCHANT_ID, DEFAULT_PROFILE_ID)).hasSize(1);
        verify(repository).findFleetByMerchantIdAndProfile(MERCHANT_ID, DEFAULT_PROFILE_ID, true);
    }

    @Test
    void findByMerchantIdAndNonDefaultProfileExcludesUnassignedBots() {
        // Perfil NÃO-default → includeUnassigned=false → só bots com profile_id setado.
        UUID otherProfile = UUID.randomUUID();
        when(profileRepository.findByMerchantIdAndIsDefaultTrueAndDeletedAtIsNull(MERCHANT_ID))
                .thenReturn(Optional.of(defaultProfileEntity()));
        when(repository.findFleetByMerchantIdAndProfile(MERCHANT_ID, otherProfile, false))
                .thenReturn(List.of(entity()));

        assertThat(adapter.findByMerchantIdAndProfileId(MERCHANT_ID, otherProfile)).hasSize(1);
        verify(repository).findFleetByMerchantIdAndProfile(MERCHANT_ID, otherProfile, false);
    }

    @Test
    void findIdsByMerchantIdAndDefaultProfileIncludesUnassignedBots() {
        when(profileRepository.findByMerchantIdAndIsDefaultTrueAndDeletedAtIsNull(MERCHANT_ID))
                .thenReturn(Optional.of(defaultProfileEntity()));
        when(repository.findIdsByMerchantIdAndProfile(MERCHANT_ID, DEFAULT_PROFILE_ID, true))
                .thenReturn(List.of(ID));

        assertThat(adapter.findIdsByMerchantIdAndProfileId(MERCHANT_ID, DEFAULT_PROFILE_ID))
                .containsExactly(ID);
        verify(repository).findIdsByMerchantIdAndProfile(MERCHANT_ID, DEFAULT_PROFILE_ID, true);
    }

    private MerchantProfileJpaEntity defaultProfileEntity() {
        return new MerchantProfileJpaEntity(DEFAULT_PROFILE_ID, MERCHANT_ID, "Padrão BR", null,
                ProfileScope.GENERAL, true, 0, null, NOW, NOW);
    }
}
