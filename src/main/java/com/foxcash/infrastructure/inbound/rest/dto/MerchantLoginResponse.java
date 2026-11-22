package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantPortalAuthUseCase;

import java.time.Instant;
import java.util.UUID;

/**
 * Response do login do lojista no portal (Spec 20; nome desde a Spec 39): token
 * opaco devolvido UMA vez; apenas o hash SHA-256 e persistido.
 */
public record MerchantLoginResponse(
        String token,
        UUID merchantId,
        String name,
        Instant expiresAt) {

    public static MerchantLoginResponse from(MerchantPortalAuthUseCase.LoginResult result) {
        return new MerchantLoginResponse(
                result.token(), result.merchantId(), result.name(), result.expiresAt());
    }
}
