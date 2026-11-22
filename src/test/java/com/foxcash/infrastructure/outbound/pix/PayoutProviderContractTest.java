package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PayoutProviderClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato do adapter de PIX de saída (Spec 38): todo adapter que implementa
 * {@link PayoutProviderClient} deve HERDAR este teste — os invariantes do
 * contrato são verificados automaticamente quando o adapter for criado.
 *
 * <p>O adapter real do adquirente (spec futura, ver runbook
 * {@code deploy/payout-homologation.md}) estende esta classe com um
 * {@code MockWebServer} do provedor (padrão do {@code PagarMePixProviderClientTest})
 * e implementa {@link #client()} e {@link #failingClient()} — sem poder "furar"
 * o contrato sem que a suíte acuse.
 */
public abstract class PayoutProviderContractTest {

    /** Adapter sob teste (configuração normal). */
    protected abstract PayoutProviderClient client();

    /** Adapter configurado para falha síncrona (ex.: mock-payout-failure ou 5xx). */
    protected abstract PayoutProviderClient failingClient();

    /** Command válido para o adapter. */
    protected abstract PayoutProviderClient.PayoutCommand command();

    @Test
    void aceiteDevolveExternalReferenceNaoVazia() {
        // createPayout deve devolver a referência externa do PSP (usada no
        // webhook de saída e na reconciliação) — nunca vazia.
        PayoutProviderClient.PayoutResult result = client().createPayout(command());
        assertThat(result.externalReference()).isNotBlank();
    }

    @Test
    void consultaDevolveEstadoCanonico() {
        // getPayout deve devolver o estado CANONICO (PAID/FAILED/PENDING) —
        // o adapter mapeia o vocabulário do PSP; nunca null.
        PayoutProviderClient.PayoutStatus status = client().getPayout("PO_REF");
        assertThat(PayoutProviderClient.PayoutStatus.values()).contains(status);
    }

    @Test
    void falhaSincronaLancaExcecao() {
        // Falha síncrona (timeout/4xx/5xx) do PSP deve lançar exceção — o fluxo
        // de saque faz rollback (nada é criado/debitado).
        assertThatThrownBy(() -> failingClient().createPayout(command()))
                .isInstanceOf(RuntimeException.class);
    }
}
