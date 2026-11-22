package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link MockPixProviderClient} (Spec 28/91): referência externa no
 * formato endToEndId do Bacen, BR Code estruturalmente plausível, idempotência
 * por {@code transactionId}, timeout simulado na primeira tentativa e a consulta
 * {@code getChargeByCode} (Spec 91 — poll anti-dupla-cobrança).
 *
 * <p>Herda {@link PixProviderContractTest} — o contrato da porta é verificado
 * automaticamente (create + consulta + falhas).
 */
class MockPixProviderClientTest extends PixProviderContractTest {

    private final PspProperties properties = new PspProperties();
    private final MockPixProviderClient sharedClient = new MockPixProviderClient(properties);

    @Override
    protected PixProviderClient client() {
        // MESMA instância: o harness cria e consulta na mesma cobrança.
        return sharedClient;
    }

    @Override
    protected PixProviderClient failingClient() {
        MockPixProviderClient mock = new MockPixProviderClient(properties);
        mock.setSimulation(MockPixProviderClient.Simulation.FAILURE);
        return mock;
    }

    @Override
    protected PixProviderClient timeoutClient() {
        MockPixProviderClient mock = new MockPixProviderClient(properties);
        mock.setSimulation(MockPixProviderClient.Simulation.TIMEOUT);
        return mock;
    }

    @Override
    protected PixProviderClient.CreateChargeCommand command() {
        return new PixProviderClient.CreateChargeCommand(
                UUID.randomUUID(), UUID.randomUUID(), Money.of("10.00"), "52998224725",
                "cobranca", 3600);
    }

    @Test
    void createsChargeWithBacenLikeExternalReference() {
        MockPixProviderClient client = new MockPixProviderClient(properties);

        PixProviderClient.CreateChargeResult result = client.createCharge(command());

        assertThat(result.externalReference()).matches("^E[0-9A-F]{32}$");
    }

    @Test
    void copyPasteContainsAmountAndQrCodeDecodesToIt() {
        MockPixProviderClient client = new MockPixProviderClient(properties);

        PixProviderClient.CreateChargeResult result = client.createCharge(command());

        assertThat(result.pixCopyPaste()).startsWith("000201").contains("53039865410.00");
        String decoded = new String(Base64.getDecoder().decode(result.pixQrCode()), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(result.pixCopyPaste());
    }

    @Test
    void sameTransactionIdReturnsSameChargeIdempotently() {
        MockPixProviderClient client = new MockPixProviderClient(properties);
        UUID id = UUID.randomUUID();

        PixProviderClient.CreateChargeResult first = client.createCharge(command(id));
        PixProviderClient.CreateChargeResult second = client.createCharge(command(id));

        assertThat(second.externalReference()).isEqualTo(first.externalReference());
        assertThat(second.pixCopyPaste()).isEqualTo(first.pixCopyPaste());
        assertThat(second.pixQrCode()).isEqualTo(first.pixQrCode());
    }

    @Test
    void differentTransactionIdsProduceDifferentReferences() {
        MockPixProviderClient client = new MockPixProviderClient(properties);

        PixProviderClient.CreateChargeResult a = client.createCharge(command(UUID.randomUUID()));
        PixProviderClient.CreateChargeResult b = client.createCharge(command(UUID.randomUUID()));

        assertThat(a.externalReference()).isNotEqualTo(b.externalReference());
    }

    @Test
    void mockTimeoutThrowsOnFirstAttemptAndSucceedsOnRetry() {
        PspProperties props = new PspProperties();
        props.setMockTimeout(true);
        MockPixProviderClient client = new MockPixProviderClient(props);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> client.createCharge(command(id)))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class);

        // Retry com o mesmo transactionId passa (como no PSP real).
        PixProviderClient.CreateChargeResult retry = client.createCharge(command(id));
        assertThat(retry.externalReference()).matches("^E[0-9A-F]{32}$");
    }

    @Test
    void mockTimeoutAppliesPerTransaction() {
        PspProperties props = new PspProperties();
        props.setMockTimeout(true);
        MockPixProviderClient client = new MockPixProviderClient(props);

        UUID first = UUID.randomUUID();
        assertThatThrownBy(() -> client.createCharge(command(first)))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class);

        // Transação NOVA volta a sofrer o timeout na primeira tentativa.
        UUID second = UUID.randomUUID();
        assertThatThrownBy(() -> client.createCharge(command(second)))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class);
    }

    @Test
    void timeoutAfterCreateDeixaCobrancaAchavelNoGetChargeByCode() {
        // Spec 91 (E2E do poll anti-dupla-cobrança): o mock CRIA a cobrança e
        // responde incerto — o getChargeByCode acha e o retry idempotente devolve.
        MockPixProviderClient client = new MockPixProviderClient(properties);
        client.setSimulation(MockPixProviderClient.Simulation.TIMEOUT_AFTER_CREATE);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> client.createCharge(command(id)))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class);

        // O poll (Spec 91) acha a cobrança criada.
        PixProviderClient.GetChargeResult found = client.getChargeByCode(id.toString());
        assertThat(found.found()).isTrue();
        assertThat(found.pixCopyPaste()).isNotBlank();

        // O retry idempotente (Spec 28) devolve a MESMA cobrança.
        PixProviderClient.CreateChargeResult retry = client.createCharge(command(id));
        assertThat(retry.pixCopyPaste()).isEqualTo(found.pixCopyPaste());
    }

    private PixProviderClient.CreateChargeCommand command(UUID id) {
        return new PixProviderClient.CreateChargeCommand(
                id, UUID.randomUUID(), Money.of("10.00"), "52998224725",
                "cobranca", 3600);
    }
}
