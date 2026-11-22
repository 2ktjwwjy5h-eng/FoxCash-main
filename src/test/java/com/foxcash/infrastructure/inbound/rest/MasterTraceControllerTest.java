package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.TransactionTraceService;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.infrastructure.inbound.rest.dto.TransactionTraceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterTraceController} (Spec 125): delegação ao service,
 * mapeamento do DTO (cabeçalho + timeline), 404 e privacidade (nenhum campo
 * sensível — pixKey/QR — exposto no payload).
 */
class MasterTraceControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    private final TransactionTraceService traceService = mock(TransactionTraceService.class);
    private MasterTraceController controller;

    @BeforeEach
    void setUp() {
        controller = new MasterTraceController(traceService);
    }

    private PixTransaction approvedTx() {
        return PixTransaction.restore(TX_ID, MERCHANT_ID, Money.of("150.00"),
                "Cobranca", "v1:enc", "E0001", "code-psp-1", "qr-code", "copia-e-cola",
                TransactionStatus.APPROVED, NOW.minusSeconds(600), NOW.minusSeconds(60),
                NOW.minusSeconds(120), null, "cob-key-1", null, BOT_ID, null,
                "user-123", null, null, null);
    }

    private TransactionTraceService.TransactionTrace traceWith(PixTransaction tx) {
        return new TransactionTraceService.TransactionTrace(tx, List.of(
                new TransactionTraceService.TraceItem(NOW, TransactionTraceService.TraceStage.GATEWAY,
                        "CHARGE_CREATED", java.util.Map.of("amount", "150.00"))));
    }

    @Test
    void traceDelegatesRefAndMapsHeaderAndTimeline() {
        PixTransaction tx = approvedTx();
        when(traceService.trace("code-psp-1")).thenReturn(traceWith(tx));

        TransactionTraceResponse response = controller.trace("code-psp-1");

        verify(traceService).trace("code-psp-1");
        assertThat(response.transactionId()).isEqualTo(TX_ID);
        // Spec 127: o response expõe o traceId de negócio (== transactionId hoje) —
        // o fio condutor dos logs (MDC do worker) para o suporte.
        assertThat(response.traceId()).isEqualTo(TX_ID);
        assertThat(response.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.externalReference()).isEqualTo("code-psp-1");
        assertThat(response.endToEndId()).isEqualTo("E0001");
        assertThat(response.bot()).isEqualTo(new TransactionTraceResponse.BotRef(BOT_ID, "user-123"));
        assertThat(response.timeline()).hasSize(1);
        assertThat(response.timeline().get(0).stage()).isEqualTo("GATEWAY");
        assertThat(response.timeline().get(0).event()).isEqualTo("CHARGE_CREATED");
    }

    @Test
    void traceWithoutBotMapsNullBot() {
        PixTransaction tx = PixTransaction.restore(TX_ID, MERCHANT_ID, Money.of("150.00"),
                "Cobranca", null, null, "code-psp-1", null, null,
                TransactionStatus.APPROVED, NOW.minusSeconds(600), NOW.minusSeconds(60),
                NOW.minusSeconds(120), null, "cob-key-1", null, null, null,
                null, null, null, null);
        when(traceService.trace(TX_ID.toString())).thenReturn(traceWith(tx));

        TransactionTraceResponse response = controller.trace(TX_ID.toString());

        assertThat(response.bot()).isNull();
        assertThat(response.profileId()).isNull();
    }

    @Test
    void traceResponseDoesNotExposeSensitiveChargeFields() {
        // Privacidade (D7): o payload NÃO serializa pixKey/pixQrCode/pixCopyPaste/
        // buyerIp/buyerDevice — campos ausentes do record do DTO.
        assertThat(TransactionTraceResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("pixKey", "pixQrCode", "pixCopyPaste", "buyerIp", "buyerDevice");
    }

    @Test
    void unknownRefPropagatesNotFound() {
        when(traceService.trace("nao-existe")).thenThrow(new TransactionNotFoundException("nao-existe"));

        assertThatThrownBy(() -> controller.trace("nao-existe"))
                .isInstanceOf(TransactionNotFoundException.class);
    }
}
