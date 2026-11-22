package com.foxcash.application.service;

import com.foxcash.domain.exception.DestinationInactiveException;
import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.port.in.WithdrawalUseCase;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.infrastructure.config.BillingProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.security.AuthenticatedMerchantResolver;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Aplicação: saque automatico do wallet do lojista (Spec 14, Spec 32, Spec 36).
 *
 * <p>O saque exige um destino ATIVO cadastrado na conta do lojista (Spec 31):
 * destino inexistente/de outro lojista → 404 {@code destination_not_found} (não
 * revela existência); inativo → 409 {@code destination_inactive}.
 *
 * <p>Auditoria 2026-08-05 (B-04): o PSP é chamado **fora** da transação de
 * banco. O fluxo é em 2 fases: <b>Fase A</b> (transação própria, ver
 * {@link WithdrawalProcessingPersister}) debita o wallet e cria o saque
 * {@code PROCESSING} **sem** {@code external_reference} + ledger
 * {@code WITHDRAWAL_DEBIT}; <b>Fase B</b> (fora da transação) chama o
 * {@code payoutProvider} e grava a referência em transação própria
 * ({@link WithdrawalSubmissionPersister}, REQUIRES_NEW). Falha síncrona do
 * provider → estorno do débito + saque {@code FAILED} (mesmo fluxo do
 * {@link WithdrawalSettlementService}) — a conexão do pool não fica retida e
 * não há saque fantasma nem duplo débito. A conclusão ({@code COMPLETED}) vem
 * do webhook de saida/reconciliação (o evento {@code WITHDRAWAL_COMPLETED} é
 * publicado nesse momento).
 */
@Service
public class WithdrawalService implements WithdrawalUseCase {

    private final WithdrawalDestinationRepository destinationRepository;
    private final BillingProperties billingProperties;
    private final PricingProperties pricingProperties;
    private final PayoutProviderClient payoutProvider;
    private final PspProperties pspProperties;
    private final PlatformActivityRecorder activityRecorder;
    private final WithdrawalProcessingPersister processingPersister;
    private final WithdrawalSubmissionPersister submissionPersister;
    private final WithdrawalSettlementService settlement;
    private final AuthenticatedMerchantResolver authenticatedMerchant;

    public WithdrawalService(WithdrawalDestinationRepository destinationRepository,
                             BillingProperties billingProperties,
                             PricingProperties pricingProperties,
                             PayoutProviderClient payoutProvider,
                             PspProperties pspProperties,
                             PlatformActivityRecorder activityRecorder,
                             WithdrawalProcessingPersister processingPersister,
                             WithdrawalSubmissionPersister submissionPersister,
                             WithdrawalSettlementService settlement,
                             AuthenticatedMerchantResolver authenticatedMerchant) {
        this.destinationRepository = destinationRepository;
        this.billingProperties = billingProperties;
        this.pricingProperties = pricingProperties;
        this.payoutProvider = payoutProvider;
        this.pspProperties = pspProperties;
        this.activityRecorder = activityRecorder;
        this.processingPersister = processingPersister;
        this.submissionPersister = submissionPersister;
        this.settlement = settlement;
        this.authenticatedMerchant = authenticatedMerchant;
    }

    @Override
    public Result withdraw(Command command) {
        if (command.destinationId() == null) {
            throw new IllegalArgumentException("destinationId é obrigatório no saque do lojista");
        }
        // SEC-13 (revisão de segurança 2026-08-11): se o principal autenticado é
        // um LOJISTA (resolver preenchido) mas o merchantId do comando aponta
        // para OUTRO lojista, falha fechada (403) — o serviço não confia cegamente
        // no merchantId do comando (IDOR latente; o controller já protege via
        // MerchantAccessGuard, mas a defesa em profundidade fica no serviço).
        Optional<Merchant> current = authenticatedMerchant.currentMerchant();
        if (current.isPresent() && !current.get().id().equals(command.merchantId())) {
            throw new MerchantAccessDeniedException();
        }
        // Destino: pertence ao lojista e está ATIVO (404/409). Leitura simples —
        // a tabela de destinos é privada; sem lock (dados privados da Spec 31).
        WithdrawalDestination destination = destinationRepository
                .findByIdAndMerchantId(command.destinationId(), command.merchantId())
                .orElseThrow(() -> new DestinationNotFoundException(command.destinationId()));
        if (!destination.active()) {
            throw new DestinationInactiveException(command.destinationId());
        }

        // Fase A (transação própria): débito + PROCESSING (sem external_reference)
        // + ledger WITHDRAWAL_DEBIT — atomicidade do movimento financeiro. Spec 88:
        // debita valor + taxa (default R$ 2,50 — foxcash.pricing.withdrawal-fee).
        WithdrawalProcessingPersister.MerchantWithdrawal pending = processingPersister
                .createMerchantWithdrawal(command.merchantId(), command.amount(),
                        Money.of(pricingProperties.getWithdrawalFee()), destination,
                        billingProperties.isBlockWithdrawalsOnDebt());

        // Fase B (FORA da transação): PSP. Sucesso → grava a referência em transação
        // própria (REQUIRES_NEW); falha SÍNCRONA do createPayout → estorno do débito
        // + saque FAILED (mesmo fluxo do settlement) — o pool não fica retido durante
        // o I/O. Auditoria 2026-08-05: o estorno cobre APENAS a falha do PSP — se o
        // PSP já aceitou (submit OK), nenhuma falha posterior estorna um saque pago
        // no adquirente (evita duplo pagamento/ghost).
        PayoutProviderClient.PayoutResult result;
        try {
            result = payoutProvider.createPayout(
                    new PayoutProviderClient.PayoutCommand(pending.withdrawal().id(), command.amount(),
                            toPayoutDestination(destination), "saque lojista"));
        } catch (RuntimeException e) {
            // Falha síncrona do PSP: estorno em transação própria (mesmo fluxo do
            // settlement FAILED) — nenhum débito fica pendurado nem saque fantasma.
            settlement.settleFailedByWithdrawalId(pending.withdrawal().id(),
                    "Falha no PSP: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            throw e;
        }
        Withdrawal submitted = submissionPersister.submit(pending.withdrawal().id(),
                result.externalReference(),
                // Spec 92 (roteamento pós-fallback): persiste o provedor que ACEITOU
                // (o router o carrega no resultado); sem router/null, o configurado.
                result.provider() != null ? result.provider().toUpperCase()
                        : pspProperties.getPayoutProvider().toUpperCase());
        // Spec 41: relatório de atividades — saque solicitado (valor sem PII).
        activityRecorder.record(PlatformActorType.MERCHANT, submitted.merchantId(),
                PlatformAction.WITHDRAWAL_REQUESTED, "WITHDRAWAL", submitted.id(),
                submitted.merchantId(), Map.of("amount", submitted.amount().amount()));
        return new Result(submitted.id(), submitted.amount(), pending.walletBalance());
    }

    private static PayoutProviderClient.PayoutDestination toPayoutDestination(
            WithdrawalDestination destination) {
        return new PayoutProviderClient.PayoutDestination(
                destination.value(), destination.bankCode(), destination.agency(),
                destination.account(), destination.accountType());
    }
}
