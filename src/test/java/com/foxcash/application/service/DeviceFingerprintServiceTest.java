package com.foxcash.application.service;

import com.foxcash.domain.model.fingerprint.DeviceFingerprint;
import com.foxcash.domain.port.out.DeviceFingerprintRepository;
import com.foxcash.infrastructure.config.FingerprintProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do serviço de device fingerprint do portal (Spec 141) — sinal de
 * risco PASSIVO: hash válido é gravado; ausente/malformado/desligado → vazio,
 * nunca quebra o request do cadastro/login.
 */
class DeviceFingerprintServiceTest {

    private static final String HASH_VALID = "a".repeat(64);
    private static final String HASH_UPPER = "B".repeat(64);
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    private final DeviceFingerprintRepository repository = mock(DeviceFingerprintRepository.class);
    private final FingerprintAlertPublisher alertPublisher = mock(FingerprintAlertPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DeviceFingerprintService service;

    @BeforeEach
    void setUp() {
        service = new DeviceFingerprintService(repository, new FingerprintProperties(), alertPublisher, clock);
    }

    @Test
    void recordWithValidHashSavesAndReturnsSnapshot() {
        DeviceFingerprint snapshot = new DeviceFingerprint(HASH_VALID, NOW, NOW, 1, 1);
        when(repository.record(HASH_VALID, MERCHANT_ID, NOW)).thenReturn(snapshot);

        Optional<DeviceFingerprint> result = service.record(HASH_VALID, MERCHANT_ID);

        assertTrue(result.isPresent());
        assertEquals(HASH_VALID, result.get().fingerprintHash());
        verify(repository).record(HASH_VALID, MERCHANT_ID, NOW);
    }

    @Test
    void recordNormalizesHashToLowerCase() {
        when(repository.record(anyString(), any(), any())).thenAnswer(inv -> {
            Object[] args = inv.getArguments();
            return new DeviceFingerprint((String) args[0], NOW, NOW, 1, 1);
        });

        // Hash maiúsculo é normalizado para hex minúsculo antes de gravar.
        Optional<DeviceFingerprint> result = service.record(HASH_UPPER, MERCHANT_ID);

        assertTrue(result.isPresent());
        assertEquals(HASH_UPPER.toLowerCase(), result.get().fingerprintHash());
        verify(repository).record(HASH_UPPER.toLowerCase(), MERCHANT_ID, NOW);
    }

    @Test
    void recordWithNullHashReturnsEmptyWithoutSaving() {
        Optional<DeviceFingerprint> result = service.record(null, MERCHANT_ID);

        assertTrue(result.isEmpty());
        verify(repository, never()).record(anyString(), any(), any());
    }

    @Test
    void recordWithBlankHashReturnsEmptyWithoutSaving() {
        Optional<DeviceFingerprint> result = service.record("   ", MERCHANT_ID);

        assertTrue(result.isEmpty());
        verify(repository, never()).record(anyString(), any(), any());
    }

    @Test
    void recordWithMalformedHashReturnsEmptyWithoutSaving() {
        // Não-hex (g), tamanho errado, curto demais — todos rejeitados.
        assertTrue(service.record("g".repeat(64), MERCHANT_ID).isEmpty());
        assertTrue(service.record("a".repeat(63), MERCHANT_ID).isEmpty());
        assertTrue(service.record("abc", MERCHANT_ID).isEmpty());
        verify(repository, never()).record(anyString(), any(), any());
    }

    @Test
    void recordWithFeatureDisabledReturnsEmptyWithoutSaving() {
        FingerprintProperties disabled = new FingerprintProperties();
        disabled.setEnabled(false);
        DeviceFingerprintService off = new DeviceFingerprintService(repository, disabled, alertPublisher, clock);

        Optional<DeviceFingerprint> result = off.record(HASH_VALID, MERCHANT_ID);

        assertTrue(result.isEmpty());
        verify(repository, never()).record(anyString(), any(), any());
    }
}
