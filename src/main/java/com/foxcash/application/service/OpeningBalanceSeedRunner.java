package com.foxcash.application.service;

import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Backfill idempotente do ledger (Spec 27): no boot, cria a entry
 * {@code OPENING_BALANCE} (= saldo atual) para cada lojista sem nenhuma
 * movimentação no ledger — ancorando a reconciliação (Spec 30) desde o deploy.
 *
 * <p>Re-boot não duplica: o {@code existsByMerchantId} garante uma entry de
 * abertura por lojista (a constraint única aceita referência nula no
 * PostgreSQL — NULL é distinto em UNIQUE).
 */
@Component
public class OpeningBalanceSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpeningBalanceSeedRunner.class);

    private final MerchantRepository merchantRepository;
    private final WalletLedgerRepository ledgerRepository;

    public OpeningBalanceSeedRunner(MerchantRepository merchantRepository,
                                    WalletLedgerRepository ledgerRepository) {
        this.merchantRepository = merchantRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Merchant> merchants = merchantRepository.findAll();
        int created = 0;
        Instant now = Instant.now();
        for (Merchant merchant : merchants) {
            if (ledgerRepository.existsByMerchantId(merchant.id())) {
                continue;
            }
            WalletLedgerEntry entry = WalletLedgerEntry.record(
                    merchant.id(), WalletLedgerEntryType.OPENING_BALANCE,
                    merchant.walletBalance(), merchant.walletBalance(),
                    null, null, now);
            ledgerRepository.save(entry);
            created++;
        }
        if (created > 0) {
            log.info("Opening balance do ledger criado para {} lojista(s) (Spec 27).", created);
        }
    }
}
