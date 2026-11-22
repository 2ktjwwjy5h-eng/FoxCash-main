package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantConfigRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantConfigResponse;

import java.util.UUID;

/**
 * Porta de entrada: config fina por lojista + controle de conta (Spec 98) —
 * escrita sob {@code ROLE_MASTER} com RBAC (Spec 69), auditoria (Spec 37) e
 * step-up TOTP nas ações sensíveis (debit/ban). Os DTOs da resposta são os
 * read-models REST (mesmo padrão do {@link MerchantPanelQueryUseCase}).
 */
public interface MerchantConfigUseCase {

    /** Config atual do lojista (nulls = sem override → default global); 404 se inexistente. */
    MerchantConfigResponse getConfig(UUID merchantId);

    /** Atualiza parcial (PATCH — null = não alterar) + auditoria {@code MERCHANT_CONFIG_UPDATED}. */
    MerchantConfigResponse updateConfig(UUID merchantId, MerchantConfigRequest request,
                                        UUID actorId, String ip);

    /** Débito administrativo (step-up + ledger duplo ADMIN_DEBIT + auditoria); saldo pode ficar negativo. */
    DebitOutcome debit(UUID merchantId, Money amount, String reason, UUID actorId, String ip);

    /** Marca/desmarca honeypot (ambiente controlado; reversível) + auditoria. */
    void markHoneypot(UUID merchantId, boolean enabled, UUID actorId, String ip);

    /** Kill switch: status {@code INACTIVE} (bloqueia portal/API/saques) + auditoria. */
    void deactivate(UUID merchantId, UUID actorId, String ip);

    /** Kill switch: status {@code ACTIVE} + auditoria. */
    void activate(UUID merchantId, UUID actorId, String ip);

    /** Banimento irreversível ({@code BANNED}; step-up + auditoria {@code MERCHANT_BANNED}). */
    void ban(UUID merchantId, String reason, UUID actorId, String ip);

    /** Resultado do débito administrativo (saldo após a mutação + valor debitado). */
    record DebitOutcome(Money newBalance, Money debitedAmount) {
    }
}
