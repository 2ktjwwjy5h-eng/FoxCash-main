package com.foxcash.application.service;

import com.foxcash.domain.exception.AutoWithdrawBelowThresholdException;
import com.foxcash.domain.exception.InsufficientBalanceException;
import com.foxcash.domain.exception.MerchantNotActiveException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.OutstandingDebtException;
import com.foxcash.domain.exception.WithdrawalsBlockedException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.withdrawal.AutoWithdrawConfig;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.port.out.AutoWithdrawConfigRepository;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.infrastructure.config.AutoWithdrawProperties;
import com.foxcash.infrastructure.config.BillingProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.config.PspProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Execucao do gatilho do saque automatico por limiar de saldo (Spec 70) — o
 * job itera sobre as configs ativas e, para cada uma com
 * {@code wallet_balance >= threshold} SOB o lock pessimista do lojista, cria um
 * saque {@code MERCHANT} {@code PROCESSING} no valor **igual a todo o saldo**
 * (decisao do usuario), reusando a Fase A ({@link WithdrawalProcessingPersister})
 * e a Fase B (PSP fora da transacao + {@link WithdrawalSubmissionPersister}).
 *
 * <p>Condicao bloqueante (nao-ACTIVE, saques bloqueados, divida, destino
 * inativo/removido, saldo abaixo do limiar na hora do lock) → <b>skip
 * silencioso</b> (log, sem excecao propagada — o job nao falha). Falha SINCRONA
 * do PSP → estorno ({@link WithdrawalSettlementService}) + <b>rethrow</b> (o
 * {@link JobExecutionService} marca o job como FAILED e emite {@code JOB_FAILED};
 * o saldo foi restaurado → a proxima execucao re-tenta naturalmente, D11.7).
 */
@Service
public class AutoWithdrawRunnerService {

    private static final Logger log = LoggerFactory.getLogger(AutoWithdrawRunnerService.class);

    private final AutoWithdrawConfigRepository configRepository;
    private final WithdrawalDestinationRepository destinationRepository;
    private final WithdrawalProcessingPersister processingPersister;
    private final BillingProperties billingProperties;
    private final PricingProperties pricingProperties;
    private final PayoutProviderClient payoutProvider;
    private final PspProperties pspProperties;
    private final WithdrawalSubmissionPersister submissionPersister;
    private final WithdrawalSettlementService settlement;
    private final PlatformActivityRecorder activityRecorder;
    private final AutoWithdrawProperties properties;

    public AutoWithdrawRunnerService(AutoWithdrawConfigRepository configRepository,
                                     WithdrawalDestinationRepository destinationRepository,
                                     WithdrawalProcessingPersister processingPersister,
                                     BillingProperties billingProperties,
                                     PricingProperties pricingProperties,
                                     PayoutProviderClient payoutProvider,
                                     PspProperties pspProperties,
                                     WithdrawalSubmissionPersister submissionPersister,
                                     WithdrawalSettlementService settlement,
                                     PlatformActivityRecorder activityRecorder,
                                     AutoWithdrawProperties properties) {
        this.configRepository = configRepository;
        this.destinationRepository = destinationRepository;
        this.processingPersister = processingPersister;
        this.billingProperties = billingProperties;
        this.pricingProperties = pricingProperties;
        this.payoutProvider = payoutProvider;
        this.pspProperties = pspProperties;
        this.submissionPersister = submissionPersister;
        this.settlement = settlement;
        this.activityRecorder = activityRecorder;
        this.properties = properties;
    }

    /** Resultado de uma execucao do job (saques criados vs skips). */
    public record RunReport(int created, int skipped) {
    }

