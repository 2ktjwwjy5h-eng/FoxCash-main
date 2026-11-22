package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Spec 136 (refactor de god classes) — orquestrador fino: mantém apenas os
 * handlers GENÉRICOS/framework (400/404/405/409 + fallback 500 com a métrica de
 * 5xx). Os handlers de DOMÍNIO foram movidos para os especialistas
 * {@code com.foxcash.infrastructure.inbound.rest.handler.*} (Transaction,
 * Merchant, Store, Auth, Security, PixProvider) — o fallback genérico deste
 * advice tem {@code @Order(LOWEST_PRECEDENCE)}: o Spring resolve o handler mais
 * específico entre todos os advices, e este fica por último.
 *
 * <p>Refactor comportamentalmente idêntico — provado pelo
 * {@code ExceptionMappingContractTest} (contrato congelado antes/depois).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Spec 126: métrica de erros 5xx por rota (alerta proativo). */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public GlobalExceptionHandler(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("invalid_argument", "Parametro ou corpo da requisicao invalido"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of("method_not_allowed", "Metodo HTTP nao suportado para esta rota"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        // Auditoria 2026-08-05 (M-A1): rota inexistente (path nao mapeado) com
        // autenticacao valida antes caia em handleUnexpected → 500 + stack trace
        // em ERROR (flood de log por paths aleatorios). Agora 404 not_found, log
        // em WARN sem stack trace (o 404 e comportamento esperado).
        log.warn("Rota inexistente (404): {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("not_found", "Recurso nao encontrado"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        // Auditoria 2026-08-05 (B-01): a mensagem da excecao pode conter detalhes
        // internos (nomes de classe/restricao, texto de infra) — loga no servidor
        // para suporte e devolve 409 generico ao cliente. O codigo de erro
        // (invalid_state) ja da a semantica maquina-legivel.
        log.warn("Conflito de estado (409): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("invalid_state", "Conflito de estado para a operacao"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        // Auditoria 2026-08-05 (B-01): mesmo padrao do IllegalStateException —
        // a mensagem vai ao log (rastreabilidade) e a resposta 400 e generica,
        // sem ecoar detalhes internos. Validacao de contrato (Bean Validation)
        // continua devolvendo o campo especifico via MethodArgumentNotValidException.
        log.warn("Argumento invalido (400): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("invalid_argument", "Parametro ou argumento invalido"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("validation_error", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, jakarta.servlet.http.HttpServletRequest request) {
        log.error("Erro inesperado", ex);
        // Spec 126: métrica de 5xx por rota normalizada (alerta proativo).
        meterRegistry.counter("foxcash_http_5xx_total", "route",
                        org.springframework.util.StringUtils.cleanPath(request.getRequestURI()))
                .increment();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("internal_error", "Ocorreu um erro inesperado"));
    }
}
