package com.foxcash.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import com.foxcash.domain.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link OpeningBalanceSeedRunner} (Spec 27): backfill idempotente do
 * ledger — entry OPENING_BALANCE só para lojista SEM movimentação; re-boot não
 * duplica (existsByMerchantId) e o log só aparece quando algo foi criado.
 */
class OpeningBalanceSeedRunnerTest {

    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);

    private OpeningBalanceSeedRunner runner;

    @BeforeEach
    void setUp() {
        runner = new OpeningBalanceSeedRunner(merchantRepository, ledgerRepository);
        when(ledgerRepository.existsByMerchantId(any())).thenReturn(false);
    }

    private Merchant merchant(UUID id, String balance) {
        return Merchant.restore(id, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of(balance), false, Instant.now());
    }

    @Test
    void createsOpeningBalanceOnlyForMerchantsWithoutLedger() {
        Merchant withLedger = merchant(UUID.randomUUID(), "0.00");
        Merchant withoutLedger = merchant(UUID.randomUUID(), "10.00");
        when(merchantRepository.findAll()).thenReturn(List.of(withLedger, withoutLedger));
        when(ledgerRepository.existsByMerchantId(withLedger.id())).thenReturn(true);

        runner.run(mock(ApplicationArguments.class));

        ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(ledgerRepository).save(captor.capture());
        WalletLedgerEntry entry = captor.getValue();
        assertThat(entry.merchantId()).isEqualTo(withoutLedger.id());
        assertThat(entry.type()).isEqualTo(WalletLedgerEntryType.OPENING_BALANCE);
        assertThat(entry.amount().amount()).isEqualByComparingTo("10.00");
        assertThat(entry.balanceAfter().amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void logsWhenOpeningBalanceCreated() {
        when(merchantRepository.findAll()).thenReturn(List.of(merchant(UUID.randomUUID(), "5.00")));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            runner.run(mock(ApplicationArguments.class));

            String logs = logs(appender);
            assertThat(logs).contains("Opening balance do ledger criado para 1 lojista");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void silentWhenNothingCreated() {
        // Todos já têm ledger → created=0 → o log NÃO deve aparecer (L58 boundary/negate).
        Merchant m = merchant(UUID.randomUUID(), "0.00");
        when(merchantRepository.findAll()).thenReturn(List.of(m));
        when(ledgerRepository.existsByMerchantId(m.id())).thenReturn(true);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            runner.run(mock(ApplicationArguments.class));

            verify(ledgerRepository, never()).save(any());
            assertThat(logs(appender)).doesNotContain("Opening balance do ledger criado");
        } finally {
            detachAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(OpeningBalanceSeedRunner.class);
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String logs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(OpeningBalanceSeedRunner.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }
}
