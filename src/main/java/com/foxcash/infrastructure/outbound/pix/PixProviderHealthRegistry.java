package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.application.service.CircuitOpenAlertPublisher;
import com.foxcash.infrastructure.config.RoutingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Circuit breaker preventivo por provedor de cobrança (Spec 91 — D5), em memória
 * (padrão Spec 64):
 *
 * <ul>
 *   <li><b>Janela de falhas</b> — contador de {@code PixProviderTimeoutException}/
 *       5xx por provedor em {@code window-seconds} (ex.: 5 falhas em 60s) →
 *       circuito {@code OPEN} por {@code cooldown-seconds} (ex.: 5 min) →
 *       {@code HALF-OPEN} (deixa 1 passar; sucesso fecha, falha reabre);</li>
 *   <li><b>Kill-switch manual</b> — {@code foxcash.psp.routing.circuit-breaker.providers.<p>.enabled=false}
 *       → provedor fora da rota (mesmo efeito de circuito aberto);</li>
 *   <li><b>Input de chargeback (Spec 16/19)</b> — {@code openForMerchant}: abre o
 *       circuito POR LOJISTA (desvia só o volume dele; o restante segue no primário);</li>
 *   <li><b>Alerta</b> — ao abrir, publica {@code PROVIDER_CIRCUIT_OPEN} no outbox
 *       (transação própria, payload mascarado, fail-safe);</li>
 *   <li><b>Métricas</b> — {@code foxcash_psp_circuit_state{provider}} (gauge) e
 *       {@code foxcash_psp_circuit_total{provider}} (contador de aberturas).</li>
 * </ul>
 */
@Component
public class PixProviderHealthRegistry {

    private static final Logger log = LoggerFactory.getLogger(PixProviderHealthRegistry.class);

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Estado do circuito de um provedor. */
    private static final class Circuit {
        final Deque<Instant> failures = new ArrayDeque<>();
        State state = State.CLOSED;
        Instant openedAt;
    }

    private final RoutingProperties routingProperties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final CircuitOpenAlertPublisher alertPublisher;

    /** Circuito por provedor. */
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

    /** Circuito aberto POR LOJISTA (input de chargeback) — provider → merchants. */
    private final Map<String, Set<UUID>> merchantOpen = new ConcurrentHashMap<>();

    public PixProviderHealthRegistry(RoutingProperties routingProperties,
                                     Clock clock,
                                     MeterRegistry meterRegistry,
                                     CircuitOpenAlertPublisher alertPublisher) {
        this.routingProperties = routingProperties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.alertPublisher = alertPublisher;
    }

    /**
     * true se o provedor deve ser PULADO na rota: kill-switch desligado, circuito
     * OPEN dentro do cooldown, ou HALF_OPEN sem probe disponível.
     */
    public boolean shouldSkip(String provider) {
        if (!killSwitchEnabled(provider)) {
            return true;
        }
        Circuit circuit = circuit(provider);
        Instant now = clock.instant();
        if (circuit.state == State.OPEN && !now.isAfter(circuit.openedAt.plusSeconds(cooldownSeconds()))) {
            return true;
        }
        if (circuit.state == State.OPEN) {
            // Cooldown expirou → HALF-OPEN: a próxima tentativa decide (sucesso
            // fecha; falha reabre). Enquanto HALF_OPEN, deixa passar.
            circuit.state = State.HALF_OPEN;
        }
        return false;
    }

    /** Circuito aberto por lojista (chargeback — desvia só o volume dele). */
    public boolean shouldSkipForMerchant(String provider, UUID merchantId) {
        Set<UUID> openMerchants = merchantOpen.get(provider);
        return openMerchants != null && openMerchants.contains(merchantId);
    }

    /** Sucesso do provedor: fecha (se HALF_OPEN) e limpa a janela de falhas. */
    public void recordSuccess(String provider) {
        Circuit circuit = circuit(provider);
        if (circuit.state == State.HALF_OPEN) {
            circuit.state = State.CLOSED;
            log.info("PixProviderHealthRegistry: circuito do provedor '{}' FECHOU (probe ok)", provider);
        }
        circuit.failures.clear();
    }

    /** Falha do provedor (timeout/5xx): conta na janela; abre se atingir o limiar. */
    public void recordFailure(String provider) {
        Circuit circuit = circuit(provider);
        Instant now = clock.instant();
        if (circuit.state == State.HALF_OPEN) {
            // Probe falhou → reabre o circuito.
            open(circuit, provider, now, "half-open probe falhou");
            return;
        }
        if (circuit.state == State.CLOSED) {
            prune(circuit, now);
            circuit.failures.addLast(now);
            if (circuit.failures.size() >= failureThreshold()) {
                open(circuit, provider, now, "janela de falhas atingida ("
                        + failureThreshold() + " em " + windowSeconds() + "s)");
            }
        }
    }

    /** Input de chargeback (Spec 16/19): abre o circuito POR LOJISTA. */
    public void openForMerchant(UUID merchantId, String provider) {
        merchantOpen.computeIfAbsent(provider, k -> ConcurrentHashMap.newKeySet()).add(merchantId);
        log.warn("PixProviderHealthRegistry: circuito do provedor '{}' aberto para o lojista {} "
                + "(chargeback acima do limiar)", provider, merchantId);
    }

    /** Estado atual do circuito (métrica/debug). */
    public State state(String provider) {
        return circuit(provider).state;
    }

    /**
     * Nome do estado atual do circuito (Spec 107 — visão de gateways do portal):
     * {@code CLOSED}/{@code OPEN}/{@code HALF_OPEN}. String estável (o enum
     * {@link State} é package-private) — sem provedor conhecido → {@code CLOSED}.
     */
    public String stateName(String provider) {
        return state(provider).name();
    }

    private void open(Circuit circuit, String provider, Instant now, String reason) {
        circuit.state = State.OPEN;
        circuit.openedAt = now;
        circuit.failures.clear();
        meterRegistry.counter("foxcash_psp_circuit_total", "provider", provider).increment();
        log.warn("PixProviderHealthRegistry: circuito do provedor '{}' ABRIU ({}) — volume desviado",
                provider, reason);
        alertPublisher.publishCircuitOpened(provider, reason);
    }

    private void prune(Circuit circuit, Instant now) {
        Instant windowStart = now.minusSeconds(windowSeconds());
        while (!circuit.failures.isEmpty() && circuit.failures.peekFirst().isBefore(windowStart)) {
            circuit.failures.removeFirst();
        }
    }

    private Circuit circuit(String provider) {
        return circuits.computeIfAbsent(provider, k -> new Circuit());
    }

    private boolean killSwitchEnabled(String provider) {
        RoutingProperties.ProviderCircuit config =
                routingProperties.getCircuitBreaker().getProviders().get(provider);
        return config == null || config.isEnabled();
    }

    private long windowSeconds() {
        return Math.max(1, routingProperties.getCircuitBreaker().getWindowSeconds());
    }

    private int failureThreshold() {
        return Math.max(1, routingProperties.getCircuitBreaker().getFailureThreshold());
    }

    private long cooldownSeconds() {
        return Math.max(1, routingProperties.getCircuitBreaker().getCooldownSeconds());
    }
}
