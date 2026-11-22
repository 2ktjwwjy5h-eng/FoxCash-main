package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.audit.AuditSearchQuery;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.audit.MasterAuditEntry;
import com.foxcash.domain.port.out.MasterAuditRepository;
import com.foxcash.infrastructure.inbound.rest.dto.AuditEntryResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * Testes do adaptador REST da trilha de auditoria do master (Spec 37): filtros
 * (merchantId/action/actorId/período), paginação e 400 {@code invalid_argument}
 * em parâmetro inválido. Sem rotas de mutação (imutável).
 */
class MasterAuditControllerTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final MasterAuditRepository repository = mock(MasterAuditRepository.class);
    private MasterAuditController controller;

    @BeforeEach
    void setUp() {
        controller = new MasterAuditController(repository);
    }

    private MasterAuditEntry entry() {
        return MasterAuditEntry.record(ACTOR_ID, MasterAuditAction.WITHDRAWALS_BLOCK,
                MERCHANT_ID, "MERCHANT", MERCHANT_ID,
                "{\"withdrawalsBlocked\":false}", "{\"withdrawalsBlocked\":true}",
                null, "192.168.1.10", Instant.now());
    }

    @Test
    void searchPassesFiltersAndPaginationToRepository() {
        when(repository.search(any(AuditSearchQuery.class))).thenReturn(
                PageResult.of(List.of(entry()), 0, 20, 1));

        PageResponse<AuditEntryResponse> response = controller.search(
                MERCHANT_ID.toString(), "WITHDRAWALS_BLOCK", ACTOR_ID.toString(),
                "2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z", 0, 20);

        ArgumentCaptor<AuditSearchQuery> captor = ArgumentCaptor.forClass(AuditSearchQuery.class);
        verify(repository).search(captor.capture());
        AuditSearchQuery query = captor.getValue();
        assertThat(query.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(query.action()).isEqualTo(MasterAuditAction.WITHDRAWALS_BLOCK);
        assertThat(query.actorId()).isEqualTo(ACTOR_ID);
        assertThat(query.from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(query.to()).isEqualTo(Instant.parse("2026-08-31T23:59:59Z"));
        assertThat(query.page()).isEqualTo(0);
        assertThat(query.size()).isEqualTo(20);

        assertThat(response.items()).hasSize(1);
        AuditEntryResponse item = response.items().get(0);
        assertThat(item.actorId()).isEqualTo(ACTOR_ID);
        assertThat(item.action()).isEqualTo("WITHDRAWALS_BLOCK");
        assertThat(item.before()).isEqualTo("{\"withdrawalsBlocked\":false}");
        assertThat(item.after()).isEqualTo("{\"withdrawalsBlocked\":true}");
    }

    @Test
    void searchWithInvalidActionThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.search(null, "INVALIDO", null, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchWithInvalidMerchantIdThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.search("nao-e-uuid", null, null, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
