package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.audit.MasterAuditEntry;
import com.foxcash.domain.port.out.MasterAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterAuditRecorder} (Spec 37): grava UMA entrada com
 * before/after em JSON e dados sensiveis (documento, chave Pix, conta) NUNCA em
 * claro — mascarados como {@code ***}.
 */
class MasterAuditRecorderTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final MasterAuditRepository repository = mock(MasterAuditRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MasterAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new MasterAuditRecorder(repository, objectMapper);
    }

    @Test
    void recordsEntryWithMaskedSensitiveData() {
        when(repository.save(any(MasterAuditEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> before = Map.of(
                "withdrawalsBlocked", false,
                "document", "52998224725",
                "destinationKey", "pix@loja.com");
        Map<String, Object> after = Map.of(
                "withdrawalsBlocked", true,
                "document", "52998224725",
                "destinationKey", "pix@loja.com");

        recorder.record(ACTOR_ID, MasterAuditAction.WITHDRAWALS_BLOCK,
                MERCHANT_ID, "MERCHANT", MERCHANT_ID, before, after, null, "192.168.1.10");

        ArgumentCaptor<MasterAuditEntry> captor = ArgumentCaptor.forClass(MasterAuditEntry.class);
        verify(repository).save(captor.capture());
        MasterAuditEntry entry = captor.getValue();

        assertThat(entry.actorId()).isEqualTo(ACTOR_ID);
        assertThat(entry.action()).isEqualTo(MasterAuditAction.WITHDRAWALS_BLOCK);
        assertThat(entry.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(entry.reason()).isNull();
        assertThat(entry.ip()).isEqualTo("192.168.1.10");
        assertThat(entry.createdAt()).isNotNull();

        // before_json: withdrawalsBlocked preservado; documento/chave PIX mascarados.
        assertThat(entry.beforeJson()).contains("\"withdrawalsBlocked\":false");
        assertThat(entry.beforeJson()).doesNotContain("52998224725");
        assertThat(entry.beforeJson()).doesNotContain("pix@loja.com");
        assertThat(entry.beforeJson()).contains("\"document\":\"***\"");
        assertThat(entry.beforeJson()).contains("\"destinationKey\":\"***\"");
        assertThat(entry.afterJson()).contains("\"withdrawalsBlocked\":true");
    }

    @Test
    void recordsNullBeforeAfterAsNull() {
        when(repository.save(any(MasterAuditEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.record(ACTOR_ID, MasterAuditAction.INVOICE_COLLECT,
                MERCHANT_ID, "INVOICE", UUID.randomUUID(), null, null, null, null);

        ArgumentCaptor<MasterAuditEntry> captor = ArgumentCaptor.forClass(MasterAuditEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().beforeJson()).isNull();
        assertThat(captor.getValue().afterJson()).isNull();
    }

    @Test
    void recordReturnsSavedEntry() {
        // Mutation (NullReturnVals L62): record devolve a entrada salva (o
        // mutante trocaria o retorno por null).
        when(repository.save(any(MasterAuditEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        MasterAuditEntry result = recorder.record(ACTOR_ID, MasterAuditAction.RISK_ADJUSTMENT,
                MERCHANT_ID, "MERCHANT", MERCHANT_ID, Map.of("name", "Loja"), null, null, "10.0.0.1");

        assertThat(result).isNotNull();
        assertThat(result.actorId()).isEqualTo(ACTOR_ID);
    }

    @Test
    void masksSensitiveKeysInsideCollections() {
        // Mutation (NullReturnVals L92 — lambda do mask em Collection): listas
        // dentro do before/after são mascaradas recursivamente (mutante → null).
        when(repository.save(any(MasterAuditEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> before = Map.of(
                "pixKeys", java.util.List.of(Map.of("key", "pix@loja.com", "label", "Loja")));

        recorder.record(ACTOR_ID, MasterAuditAction.RISK_ADJUSTMENT,
                MERCHANT_ID, "MERCHANT", MERCHANT_ID, before, null, null, "10.0.0.1");

        ArgumentCaptor<MasterAuditEntry> captor = ArgumentCaptor.forClass(MasterAuditEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().beforeJson()).contains("\"key\":\"***\"");
        assertThat(captor.getValue().beforeJson()).contains("\"label\":\"Loja\"");
        assertThat(captor.getValue().beforeJson()).doesNotContain("pix@loja.com");
    }
}
