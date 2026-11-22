package com.foxcash.application.service;

import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.WithdrawalRepository;
import com.foxcash.infrastructure.config.PspProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reconciliacao de saques {@code PROCESSING} orfaos (Spec 36) — mesmo papel da
 * Spec 30 para o lado de saida: o job consulta o PSP ({@code getPayout}) e
 * encerra saques presos além de {@code foxcash.psp.payout-reconciliation.pending-since-hours}
 * via o mesmo {@link WithdrawalSettlementService} do webhook (gate de status —
 * sem duplicar estorno).
 *
 * <p>Auditoria 2026-08-05 (B-04): um saque {@code PROCESSING} **sem**
 * {@code external_reference} (falha entre o commit da Fase A e o registro da
 * referência na Fase B) não pode ser consultado no PSP — a reconciliação faz o
 * **retry idempotente**: re-chama {@code createPayout} com o MESMO
 * {@code withdrawal.id} (idempotente no adquirente) e grava a referência. Se o
 * retry falhar, permanece {@code PROCESSING} para o próximo run (sem duplo
 * débito nem saque fantasma).
 */
@Service
public class WithdrawalReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalReconciliationService.class);

    private final WithdrawalRepository withdrawalRepository;
    private final PayoutProviderClient payoutProvider;
    private final WithdrawalSettlementService settlement;
    private final WithdrawalSubmissionPersister submissionPersister;
    private final PspProperties pspProperties;

    public WithdrawalReconciliationService(WithdrawalRepository withdrawalRepository,
                                           PayoutProviderClient payoutProvider,
                                           WithdrawalSettlementService settlement,
                                           WithdrawalSubmissionPersister submissionPersister,
                                           PspProperties pspProperties) {
        this.withdrawalRepository = withdrawalRepository;
        this.payoutProvider = payoutProvider;
        this.settlement = settlement;
        this.submissionPersister = submissionPersister;
        this.pspProperties = pspProperties;
    }

    /**
     * Encerra PROCESSING orfaos consultando o PSP. Retorna quantos liquidou.
     *
     * <p><b>SEC-12 (revisão de segurança 2026-08-11):</b> o método NÃO é mais
     * {@code @Transactional} — o I/O de rede do PSP (getPayout/createPayout)
     * roda FORA de transação, no mesmo padrão da auditoria B-04 dos demais
     * fluxos. Cada liquidação/estorno mantém sua própria transação atômica
     * ({@code settleByExternalReference} é {@code @Transactional}; o retry usa
     * {@code WithdrawalSubmissionPersister} REQUIRES_NEW). Antes, uma lentidão
     * do PSP segurava uma conexão do pool e linhas da tabela de saques por
     * segundos dentro da transação do job.
     */
    public int reconcile(Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(
                pspProperties.getPayoutReconciliation().getPendingSinceHours()));
        List<Withdrawal> orphans = withdrawalRepository.findProcessingOlderThan(cutoff);
        int settled = 0;
        for (Withdrawal orphan : orphans) {
            if (orphan.externalReference() == null) {
                // B-04: PROCESSING sem referência (falha entre Fase A e B) — retry
                // idempotente com o MESMO withdrawal.id.
                if (retryPendingSubmission(orphan)) {
                    settled++;
                }
                continue;
            }
            PayoutProviderClient.PayoutStatus status = payoutProvider.getPayout(orphan.externalReference());
            // Spec 38: estados intermediarios de adquirentes reais (PENDING) NAO
            // liquidam — o saque PROCESSING permanece e o proximo run re-consulta
            // (antes, qualquer estado != PAID virava FAILED e estornava o debito).
            switch (status) {
                case PAID -> {
                    settlement.settleByExternalReference(orphan.externalReference(), "PAID", null);
                    settled++;
                }
                case FAILED -> {
                    settlement.settleByExternalReference(orphan.externalReference(), "FAILED",
                            "reconciliacao: falha no PSP");
                    settled++;
                }
                case PENDING -> log.debug("Reconciliacao: saque {} ainda PROCESSING no PSP — aguardando",
                        orphan.externalReference());
            }
        }
        if (settled > 0) {
            log.info("Reconciliacao de saques: {} PROCESSING orfaos liquidados (cutoff={})",
                    settled, cutoff);
        }
        return settled;
    }

    /**
     * B-04: re-submete ao PSP um saque {@code PROCESSING} sem
     * {@code external_reference}, usando o MESMO {@code withdrawal.id} (idempotente
     * no adquirente — padrão {@code code} = id da Spec 28). Destino reconstruído do
     * snapshot persistido ({@code TYPE:chave} — BANK_ACCOUNT usa {@code |}).
     */
    private boolean retryPendingSubmission(Withdrawal orphan) {
        try {
            PayoutProviderClient.PayoutResult result = payoutProvider.createPayout(
                    new PayoutProviderClient.PayoutCommand(orphan.id(), orphan.amount(),
                            payoutDestinationOf(orphan), "retry saque " + orphan.id()));
            submissionPersister.submit(orphan.id(), result.externalReference(),
                    // Spec 92 (roteamento pós-fallback): provedor que ACEITOU no resultado.
                    result.provider() != null ? result.provider().toUpperCase()
                            : pspProperties.getPayoutProvider().toUpperCase());
            log.warn("Reconciliacao: saque {} sem external_reference re-submetido ao PSP (retry idempotente)",
                    orphan.id());
            return true;
        } catch (RuntimeException e) {
            log.warn("Reconciliacao: retry do saque {} falhou; permanece PROCESSING para o proximo run",
                    orphan.id());
            return false;
        }
    }

    /** Reconstroi o destino do snapshot {@code TYPE:chave} persistido no saque (B-04). */
    private static PayoutProviderClient.PayoutDestination payoutDestinationOf(Withdrawal withdrawal) {
        String snapshot = withdrawal.destinationKeySnapshot();
        if (snapshot == null) {
            return new PayoutProviderClient.PayoutDestination(null, null, null, null, null);
        }
        int idx = snapshot.indexOf(':');
        String type = idx > 0 ? snapshot.substring(0, idx) : "";
        String raw = idx > 0 ? snapshot.substring(idx + 1) : snapshot;
        if ("BANK_ACCOUNT".equals(type)) {
            String[] parts = raw.split("\\|");
            return new PayoutProviderClient.PayoutDestination(null,
                    parts.length > 0 ? parts[0] : null,
                    parts.length > 1 ? parts[1] : null,
                    parts.length > 2 ? parts[2] : null,
                    parts.length > 3 ? parts[3] : null);
        }
        return new PayoutProviderClient.PayoutDestination(raw, null, null, null, null);
    }
}
