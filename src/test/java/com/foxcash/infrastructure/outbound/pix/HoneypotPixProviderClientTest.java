package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PixProviderClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do provedor TRAP de honeypot (Spec 98 — D4.5): a cobrança do lojista
 * em ambiente controlado devolve QR fictício (referência {@code HP<uuid>}) e
 * NUNCA resolve a consulta ({@code getChargeByCode} → NOT_FOUND — não revela
 * nem confirma pagamento).
 */
class HoneypotPixProviderClientTest {

    private final HoneypotPixProviderClient trap = new HoneypotPixProviderClient();

    @Test
    void createChargeReturnsFakeQrWithHpReference() {
        UUID txId = UUID.randomUUID();
        PixProviderClient.CreateChargeCommand command =
                new PixProviderClient.CreateChargeCommand(txId, UUID.randomUUID(),
                        Money.of("100.00"), "pix@example.com", "venda", 3600);

        PixProviderClient.CreateChargeResult result = trap.createCharge(command);

        assertThat(result.externalReference()).isEqualTo("HP" + txId);
        assertThat(result.pixCopyPaste()).contains("honeypot");
        assertThat(result.pixQrCode()).isNotBlank();
        assertThat(result.provider()).isEqualTo("honeypot");
    }

    @Test
    void getChargeByCodeNeverResolves() {
        // NOT_FOUND sempre — a cobrança trap não existe em PSP algum.
        PixProviderClient.GetChargeResult result = trap.getChargeByCode("HP" + UUID.randomUUID());
        assertThat(result.found()).isFalse();
    }
}
