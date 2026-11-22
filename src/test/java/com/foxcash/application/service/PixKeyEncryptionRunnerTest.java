package com.foxcash.application.service;

import com.foxcash.infrastructure.outbound.persistence.repository.PixTransactionJpaRepository;
import com.foxcash.infrastructure.security.CryptoConverter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PixKeyEncryptionRunner} (B-03 — auditoria 2026-08-05): o
 * backfill de boot criptografa apenas registros com {@code pix_key} em claro
 * (sem prefixo {@code v1:}) e é idempotente (registros já criptografados são
 * ignorados).
 */
class PixKeyEncryptionRunnerTest {

    private final PixTransactionJpaRepository jpaRepository = mock(PixTransactionJpaRepository.class);
    private final CryptoConverter cryptoConverter = mock(CryptoConverter.class);

    private PixKeyEncryptionRunner runner() {
        return new PixKeyEncryptionRunner(jpaRepository, cryptoConverter);
    }

    @Test
    void encryptsPlainPixKeysInBatches() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(jpaRepository.findPlainPixKeys()).thenReturn(List.<Object[]>of(
                new Object[]{id1, "52998224725"},
                new Object[]{id2, "pix@loja.com"}));
        when(cryptoConverter.convertToDatabaseColumn(any()))
                .thenAnswer(inv -> "v1:" + inv.getArgument(0));

        runner().run(null);

        verify(jpaRepository).encryptPixKey(eq(id1), eq("v1:52998224725"), any(Instant.class));
        verify(jpaRepository).encryptPixKey(eq(id2), eq("v1:pix@loja.com"), any(Instant.class));
        verify(cryptoConverter).convertToDatabaseColumn("52998224725");
        verify(cryptoConverter).convertToDatabaseColumn("pix@loja.com");
    }

    @Test
    void skipsAlreadyEncryptedRows() throws Exception {
        UUID id1 = UUID.randomUUID();
        when(jpaRepository.findPlainPixKeys()).thenReturn(List.<Object[]>of(
                new Object[]{id1, "v1:abc123"}));

        runner().run(null);

        // Nunca re-criptografa um valor que já tem o prefixo v1:.
        verify(cryptoConverter, never()).convertToDatabaseColumn(any());
        verify(jpaRepository, never()).encryptPixKey(any(), any(), any());
    }

    @Test
    void doesNothingWhenNoPlainKeys() throws Exception {
        when(jpaRepository.findPlainPixKeys()).thenReturn(List.of());

        runner().run(null);

        verify(cryptoConverter, never()).convertToDatabaseColumn(any());
        verify(jpaRepository, never()).encryptPixKey(any(), any(), any());
    }
}
