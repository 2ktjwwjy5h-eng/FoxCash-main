package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PixProviderClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato do adapter de cobrança Pix (Spec 91): todo adapter que implementa
 * {@link PixProviderClient} deve HERDAR este teste — os invariantes do contrato
 * são verificados automaticamente quando o adapter for criado.
 *
 * <p>Invariantes (D2.2): {@code createCharge} devolve {@code externalReference} +
 * QR; {@code getChargeByCode(code)} após create → {@code found=true} com o MESMO
 * QR; code desconhecido → {@code found=false}; falha síncrona do provedor
 * (4xx/5xx) → {@link PixProviderClient.PixProviderException}; timeout/5xx
 * incerto → {@link PixProviderClient.PixProviderTimeoutException}.
 *
 * <p>Os testes concretos (ex.: {@code MockPixProviderClientTest},
 * {@code PagarMePixProviderClientTest} com MockWebServer) fornecem
 * {@link #client()}, {@link #failingClient()} (erro definitivo 4xx) e
 * {@link #timeoutClient()} (5xx/timeout incerto) — sem poder "furar" o contrato
 * sem que a suíte acuse.
 */
public abstract class PixProviderContractTest {

    /** Adapter sob teste (configuração normal). */
    protected abstract PixProviderClient client();

    /** Adapter configurado para erro DEFINITIVO (4xx de negócio / falha síncrona). */
    protected abstract PixProviderClient failingClient();

    /** Adapter configurado para resposta INCERTA (5xx/timeout). */
    protected abstract PixProviderClient timeoutClient();

    /** Command válido para o adapter. */
    protected abstract PixProviderClient.CreateChargeCommand command();

    @Test
    void createChargeDevolveReferenciaEQRCode() {
        // createCharge deve devolver a referência externa do PSP + o payload Pix
        // (QR base64 + Copia e Cola) — nunca vazios.
        PixProviderClient.CreateChargeResult result = client().createCharge(command());
        assertThat(result.externalReference()).isNotBlank();
        assertThat(result.pixQrCode()).isNotBlank();
        assertThat(result.pixCopyPaste()).isNotBlank();
    }

    @Test
    void getChargeByCodeAchaCobrancaCriadaComMesmoQr() {
        // Poll anti-dupla-cobrança (Spec 91): após criar, a consulta pelo code
        // (= transactionId) deve achar a MESMA cobrança (found=true, mesmo QR).
        PixProviderClient.CreateChargeCommand cmd = command();
        PixProviderClient.CreateChargeResult created = client().createCharge(cmd);

        PixProviderClient.GetChargeResult found = client().getChargeByCode(cmd.transactionId().toString());

        assertThat(found.found()).isTrue();
        assertThat(found.pixCopyPaste()).isEqualTo(created.pixCopyPaste());
        assertThat(found.pixQrCode()).isEqualTo(created.pixQrCode());
    }

    @Test
    void getChargeByCodeDesconhecidoNaoAcha() {
        // Code que nunca foi criado → found=false (o router cai para o próximo).
        PixProviderClient.GetChargeResult notFound = client().getChargeByCode(
                UUID.randomUUID().toString());
        assertThat(notFound.found()).isFalse();
    }

    @Test
    void falhaSincronaLancaExcecao() {
        // Erro definitivo do PSP (4xx de negócio) → PixProviderException (ou
        // subclasse) — o router faz fallback imediato.
        assertThatThrownBy(() -> failingClient().createCharge(command()))
                .isInstanceOf(PixProviderClient.PixProviderException.class);
    }

    @Test
    void respostaIncertaLancaPixProviderTimeoutException() {
        // Timeout/5xx → PixProviderTimeoutException (resposta INCERTA — o router
        // faz o poll anti-dupla-cobrança antes do fallback).
        assertThatThrownBy(() -> timeoutClient().createCharge(command()))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class);
    }
}
