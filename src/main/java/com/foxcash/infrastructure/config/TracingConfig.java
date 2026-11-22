package com.foxcash.infrastructure.config;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;

/**
 * Exporter de spans condicional (Spec 57, Etapa 1 — pos-incidente Fase 2A).
 *
 * <p>Sem {@code FOXCASH_OTLP_ENDPOINT} (producao nao tem collector), usa um exporter
 * NO-OP: o {@code OtlpAutoConfiguration} do Spring e excluido no
 * {@code application.yml} para NUNCA tentar {@code localhost:4318} — que spammava
 * erro a cada ~20-40s e, quando a fila do batch span processor enchia (collector
 * inacessivel + {@code exportWhenQueueFull} bloqueando o thread que finaliza o
 * span), era o suspeito do hang do dispatcher logo apos cada deploy.
 *
 * <p>traceId/spanId continuam no MDC dos logs (o tracer permanece ativo); so o
 * ENVIO de spans muda. Com {@code FOXCASH_OTLP_ENDPOINT} configurado, o exporter
 * OTLP real e criado (collector futuro).
 */
@Configuration
public class TracingConfig {

    @Bean
    public SpanExporter spanExporter(@Value("${FOXCASH_OTLP_ENDPOINT:}") String otlpEndpoint) {
        if (otlpEndpoint == null || otlpEndpoint.isBlank()) {
            return new NoopSpanExporter();
        }
        return io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();
    }

    /** SpanExporter que nao envia nada — sem rede, sem erro, sem blocking. */
    static final class NoopSpanExporter implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
