package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.infrastructure.outbound.pix.MockPixProviderClient;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controle de simulação dos mocks Pix (Spec 91 — E2E), **somente no perfil dev**
 * ({@code @Profile("dev")}): o {@code psp-fallback.spec.js} liga/desliga a falha
 * do provedor A ({@code mock}) em runtime para os cenários de fallback
 * (5xx/timeout/poll anti-dupla-cobrança) SEM afetar os demais specs.
 *
 * <p>Em produção o controller NÃO existe (perfil prod) → {@code 404} (o matcher
 * {@code /internal/psp-mock/**} permitAll do SecurityConfig não expõe nada).
 *
 * <pre>
 *   POST /internal/psp-mock/mock/simulation  {"mode":"FAILURE"}  // 5xx incerto
 *   POST /internal/psp-mock/mock/simulation  {"mode":"TIMEOUT_AFTER_CREATE"}
 *   POST /internal/psp-mock/mock/simulation  {"mode":"NORMAL"}   // reset
 * </pre>
 */
@RestController
@RequestMapping("/internal/psp-mock")
@Profile("dev")
public class PspMockControlController {

    private final Map<String, MockPixProviderClient> mocks;

    public PspMockControlController(Map<String, MockPixProviderClient> mocks) {
        this.mocks = mocks;
    }

    /** Modo de simulação de um provedor mock (ex.: "mock", "suitpay"). */
    public record SimulationRequest(MockPixProviderClient.Simulation mode) {
    }

    @PostMapping("/{provider}/simulation")
    public void setSimulation(@PathVariable String provider, @RequestBody SimulationRequest request) {
        MockPixProviderClient mock = mocks.get(provider + "PixProviderClient");
        if (mock == null) {
            throw new IllegalArgumentException("mock desconhecido: " + provider
                    + " (disponíveis: " + mocks.keySet() + ")");
        }
        mock.setSimulation(request.mode());
    }
}
