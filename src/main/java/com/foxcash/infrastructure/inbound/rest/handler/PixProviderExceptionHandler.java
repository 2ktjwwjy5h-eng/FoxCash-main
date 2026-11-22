package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.PspTimeoutException;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Spec 136 (refactor de god classes) — especialista de domínio: erros de PSP
 * (Pagar.me/Asaas/mock) na criação da cobrança. Movido do
 * {@code GlobalExceptionHandler} sem mudança de comportamento.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PixProviderExceptionHandler {

    @ExceptionHandler(PspTimeoutException.class)
    public ResponseEntity<ErrorResponse> handlePspTimeout(PspTimeoutException ex) {
        // Timeout do PSP na criação da cobrança: resposta incerta (Spec 28).
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("psp_timeout", ex.getMessage()));
    }
}
