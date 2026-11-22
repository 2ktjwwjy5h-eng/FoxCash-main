package com.foxcash.domain.model.tracking;

/**
 * Resultado de um envio/teste a um provedor de tracking (Spec 81/D6).
 */
public record ProviderResult(boolean ok, String message) {

    public static ProviderResult ok(String message) {
        return new ProviderResult(true, message);
    }

    public static ProviderResult failed(String message) {
        return new ProviderResult(false, message);
    }
}
