package com.foxcash.domain.model.fingerprint;

import java.time.Instant;
import java.util.Objects;

/**
 * Fingerprint de dispositivo (Spec 141) — hash SHA-256 (64 hex) dos sinais do
 * navegador coletados pelo frontend do portal (canvas, WebGL, fontes, UA,
 * timezone, etc.). Sinal de risco PASSIVO: nunca bloqueia; alimenta auditoria
 * e análise (mesmo dispositivo em N contas = risco).
 */
public record DeviceFingerprint(
        String fingerprintHash,
        Instant firstSeenAt,
        Instant lastSeenAt,
        long seenCount,
        int distinctMerchants) {

    public DeviceFingerprint {
        Objects.requireNonNull(fingerprintHash, "fingerprintHash must not be null");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    }
}
