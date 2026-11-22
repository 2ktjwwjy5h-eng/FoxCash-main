package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.domain.port.in.WalletReconciliationUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ReconciliationIssueResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ReconciliationRunResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST de reconciliação (Spec 30): run retorna o resumo e
 * issues paginado com filtros — parâmetros inválidos viram 400
 * {@code invalid_argument} (o 401/403 de autenticação é do SecurityConfig).
 */
class ReconciliationControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final WalletReconciliationUseCase reconciliation = mock(WalletReconciliationUseCase.class);
    private ReconciliationController controller;

    @BeforeEach
    void setUp() {
        controller = new ReconciliationController(reconciliation);
    }

    @Test
    void runReturnsSummary() {
        when(reconciliation.run()).thenReturn(new WalletReconciliationUseCase.ReconciliationReport(5, 4, 1, 2));

        ReconciliationRunResponse response = controller.run();

        assertThat(response.checked()).isEqualTo(5);
        assertThat(response.ok()).isEqualTo(4);
        assertThat(response.issues()).isEqualTo(1);
        assertThat(response.orphanCharges()).isEqualTo(2);
    }

    @Test
    void issuesReturnsPagedResponse() {
        ReconciliationIssue issue = new ReconciliationIssue(
                UUID.randomUUID(), MERCHANT_ID, Money.of("100.00"), Money.of("150.00"),
                ReconciliationIssueStatus.OPEN, Instant.now(), null);
        when(reconciliation.findIssues(eq(MERCHANT_ID), eq(ReconciliationIssueStatus.OPEN), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(issue), 0, 20, 1));

        PageResponse<ReconciliationIssueResponse> response =
                controller.issues(MERCHANT_ID.toString(), "open", 0, 20);

        assertThat(response.items()).hasSize(1);
        ReconciliationIssueResponse item = response.items().get(0);
        assertThat(item.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(item.expectedBalance()).isEqualByComparingTo("100.00");
        assertThat(item.actualBalance()).isEqualByComparingTo("150.00");
        assertThat(item.status()).isEqualTo("OPEN");
    }

    @Test
    void invalidMerchantIdIsRejected() {
        assertThatThrownBy(() -> controller.issues("nao-eh-uuid", null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidStatusIsRejected() {
        assertThatThrownBy(() -> controller.issues(MERCHANT_ID.toString(), "hacker", 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuesWithoutFiltersPassesNulls() {
        when(reconciliation.findIssues(eq(null), eq(null), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        PageResponse<ReconciliationIssueResponse> response = controller.issues(null, null, 0, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }
}
