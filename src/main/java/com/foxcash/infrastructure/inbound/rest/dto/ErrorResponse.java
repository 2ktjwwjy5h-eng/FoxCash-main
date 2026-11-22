package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Corpo padrao de erro da API.
 */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message);
    }
}
