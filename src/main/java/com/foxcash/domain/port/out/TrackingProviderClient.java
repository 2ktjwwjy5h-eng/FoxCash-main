package com.foxcash.domain.port.out;

import com.foxcash.domain.model.tracking.ProviderResult;
import com.foxcash.domain.model.tracking.TrackingEvent;
import com.foxcash.domain.model.tracking.TrackingServiceType;

/**
 * Porta de saída: cliente de um provedor de tracking (Spec 81/D6) — um adapter
 * por provedor (Facebook CAPI, TikTok, UTMify, Otimizey). {@code type()} é o
 * seletor do dispatch; {@code testConnection()} alimenta o {@code POST /sync}.
 * As credenciais (pixel/token do perfil) são passadas por chamada — o provedor
 * nunca conhece a configuração do perfil.
 */
public interface TrackingProviderClient {

    TrackingServiceType type();

    /** Envia o evento ao provedor. Nunca lança — devolve {@link ProviderResult}. */
    ProviderResult send(TrackingEvent event, ProviderCredentials credentials);

    /** Teste de conexão/configuração ({@code POST /{id}/sync} — Spec 81/D8). */
    ProviderResult testConnection(ProviderCredentials credentials);

    /** Credenciais do provedor dentro de um perfil (pixelId nulo p/ UTMify/Otimizey). */
    record ProviderCredentials(String pixelId, String token) {
    }
}
