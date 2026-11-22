package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Roteador de PIX de saída por prioridade (Spec 92 — fallback simples, sem
 * circuito breaker nesta spec; o registry de saúde da Spec 91 pode ser plugado
 * depois). Implementa {@link PayoutProviderClient} e é o bean {@code @Primary}:
 * todos os provedores de saída são beans (Spec 92) e o router decide a ordem.
 *
 * <p><b>Ordem:</b> com {@code foxcash.psp.payout-routing.enabled=true}, a lista
 * {@code payout-routing.providers} (ex.: "pagarme,asaas"); senão, apenas o
 * {@code payout-provider} configurado (comportamento atual). Provedor listado
 * sem bean → fail-fast na construção (config quebrada não silencia).
 *
 * <p><b>createPayout:</b> tenta os provedores em ordem; falha **incerta**
 * (timeout/5xx — {@code PayoutProviderTimeoutException}/{@code PayoutProviderException})
 * → tenta o próximo; erro definitivo (validação/estado — outra exceção) →
 * propaga. Todos falham → última exceção (o fluxo do saque mantém PROCESSING +
 * reconciliação da Spec 36).
 *
 * <p><b>getPayout:</b> multi-get — tenta cada provedor na ordem de prioridade
 * até um reconhecê-lo (as referências são opacas por adquirente; o provedor
 * errado lança {@code PayoutProviderException} de "não encontrado"). Usado
 * pela reconciliação (Spec 36) — o provedor que criou o saque devolve o estado.
 *
 * <p><b>Freeze não é roteável:</b> se o provedor bloqueou a conta (403/400 de
 * permissão/saldo), o fallback para outro NÃO move o dinheiro (está no PSP
 * congelado) — a defesa é o sweep (Spec 92), não este router.
 */
@Component
@Primary
public class PayoutProviderRouter implements PayoutProviderClient {

    private static final Logger log = LoggerFactory.getLogger(PayoutProviderRouter.class);

    private final List<OrderedProvider> orderedProviders;
    private final MeterRegistry meterRegistry;

    /** Provedor + nome de roteamento (chave da config, ex.: "pagarme"). */
    private record OrderedProvider(PayoutProviderClient client, String name) {
    }

    public PayoutProviderRouter(Map<String, PayoutProviderClient> providers,
                                PspProperties properties,
                                MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        List<String> keys = routingKeys(properties);
        this.orderedProviders = new ArrayList<>(keys.size());
        for (String key : keys) {
            PayoutProviderClient provider = findProvider(providers, key);
            if (provider == null) {
                throw new IllegalStateException(
                        "PayoutProviderRouter: provedor de saque '" + key
                                + "' listado na config nao tem adapter (beans: "
                                + String.join(", ", providers.keySet()) + ")");
            }
            orderedProviders.add(new OrderedProvider(provider, key));
        }
        if (orderedProviders.isEmpty()) {
            throw new IllegalStateException(
                    "PayoutProviderRouter: nenhum provedor de saque configurado (payout-provider ou payout-routing.providers)");
        }
        log.info("PayoutProviderRouter: ordem de provedores de saida = {}",
                orderedProviders.stream().map(OrderedProvider::name).toList());
    }

    private static List<String> routingKeys(PspProperties properties) {
        if (properties.isPayoutRoutingEnabled()) {
            String raw = properties.getPayoutRoutingProviders();
            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException(
                        "PayoutProviderRouter: payout-routing.enabled=true exige payout-routing.providers (ex.: pagarme,asaas)");
            }
            return java.util.Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .toList();
        }
        return List.of(properties.getPayoutProvider().toLowerCase());
    }

    /** Deriva a chave do provedor a partir do nome do bean ({@code xxxPayoutProviderClient}). */
    private static PayoutProviderClient findProvider(Map<String, PayoutProviderClient> providers, String key) {
        for (Map.Entry<String, PayoutProviderClient> e : providers.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String beanName = e.getKey().toLowerCase();
            // Nome do bean: mockPayoutProviderClient / pagarMePayoutProviderClient / asaasPayoutProviderClient
            if (beanName.startsWith(key) && beanName.contains("payoutproviderclient")) {
                return e.getValue();
            }
            // Fallback: casa exata do nome da classe simples
            if (e.getValue().getClass().getSimpleName().toLowerCase().startsWith(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    @Override
    public PayoutResult createPayout(PayoutCommand command) {
        RuntimeException last = null;
        for (OrderedProvider candidate : orderedProviders) {
            PayoutProviderClient provider = candidate.client();
            try {
                PayoutResult result = provider.createPayout(command);
                meterRegistry.counter("foxcash_psp_payout_total", "provider", candidate.name(),
                        "outcome", "ok").increment();
                // Spec 92 (roteamento pós-fallback): o provedor que ACEITOU é
                // gravado no resultado — o caller persiste em
                // withdrawals.payout_provider (reconciliação/webhook roteiam
                // por ele; o externalReference sozinho não identifica o PSP).
                return new PayoutResult(result.externalReference(), candidate.name());
            } catch (PayoutProviderException e) {
                // Falha incerta (timeout/5xx — inclui PayoutProviderTimeoutException,
                // que é subclasse) → tenta o próximo; freeze/permissão também cai
                // aqui (o sweep é a defesa, o router tenta mesmo assim).
                last = e;
                meterRegistry.counter("foxcash_psp_payout_total", "provider", candidate.name(),
                        "outcome", "fallback").increment();
                log.warn("PayoutProviderRouter: provedor falhou ao criar payout (saque={}) — tentando proximo: {}",
                        command.withdrawalId(), e.getMessage());
            }
        }
        log.error("PayoutProviderRouter: todos os provedores falharam para saque={}", command.withdrawalId());
        throw last != null ? last : new PayoutProviderException("PayoutProviderRouter: sem provedor de saida");
    }

    @Override
    public PayoutStatus getPayout(String externalReference) {
        RuntimeException last = null;
        for (OrderedProvider candidate : orderedProviders) {
            PayoutProviderClient provider = candidate.client();
            try {
                PayoutStatus status = provider.getPayout(externalReference);
                meterRegistry.counter("foxcash_psp_payout_total", "provider", candidate.name(),
                        "outcome", "status").increment();
                return status;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last != null ? last : new PayoutProviderException(
                "PayoutProviderRouter: nenhum provedor reconheceu o payout " + externalReference);
    }
}
