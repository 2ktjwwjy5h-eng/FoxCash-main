package com.foxcash.application.service;

import com.foxcash.infrastructure.outbound.persistence.repository.PixTransactionJpaRepository;
import com.foxcash.infrastructure.security.CryptoConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Backfill idempotente da criptografia da {@code pix_key} (auditoria
 * 2026-08-05/B-03): no boot, criptografa os registros legados cuja
 * {@code pix_key} está em claro (sem prefixo {@code v1:}) — todos os demais
 * dados sensíveis já usam {@link CryptoConverter} (AES-256/GCM).
 *
 * <p>Não pode carregar a entidade JPA para este backfill: o {@code @Convert}
 * na coluna aplicaria o converter na LEITURA e o valor em claro quebraria.
 * Por isso a leitura é por query nativa ({@code Object[]}) e a escrita aplica o
 * converter manualmente no SQL. Re-boot não duplica: a query já filtra apenas
 * registros sem o prefixo {@code v1:}.
 */
@Component
public class PixKeyEncryptionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PixKeyEncryptionRunner.class);

    private final PixTransactionJpaRepository jpaRepository;
    private final CryptoConverter cryptoConverter;

    public PixKeyEncryptionRunner(PixTransactionJpaRepository jpaRepository,
                                  CryptoConverter cryptoConverter) {
        this.jpaRepository = jpaRepository;
        this.cryptoConverter = cryptoConverter;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Object[]> plain = jpaRepository.findPlainPixKeys();
        int encrypted = 0;
        Instant now = Instant.now();
        for (Object[] row : plain) {
            UUID id = (UUID) row[0];
            String clear = (String) row[1];
            if (clear == null || clear.startsWith(CryptoConverter.VERSION)) {
                continue; // defensivo: a query já filtra, mas nunca re-criptografa
            }
            jpaRepository.encryptPixKey(id, cryptoConverter.convertToDatabaseColumn(clear), now);
            encrypted++;
        }
        if (encrypted > 0) {
            log.info("pix_key criptografada em repouso para {} transacao(ões) (B-03).", encrypted);
        }
    }
}
