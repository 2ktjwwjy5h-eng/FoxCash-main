package com.foxcash.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do {@link AxiomHttpAppender} (Spec 95 — drain direto p/ Axiom):
 * lote JSON assíncrono com Bearer token + MDC e fail-open quando config ausente.
 */
class AxiomHttpAppenderTest {

    private final LoggerContext context = new LoggerContext();

    /** Evento com MDC pré-preenchido — o adapter MDC estático do SLF4J não é
     *  inicializado neste JVM de teste (surefire); em produção o appender
     *  captura o MDC do thread que logou via prepareForDeferredProcessing. */
    private ILoggingEvent event(String message, String requestId) {
        LoggingEvent evt = new LoggingEvent(
                "com.foxcash.test.Fqcn", context.getLogger("com.foxcash.test"),
                Level.INFO, message, null, null);
        Map<String, String> mdc = new HashMap<>();
        mdc.put("requestId", requestId);
        evt.setMDCPropertyMap(mdc);
        return evt;
    }

    @Test
    void enviaLoteJsonComBearerTokenEMdc() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"));
        server.start();

        try {
            AxiomHttpAppender appender = new AxiomHttpAppender();
            appender.setContext(context);
            appender.setName("AXIOM_TEST");
            appender.setUrl(server.url("/v1/datasets/foxcash-prod/ingest").toString());
            appender.setToken("test-token");
            appender.setBatchSize(2);

            LogstashEncoder encoder = new LogstashEncoder();
            encoder.setContext(context);
            encoder.start();
            appender.setEncoder(encoder);
            appender.start();
            assertTrue(appender.isStarted(), "appender deve iniciar com url+token válidos");

            appender.append(event("primeiro log", "req-1"));
            appender.append(event("segundo log", "req-2"));

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request, "worker deve POSTar o lote para o Axiom");

            assertEquals("Bearer test-token", request.getHeader("Authorization"));
            assertEquals("application/json", request.getHeader("Content-Type"));

            String body = request.getBody().readUtf8();
            assertTrue(body.startsWith("["), "corpo deve ser um array JSON de eventos");
            assertTrue(body.endsWith("]"));
            assertTrue(body.contains("primeiro log"), "evento 1 serializado no lote");
            assertTrue(body.contains("segundo log"), "evento 2 serializado no lote");
            assertTrue(body.contains("com.foxcash.test"), "logger_name presente no JSON");
            assertTrue(body.contains("\"requestId\":\"req-1\""), "MDC requestId do evento 1 no JSON");
            assertTrue(body.contains("\"requestId\":\"req-2\""), "MDC requestId do evento 2 no JSON");

            appender.stop();
        } finally {
            server.shutdown();
        }
    }

    @Test
    void failOpenQuandoTokenAusente() {
        AxiomHttpAppender appender = new AxiomHttpAppender();
        appender.setContext(context);
        appender.setName("AXIOM_TEST");
        appender.setUrl("https://api.axiom.co/api/v1/datasets/foxcash-prod/ingest");
        appender.setToken("");

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.start();
        appender.setEncoder(encoder);
        appender.start();

        assertFalse(appender.isStarted(), "sem token o appender deve falhar aberto (desligado)");
        // append() com appender desligado é no-op — não pode lançar nem travar.
        appender.append(event("não deve ir a lugar nenhum", "req-x"));
        appender.stop();
    }

    @Test
    void warnDeIngestComThrottle() throws Exception {
        MockWebServer server = new MockWebServer();
        // 3 lotes com erro 404 — o WARN de ingest deve aparecer UMA vez (throttle
        // de 5 min), não 3x.
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"code\":404,\"message\":\"dataset not found\"}"));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"code\":404,\"message\":\"dataset not found\"}"));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"code\":404,\"message\":\"dataset not found\"}"));
        server.start();

        try {
            AxiomHttpAppender appender = new AxiomHttpAppender();
            appender.setContext(context);
            appender.setName("AXIOM_THROTTLE");
            appender.setUrl(server.url("/v1/datasets/foxcash/ingest").toString());
            appender.setToken("test-token");
            appender.setBatchSize(1);

            LogstashEncoder encoder = new LogstashEncoder();
            encoder.setContext(context);
            encoder.start();
            appender.setEncoder(encoder);
            appender.start();
            assertTrue(appender.isStarted());

            // 3 lotes de 1 evento cada → 3 POSTs 404 → 1 único WARN (throttle).
            appender.append(event("erro 1", "req-1"));
            appender.append(event("erro 2", "req-2"));
            appender.append(event("erro 3", "req-3"));

            // Aguarda os 3 POSTs serem processados pelo worker.
            for (int i = 0; i < 3; i++) {
                RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(request, "worker deve POSTar o lote " + (i + 1));
            }

            // Pequena espera para o WARN chegar ao StatusManager.
            Thread.sleep(300);
            long warns = context.getStatusManager().getCopyOfStatusList().stream()
                    .filter(s -> s.getMessage() != null && s.getMessage().contains("Axiom ingest respondeu"))
                    .count();
            assertEquals(1, warns, "throttle: N erros contínuos devem gerar apenas 1 WARN de ingest");

            appender.stop();
        } finally {
            server.shutdown();
        }
    }
}