    /**
     * Executa o gatilho para todas as configs ativas. Respeita o kill-switch
     * ({@code foxcash.auto-withdraw.enabled}) e o teto
     * {@code max-withdrawals-per-run} (conta apenas saques CRIADOS — skips sao
     * leituras + lock baratos e nao consomem o teto do trabalho real, D11.5).
     */
    public RunReport runAll(Instant now) {
        if (!properties.isEnabled()) {
            log.info("auto-withdraw desligado (kill-switch foxcash.auto-withdraw.enabled=false)");
            return new RunReport(0, 0);
        }
        int created = 0;
        int skipped = 0;
        for (AutoWithdrawConfig config : configRepository.findEnabled()) {
            if (created >= properties.getMaxWithdrawalsPerRun()) {
                break; // os restantes ficam para a proxima execucao
            }
            if (runForConfig(config)) {
                created++;
            } else {
                skipped++;
            }
        }
        log.info("auto-withdraw job concluido: criados={} skips={}", created, skipped);
        return new RunReport(created, skipped);
    }

    /**
     * Tenta criar o auto-saque de uma config ativa.
     *
     * @return {@code true} se um saque foi criado (Fase A OK + Fase B), {@code
     * false} se skip silencioso
     * @throws RuntimeException falha SINCRONA do PSP (apos estorno) — propagada
     *                         para o {@link JobExecutionService} marcar o job FAILED
     */
    boolean runForConfig(AutoWithdrawConfig config) {
        // 1. Destino ativo (pode ter sido desativado/removido depois da config).
        WithdrawalDestination destination = destinationRepository
                .findByIdAndMerchantId(config.destinationId(), config.merchantId())
                .orElse(null);
        if (destination == null) {
            log.warn("auto-withdraw skip lojista={} motivo=destino_nao_encontrado destino={}",
                    config.merchantId(), config.destinationId());
            return false;
        }
        if (!destination.active()) {
            log.warn("auto-withdraw skip lojista={} motivo=destino_inativo destino={}",
                    config.merchantId(), config.destinationId());
            return false;
        }

        // 2. Fase A (transacao propria): lock + threshold + debito + PROCESSING.
        //    Threshold/valor avaliados DENTRO do lock — sem corrida (D11.2).
        WithdrawalProcessingPersister.MerchantWithdrawal pending;
        try {
            pending = processingPersister.createMerchantAutoWithdrawal(
                    config.merchantId(), destination, config.threshold(),
                    billingProperties.isBlockWithdrawalsOnDebt(),
                    Money.of(pricingProperties.getWithdrawalFee()));
        } catch (MerchantNotActiveException | WithdrawalsBlockedException
                 | OutstandingDebtException | AutoWithdrawBelowThresholdException
                 | InsufficientBalanceException | MerchantNotFoundException e) {
            log.info("auto-withdraw skip lojista={} motivo={}",
                    config.merchantId(), e.getClass().getSimpleName());
            return false;
        }

        // 3. Fase B (FORA da transacao): PSP + submit + atividade SYSTEM.
        PayoutProviderClient.PayoutResult result;
        try {
            result = payoutProvider.createPayout(new PayoutProviderClient.PayoutCommand(
                    pending.withdrawal().id(), pending.withdrawal().amount(),
                    toPayoutDestination(destination), "saque automatico"));
        } catch (RuntimeException e) {
            // Estorno em transacao propria (mesmo fluxo do saque manual) — nenhum
            // debito fica pendurado; rethrow marca o job FAILED (Spec 53).
            settlement.settleFailedByWithdrawalId(pending.withdrawal().id(),
                    "Falha no PSP: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            throw e;
        }
        Withdrawal submitted = submissionPersister.submit(pending.withdrawal().id(),
                result.externalReference(),
                // Spec 92 (roteamento pós-fallback): provedor que ACEITOU no resultado.
                result.provider() != null ? result.provider().toUpperCase()
                        : pspProperties.getPayoutProvider().toUpperCase());
        // Spec 41: saque automatico criado (actor SYSTEM, sem IP) — valor sem PII.
        activityRecorder.record(PlatformActorType.SYSTEM, null,
                PlatformAction.AUTO_WITHDRAW_REQUESTED, "WITHDRAWAL", submitted.id(),
                submitted.merchantId(), Map.of("amount", submitted.amount().amount()));
        return true;
    }

    private static PayoutProviderClient.PayoutDestination toPayoutDestination(
            WithdrawalDestination destination) {
        return new PayoutProviderClient.PayoutDestination(
                destination.value(), destination.bankCode(), destination.agency(),
                destination.account(), destination.accountType());
    }
}
