package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.PvsAmountException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.in.PvsPaymentUseCase;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.WithdrawalRepository;
import com.foxcash.domain.pvs.ParsedPix;
import com.foxcash.domain.pvs.PixCodeParser;
import com.foxcash.infrastructure.config.BillingProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.config.PvsProperties;
import com.foxcash.infrastructure.security.AuthenticatedMerchantResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicação: Pagamento via Saldo (PVS, Spec 120) — o lojista paga um Pix Copia
 * e Cola ESTÁTICO com o saldo do wallet. Espelha o {@link WithdrawalService}
 * (Spec 36/B-04): <b>Fase A</b> (transação própria, ver
 * {@link WithdrawalProcessingPersister#createBillPaymentWithdrawal}) debita o
 * wallet (valor + taxa R$ 2,50 — Spec 88), cria o {@code withdrawal} de tipo
 * {@code BILL_PAYMENT} com destino arbitrário (chave extraída no snapshot) e
 * avalia os tetos anti-abuso SOB o lock; <b>Fase B</b> (fora da transação)
 * chama o {@code payoutProvider} e grava a referência em transação própria
 * ({@link WithdrawalSubmissionPersister}, REQUIRES_NEW). Falha síncrona do
 * provider → estorno de valor + taxa (mesmo fluxo do
 * {@link WithdrawalSettlementService}). Desfecho COMPLETED/FAILED via webhook
 * de saída/reconciliação — 100% reuso, nenhuma mudança nessas camadas.
 *
 * <p>O parser ({@link PixCodeParser}) é a ÚNICA porta de entrada do destino —
 * o endpoint só aceita {@code pixCode}; chave livre digitada nunca chega ao
 * serviço. {@code pixCode} nunca é logado.
 */
@Service
public class PvsPaymentService implements PvsPaymentUseCase {

    private final PricingProperties pricingProperties;
    private final PvsProperties pvsProperties;
    private final PspProperties pspProperties;
    private final BillingProperties billingProperties;
    private final PayoutProviderClient payoutProvider;
    private final PlatformActivityRecorder activityRecorder;
    private final WithdrawalProcessingPersister processingPersister;
    private final WithdrawalSubmissionPersister submissionPersister;
    private final WithdrawalSettlementService settlement;
    private final WithdrawalRepository withdrawalRepository;
    private final AuthenticatedMerchantResolver authenticatedMerchant;

    public PvsPaymentService(PricingProperties pricingProperties,
                             PvsProperties pvsProperties,
                             PspProperties pspProperties,
                             BillingProperties billingProperties,
                             PayoutProviderClient payoutProvider,
                             PlatformActivityRecorder activityRecorder,
                             WithdrawalProcessingPersister processingPersister,
                             WithdrawalSubmissionPersister submissionPersister,
                             WithdrawalSettlementService settlement,
                             WithdrawalRepository withdrawalRepository,
                             AuthenticatedMerchantResolver authenticatedMerchant) {
        this.pricingProperties = pricingProperties;
        this.pvsProperties = pvsProperties;
        this.pspProperties = pspProperties;
        this.billingProperties = billingProperties;
        this.payoutProvider = payoutProvider;
        this.activityRecorder = activityRecorder;
        this.processingPersister = processingPersister;
        this.submissionPersister = submissionPersister;
        this.settlement = settlement;
        this.withdrawalRepository = withdrawalRepository;
        this.authenticatedMerchant = authenticatedMerchant;
    }

    @Override
    public Result pay(PayCommand command) {
        // SEC-13 (revisão de segurança 2026-08-11): se o principal autenticado é
        // um LOJISTA mas o merchantId do comando aponta para OUTRO lojista →
        // falha fechada (403). O controller usa o merchantId do principal; a
        // defesa em profundidade fica no serviço.
        Optional<Merchant> current = authenticatedMerchant.currentMerchant();
        if (current.isPresent() && !current.get().id().equals(command.merchantId())) {
            throw new MerchantAccessDeniedException();
        }
        // Parse do Pix Copia e Cola (domínio puro, SEM lock — falha → 400, nada
        // é criado). O parser é a única porta do destino arbitrário.
        ParsedPix parsed = PixCodeParser.parse(command.pixCode());
        Money amount = resolveAmount(parsed, command.amount());

        // Fase A (transação própria): débito valor + taxa + PROCESSING +
        // tetos anti-abuso sob o lock do lojista (SEC-19 — sem corrida).
        WithdrawalProcessingPersister.MerchantWithdrawal pending = processingPersister
                .createBillPaymentWithdrawal(command.merchantId(), amount,
                        Money.of(pricingProperties.getWithdrawalFee()), parsed.key(),
                        pvsProperties.getDailyCap(), pvsProperties.getMaxPerPayment(),
                        billingProperties.isBlockWithdrawalsOnDebt());

        // Fase B (FORA da transação): PSP. Sucesso → grava a referência em
        // transação própria (REQUIRES_NEW); falha SÍNCRONA do createPayout →
        // estorno de valor + taxa (mesmo fluxo do saque — B-04/Spec 88).
        PayoutProviderClient.PayoutResult result;
        try {
            result = payoutProvider.createPayout(new PayoutProviderClient.PayoutCommand(
                    pending.withdrawal().id(), amount,
                    new PayoutProviderClient.PayoutDestination(parsed.key(), null, null, null, null),
                    "pagamento via saldo"));
        } catch (RuntimeException e) {
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
        // Spec 41: trilha — valor apenas; a chave nunca vai em claro.
        activityRecorder.record(PlatformActorType.MERCHANT, submitted.merchantId(),
                PlatformAction.BILL_PAYMENT_REQUESTED, "WITHDRAWAL", submitted.id(),
                submitted.merchantId(), Map.of("amount", submitted.amount().amount()));
        return new Result(submitted.id(), submitted.amount(), submitted.fee(),
                pending.walletBalance(), "PROCESSING");
    }

    @Override
    public Limits limits(UUID merchantId) {
        Money dailyCap = Money.of(pvsProperties.getDailyCap());
        Money maxPerPayment = Money.of(pvsProperties.getMaxPerPayment());
        BigDecimal usedToday = Money.normalize(withdrawalRepository
                .sumAmountAndFeeByMerchantAndTypeCreatedSince(merchantId, WithdrawalType.BILL_PAYMENT,
                        LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()));
        Money used = Money.of(usedToday);
        Money remaining = dailyCap.subtract(used);
        if (remaining.amount().signum() < 0) {
            remaining = Money.of("0.00");
        }
        return new Limits(dailyCap, used, remaining, maxPerPayment);
    }

    /**
     * Resolve o valor efetivo: EMV com valor (campo 54) → efetivo = valor do
     * EMV (request com {@code amount} DIVERGENTE → 400 {@code pvs_amount_mismatch});
     * EMV sem valor → {@code amount} do request OBRIGATÓRIO (400
     * {@code pvs_amount_required}); efetivo sempre {@code > 0}.
     */
    private static Money resolveAmount(ParsedPix parsed, Money requested) {
        final Money effective;
        if (parsed.amount() != null) {
            effective = Money.of(parsed.amount());
            if (requested != null && requested.amount().compareTo(parsed.amount()) != 0) {
                throw new PvsAmountException("pvs_amount_mismatch",
                        "O valor informado não bate com o valor do Pix Copia e Cola");
            }
        } else {
            if (requested == null) {
                throw new PvsAmountException("pvs_amount_required",
                        "O Pix Copia e Cola não carrega valor — informe o amount");
            }
            effective = requested;
        }
        if (effective.amount().signum() <= 0) {
            throw new PvsAmountException("pvs_amount_required",
                    "O valor do pagamento deve ser maior que zero");
        }
        return effective;
    }
}
