package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PspBalanceProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato do adapter de saldo da conta master (Spec 92): todo adapter que
 * implementa {@link PspBalanceProvider} deve HERDAR este teste — os invariantes
 * do contrato são verificados automaticamente quando o adapter for criado.
 *
 * <p>O adapter real do adquirente (Pagar.me/Asaas) estende esta classe com um
 * {@code MockWebServer} do provedor (padrão do {@code PagarMePixProviderClientTest})
 * e implementa {@link #client()} — sem poder "furar" o contrato sem que a
 * suíte acuse. Falhas de rede/timeout são cobertas nos testes de cada adapter
 * (o mock nunca falha).
 */
public abstract class PspBalanceContractTest {

    /** Adapter sob teste (configuração normal). */
    protected abstract PspBalanceProvider client();

    @Test
    void getBalanceDevolveAvailableNaoNulo() {
        // available é o que o sweep transfere — nunca null (sempre BRL ≥ 0).
        PspBalanceProvider.PspBalance balance = client().getBalance();
        assertThat(balance.available()).isNotNull();
    }

    @Test
    void getBalanceDevolveCurrencyBRL() {
        // Currency fixa (ISO 4217) — o sweep opera em reais.
        PspBalanceProvider.PspBalance balance = client().getBalance();
        assertThat(balance.currency()).isEqualTo("BRL");
    }
}
