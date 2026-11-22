package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.infrastructure.security.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PlatformActivityRecorder} (Spec 41): o {@code details} é
 * serializado com máscara de dados sensíveis (nunca em claro na trilha) e o
 * {@code ip} fica null quando não há request (jobs/SYSTEM).
 */
class PlatformActivityRecorderTest {

    private final PlatformActivityRepository repository = mock(PlatformActivityRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);

    private PlatformActivityRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new PlatformActivityRecorder(repository, objectMapper, clientIpResolver);
        when(repository.save(any(PlatformActivity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void detailsMaskSensitiveValues() {
        PlatformActivity activity = recorder.record(
                PlatformActorType.MERCHANT, UUID.randomUUID(), PlatformAction.CHARGE_CREATED,
                "TRANSACTION", UUID.randomUUID(), UUID.randomUUID(),
                Map.of("amount", "150.00", "document", "52998224725",
                        "destinationKey", "pix@loja.com", "email", "loja@example.com"));

        assertTrue(activity.details().contains("\"amount\":\"150.00\""));
        assertTrue(activity.details().contains("\"document\":\"***\""));
        assertTrue(activity.details().contains("\"destinationKey\":\"***\""));
        assertTrue(activity.details().contains("\"email\":\"***\""));
    }

    @Test
    void ipIsNullOutsideRequestContext() {
        // Sem request ativo (job/SYSTEM) → o recorder grava ip null.
        PlatformActivity activity = recorder.record(
                PlatformActorType.SYSTEM, null, PlatformAction.INVOICE_COLLECTED,
                "INVOICE", UUID.randomUUID(), UUID.randomUUID(), Map.of("debit", "10.00"));

        assertNull(activity.ip());
    }

    @Test
    void nullDetailsSerializesToNull() {
        // L84: details null → trilha sem contexto (mutante devolveria "").
        PlatformActivity activity = recorder.record(
                PlatformActorType.SYSTEM, null, PlatformAction.INVOICE_COLLECTED,
                "INVOICE", UUID.randomUUID(), UUID.randomUUID(), null);

        assertNull(activity.details());
    }

    @Test
    void scalarDetailsPassThroughUnmasked() {
        // L111: mask() só transforma Map/Collection — escalares passam intactos
        // (mutante NullReturnVals converteria "plain-text" em "null").
        PlatformActivity activity = recorder.record(
                PlatformActorType.SYSTEM, null, PlatformAction.INVOICE_COLLECTED,
                "INVOICE", UUID.randomUUID(), UUID.randomUUID(), "plain-text");

        assertTrue(activity.details().contains("plain-text"));
    }

    @Test
    void collectionDetailsMaskRecursively() {
        // L109: mask() de Collection mapeia cada elemento (mutante NullReturnVals
        // converteria a lista inteira em "null").
        PlatformActivity activity = recorder.record(
                PlatformActorType.SYSTEM, null, PlatformAction.INVOICE_COLLECTED,
                "INVOICE", UUID.randomUUID(), UUID.randomUUID(),
                java.util.List.of(Map.of("document", "52998224725"), "plain"));

        assertTrue(activity.details().contains("\"document\":\"***\""));
        assertTrue(activity.details().contains("plain"));
    }

    @Test
    void ipResolvedFromCurrentRequest() {
        // L76/L77: com request ativo, o ip é resolvido do request (não fica null).
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("203.0.113.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            PlatformActivity activity = recorder.record(
                    PlatformActorType.MERCHANT, UUID.randomUUID(), PlatformAction.CHARGE_CREATED,
                    "TRANSACTION", UUID.randomUUID(), UUID.randomUUID(), Map.of("amount", "150.00"));

            assertThat(activity.ip()).isEqualTo("203.0.113.7");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
