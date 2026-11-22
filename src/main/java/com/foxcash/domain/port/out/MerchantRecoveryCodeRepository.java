package com.foxcash.domain.port.out;

import com.foxcash.domain.model.merchant.MerchantRecoveryCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saida: repositório de códigos de recuperação do MFA do lojista
 * (Spec 94). Códigos persistidos como SHA-256 single-use (comparação direta
 * por hash em tempo constante — nunca usados para gerar códigos).
 */
public interface MerchantRecoveryCodeRepository {

    List<MerchantRecoveryCode> saveAll(List<MerchantRecoveryCode> codes);

    List<MerchantRecoveryCode> findByMethodId(UUID methodId);

    /**
     * SEC-20 (revisão de segurança 2026-08-11): consume ATÔMICO — marca o
     * código como usado somente se ainda estiver sem uso (UPDATE condicional).
     * Mesmo padrão do {@code MasterRecoveryCodeRepository.consumeIfOpen}: dois
     * logins concorrentes com o MESMO código não podem burlar o single-use.
     */
    boolean consumeIfOpen(UUID methodId, String codeHash, Instant now);

    void deleteByMethodId(UUID methodId);
}
