package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MasterAuthUseCase;

import java.time.Instant;
import java.util.UUID;

/**
 * Response do login do master (Spec 13): token opaco devolvido UMA vez.
 */
public record MasterLoginResponse(
        String token,
        UUID masterId,
        String name,
        Instant expiresAt) {

    public static MasterLoginResponse from(MasterAuthUseCase.LoginResult result) {
        return new MasterLoginResponse(result.token(), result.masterId(), result.name(), result.expiresAt());
    }
}
