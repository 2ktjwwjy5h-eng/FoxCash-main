package com.foxcash.application.service;

import com.foxcash.domain.model.fingerprint.DeviceFingerprint;
import com.foxcash.domain.port.out.DeviceFingerprintRepository;
import com.foxcash.infrastructure.config.FingerprintProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aplicação: registro de device fingerprint do portal (Spec 141).
 *
 * <p>Sinal de risco PASSIVO e best-effort: o frontend envia um hash SHA-256
 * (64 hex) dos sinais do navegador no cadastro/login; aqui o hash é validado,
 * normalizado e gravado (upsert atômico + associação com o lojista). Um hash
 * ausente/malformado NUNCA quebra o request — o fingerprint é auditoria e sinal
 * de risco para o master (mesmo dispositivo em N contas).
 *
 * <p>Quando o mesmo hash atinge {@code alert-threshold} contas distintas,
 * notifica o operador via {@link FingerprintAlertPublisher}
 * ({@code FINGERPRINT_DEVICE_SHARED} — outbox → webhook, padrão Spec 64).
 */
@Service
public class DeviceFingerprintService {

    /** Hash SHA-256 em hex minúsculo (64 chars) — formato do frontend. */
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final DeviceFingerprintRepository repository;
    private final FingerprintProperties properties;
    private final FingerprintAlertPublisher alertPublisher;
    private final Clock clock;

    public DeviceFingerprintService(DeviceFingerprintRepository repository,
                                    FingerprintProperties properties,
                                    FingerprintAlertPublisher alertPublisher,
                                    Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.alertPublisher = alertPublisher;
        this.clock = clock;
    }

    /**
     * Registra o fingerprint do dispositivo para o lojista (best-effort).
     *
     * @param rawHash    hash SHA-256 hex enviado pelo frontend (opcional)
     * @param merchantId lojista que gerou o fingerprint (cadastro/login)
     * @return snapshot gravado (para a trilha de auditoria) — vazio quando o
     *         fingerprint está desligado ou o hash é inválido/ausente
     */
    public Optional<DeviceFingerprint> record(String rawHash, UUID merchantId) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        String hash = normalize(rawHash);
        if (hash == null) {
            return Optional.empty();
        }
        DeviceFingerprint fingerprint = repository.record(hash, merchantId, clock.instant());
        // Alerta de risco (throttled por hash, REQUIRES_NEW, fail-safe): o
        // operador é avisado quando o device toca N contas — nunca bloqueia.
        alertPublisher.maybeAlert(fingerprint.fingerprintHash(), fingerprint.distinctMerchants());
        return Optional.of(fingerprint);
    }

    /** Normaliza para hex minúsculo; {@code null} quando ausente/inválido. */
    private String normalize(String rawHash) {
        if (rawHash == null || rawHash.isBlank()) {
            return null;
        }
        String hash = rawHash.trim().toLowerCase();
        return SHA256_HEX.matcher(hash).matches() ? hash : null;
    }
}
