package com.foxcash.domain.port.out;

import com.foxcash.domain.model.master.MasterRecoveryCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saida: repositório de códigos de recuperação do MFA (Spec 44).
 * Códigos persistidos como SHA-256 single-use (comparação direta por hash em
 * tempo constante — nunca usados para gerar códigos).
 */
public interface MasterRecoveryCodeRepository {

    List<MasterRecoveryCode> saveAll(List<MasterRecoveryCode> codes);

    List<MasterRecoveryCode> findByMethodId(UUID methodId);

    /**
     * SEC-20 (revisão de segurança 2026-08-11): consume ATÔMICO — marca o
     * código como usado somente se ainda estiver sem uso (UPDATE condicional).
     * Antes o consumo era leitura + {@code saveAll}: dois logins concorrentes
     * com o MESMO código ambos liam UNUSED e ambos salvavam — o single-use era
     * burlado sob corrida (mesma classe do SEC-10, já corrigido no reset token).
     */
    boolean consumeIfOpen(UUID methodId, String codeHash, Instant now);

    void deleteByMethodId(UUID methodId);
}
