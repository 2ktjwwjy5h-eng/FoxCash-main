package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.master.MerchantPanelSearchQuery;
import com.foxcash.domain.model.master.MerchantPanelTransactionQuery;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.port.in.MerchantPanelQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelDetail;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelItem;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelTransaction;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantStoreResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterPanelController} (Spec 97): delegacao dos endpoints
 * novos com parsing de filtros (status/q/from/to/risk), paginacao com cap 100
 * e 400 {@code invalid_argument} em parametro invalido.
 */
class MasterPanelControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final MerchantPanelQueryUseCase panelQuery = mock(MerchantPanelQueryUseCase.class);
    private MasterPanelController controller;

    @BeforeEach
    void setUp() {
        controller = new MasterPanelController(panelQuery);
    }

    @Test
    void panelParsesFiltersAndClampsSize() {
        when(panelQuery.searchPanel(any())).thenReturn(PageResult.of(List.of(), 0, 100, 0));

        PageResponse<MerchantPanelItem> response = controller.panel(
                "active", "ramon", "2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z",
                "high", 0, 500);

        ArgumentCaptor<MerchantPanelSearchQuery> captor =
                ArgumentCaptor.forClass(MerchantPanelSearchQuery.class);
        verify(panelQuery).searchPanel(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(captor.getValue().query()).isEqualTo("ramon");
        assertThat(captor.getValue().from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(captor.getValue().to()).isEqualTo(Instant.parse("2026-08-31T23:59:59Z"));
        assertThat(captor.getValue().risk()).isEqualTo(MerchantPanelSearchQuery.RiskLevel.HIGH);
        assertThat(captor.getValue().size()).isEqualTo(100);
        assertThat(response.page()).isZero();
    }

    @Test
    void panelWithInvalidStatusThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.panel("INVALIDO", null, null, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'status'");
    }

    @Test
    void panelWithInvalidFromThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.panel(null, null, "ontem", null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'from'");
    }

    @Test
    void panelWithInvalidRiskThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.panel(null, null, null, null, "altissimo", 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'risk'");
    }

    @Test
    void panelDetailDelegates() {
        MerchantPanelDetail expected = MerchantPanelDetail.of(
                new MerchantPanelDetail.PanelKpis(BigDecimal.TEN, BigDecimal.TEN, 1, BigDecimal.TEN, 1),
                new MerchantPanelDetail.PanelAccount("Ramon", "r@x.com", null, "529.***.***-25", null, Instant.now()),
                new MerchantPanelDetail.PanelFeesAndLimits(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ZERO, 0, null, null, null, null));
        when(panelQuery.panelDetail(MERCHANT_ID)).thenReturn(expected);

        MerchantPanelDetail result = controller.panelDetail(MERCHANT_ID);

        assertThat(result.account().documentMasked()).isEqualTo("529.***.***-25");
        verify(panelQuery).panelDetail(MERCHANT_ID);
    }

    @Test
    void transactionsParsesFilters() {
        when(panelQuery.transactions(any(), any())).thenReturn(PageResult.of(List.of(), 0, 20, 0));

        controller.transactions(MERCHANT_ID, "approved", "2026-08-01T00:00:00Z", null, 1, 20);

        ArgumentCaptor<MerchantPanelTransactionQuery> captor =
                ArgumentCaptor.forClass(MerchantPanelTransactionQuery.class);
        verify(panelQuery).transactions(any(), captor.capture());
        assertThat(captor.getValue().status().name()).isEqualTo("APPROVED");
        assertThat(captor.getValue().from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(captor.getValue().page()).isEqualTo(1);
    }

    @Test
    void transactionsWithInvalidStatusThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.transactions(MERCHANT_ID, "FOO", null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'status'");
    }

    @Test
    void storesDelegates() {
        MerchantStoreResponse store = new MerchantStoreResponse(UUID.randomUUID(), "Loja X", "Título",
                "#FF0000", "🔥", "desc", null, null, Instant.now(), Instant.now());
        when(panelQuery.stores(MERCHANT_ID)).thenReturn(List.of(store));

        List<MerchantStoreResponse> result = controller.stores(MERCHANT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Loja X");
    }
}
