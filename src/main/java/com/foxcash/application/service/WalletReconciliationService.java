package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.port.in.WalletReconciliationUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReconciliationIssueRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciliação e integridade do saldo (Spec 30) — o backstop da Fase 5:
 * valida, para cada lojista, que {@code merchants.wallet_balance} é igual ao
 * {@code balance_after} da ÚLTIMA entry do ledger (Spec 27). Divergência →
 * issue {@code OPEN} em {@code reconciliation_issues} (idempotente) para
 * auditoria do master — correção automática fica fora de escopo.
 *
 * <p>Também contabiliza cobranças órfãs (transações {@code PENDING} sem
 * {@code externalReference} — timeout da Spec 28) no resumo, como visibilidade
 * sem ação automática.
 */
@Service
public class WalletReconciliationService implements WalletReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(WalletReconciliationService.class);

    /** Tamanho do batch de varredura (auditoria 2026-08-05/M-A3). */
    private static final int PAGE_SIZE = 200;

    private final MerchantRepository merchantRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final ReconciliationIssueRepository issuesRepository;
    private final TransactionRepository transactionRepository;

    public WalletReconciliationService(MerchantRepository merchantRepository,
                                       WalletLedgerRepository ledgerRepository,
                                       ReconciliationIssueRepository issuesRepository,
                                       TransactionRepository transactionRepository) {
        this.merchantRepository = merchantRepository;
        this.ledgerRepository = ledgerRepository;
        this.issuesRepository = issuesRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    @Transactional
    public ReconciliationReport run() {
        // Auditoria 2026-08-05 (M-A3): processa em páginas (batch) — antes
        // carregava a tabela inteira em memória (findAll) e fazia N queries.
        int checked = 0;
        int ok = 0;
        int page = 0;
        PageResult<Merchant> batch;
        do {
            batch = merchantRepository.findAll(page, PAGE_SIZE);
            for (Merchant merchant : batch.items()) {
                checked++;
                Optional<WalletLedgerEntry> latest = ledgerRepository.findLatestByMerchantId(merchant.id());
                // Sem entry de ledger (ex.: lojista sem movimentação) → abrevia: esperado
                // é o próprio saldo atual (consistente por definição).
                BigDecimal expected = latest.map(e -> e.balanceAfter().amount())
                        .orElse(merchant.walletBalance().amount());
                if (expected.compareTo(merchant.walletBalance().amount()) == 0) {
                    ok++;
                    continue;
                }
                issuesRepository.open(merchant.id(), Money.of(expected), merchant.walletBalance(), Instant.now());
            }
            page++;
        } while (!batch.items().isEmpty() && page < batch.totalPages());
        long orphanCharges = transactionRepository.countPendingWithoutExternalReference();
        if (checked - ok > 0) {
            log.warn("Reconciliacao: {} divergencias em {} lojistas checados; {} cobrancas orfas",
                    checked - ok, checked, orphanCharges);
        }
        return new ReconciliationReport(checked, ok, checked - ok, orphanCharges);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ReconciliationIssue> findIssues(UUID merchantId, ReconciliationIssueStatus status,
                                                      int page, int size) {
        return issuesRepository.find(merchantId, status, page, size);
    }
}
