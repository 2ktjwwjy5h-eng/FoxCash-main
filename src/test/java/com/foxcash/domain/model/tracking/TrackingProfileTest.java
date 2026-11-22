package com.foxcash.domain.model.tracking;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Testes do aggregate {@link TrackingProfile} (Spec 81/D3). */
class TrackingProfileTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID MERCHANT = UUID.randomUUID();

    @Test
    void createValidatesPairPixelToken() {
        assertThatThrownBy(() -> TrackingProfile.create(MERCHANT, "Pixel FB", "PIXEL_ID",
                null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("facebook");
        assertThatThrownBy(() -> TrackingProfile.create(MERCHANT, "TikTok", null, null,
                null, "TOKEN", null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tiktok");
    }

    @Test
    void createValidatesName() {
        assertThatThrownBy(() -> TrackingProfile.create(MERCHANT, "  ", null, null,
                null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void createAndActivateTransitions() {
        TrackingProfile profile = TrackingProfile.create(MERCHANT, "Pixel Modelo",
                "PIXEL", "tok", null, null, "utmify-tok", null, NOW);

        assertThat(profile.status()).isEqualTo(TrackingStatus.ACTIVE);
        assertThat(profile.isFacebookConfigured()).isTrue();
        assertThat(profile.isTikTokConfigured()).isFalse();
        assertThat(profile.isUtmifyConfigured()).isTrue();
        assertThat(profile.isOtimizeyConfigured()).isFalse();

        profile.deactivate(NOW);
        assertThat(profile.status()).isEqualTo(TrackingStatus.INACTIVE);
        profile.activate(NOW);
        assertThat(profile.status()).isEqualTo(TrackingStatus.ACTIVE);
    }

    @Test
    void duplicateCreatesNewIdActive() {
        TrackingProfile source = TrackingProfile.create(MERCHANT, "Pixel Modelo",
                "PIXEL", "tok", null, null, null, null, NOW);

        TrackingProfile copy = source.duplicate(MERCHANT, "Pixel Modelo (copia)", NOW);

        assertThat(copy.id()).isNotEqualTo(source.id());
        assertThat(copy.status()).isEqualTo(TrackingStatus.ACTIVE);
        assertThat(copy.facebookPixelId()).isEqualTo(source.facebookPixelId());
        assertThat(copy.facebookAccessToken()).isEqualTo(source.facebookAccessToken());
        assertThat(copy.name()).isEqualTo("Pixel Modelo (copia)");
    }

    @Test
    void tokenMaskNeverExposesFullToken() {
        assertThat(TrackingProfile.tokenMask("abc123456789")).isEqualTo("****6789");
        assertThat(TrackingProfile.tokenMask("abc")).isEqualTo("****");
        assertThat(TrackingProfile.tokenMask(null)).isNull();
    }
}
