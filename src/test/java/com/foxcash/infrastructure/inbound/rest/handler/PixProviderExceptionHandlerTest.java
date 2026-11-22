package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.PspTimeoutException;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 136 (C4) — teste isolado do {@link PixProviderExceptionHandler}:
 * timeout do PSP → 502 {@code psp_timeout} (Spec 28 — resposta incerta).
 */
class PixProviderExceptionHandlerTest {

    private final PixProviderExceptionHandler handler = new PixProviderExceptionHandler();

    @Test
    void pspTimeoutMapsTo502() {
        ResponseEntity<ErrorResponse> response =
                handler.handlePspTimeout(new PspTimeoutException("timeout", new RuntimeException()));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().code()).isEqualTo("psp_timeout");
    }
}
