package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link QueryWalletLedgerService} (Spec 27): repassa o escopo do
 * lojista + filtros/paginação ao repositório e devolve o PageResult.
 */
class QueryWalletLedgerServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-31T23:59:59Z");

    private final WalletLedgerRepository repository = mock(WalletLedgerRepository.class);
    private final QueryWalletLedgerService service = new QueryWalletLedgerService(repository);

    @Test
    void delegatesScopeFiltersAndPaginationToRepository() {
        WalletLedgerEntry entry = WalletLedgerEntry.record(MERCHANT_ID, WalletLedgerEntryType.SALE_CREDIT,
                Money.of("150.00"), Money.of("150.00"), "TRANSACTION", UUID.randomUUID(), FROM);
        PageResult<WalletLedgerEntry> page =
                new PageResult<>(List.of(entry), 0, 20, 1, 1);
        when(repository.findByMerchantId(MERCHANT_ID, WalletLedgerEntryType.SALE_CREDIT,
                FROM, TO, 0, 20)).thenReturn(page);

        PageResult<WalletLedgerEntry> result = service.findByMerchantId(
                MERCHANT_ID, WalletLedgerEntryType.SALE_CREDIT, FROM, TO, 0, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).type()).isEqualTo(WalletLedgerEntryType.SALE_CREDIT);
        assertThat(result.totalElements()).isEqualTo(1);
        verify(repository).findByMerchantId(MERCHANT_ID, WalletLedgerEntryType.SALE_CREDIT,
                FROM, TO, 0, 20);
    }

    @Test
    void passesNullFiltersThroughWhenAbsent() {
        when(repository.findByMerchantId(MERCHANT_ID, null, null, null, 2, 50))
                .thenReturn(new PageResult<>(List.of(), 2, 50, 0, 0));

        service.findByMerchantId(MERCHANT_ID, null, null, null, 2, 50);

        verify(repository).findByMerchantId(MERCHANT_ID, null, null, null, 2, 50);
    }
}
