package com.foxcash.domain.model.profile;

/**
 * Escopo do perfil de operação (Spec 139) — classifica a operação do perfil sem
 * quebrar o modelo auditado da Spec 122 (perfil = UMA entidade: rota + frota).
 *
 * <ul>
 *   <li>{@code GENERAL} — ⚡ Completo: rota de gateway + frota de bots.</li>
 *   <li>{@code BOT_ONLY} — 🤖 Somente Bot: frota de bots; rota sempre
 *       {@code null} (PADRAO — roteamento por risco, Spec 91).</li>
 *   <li>{@code GATEWAY_ONLY} — 🌐 Somente Gateway: rota de gateway; frota
 *       vazia (desanexada).</li>
 * </ul>
 *
 * <p>As regras de consistência (BOT_ONLY sem rota, GATEWAY_ONLY sem frota) são
 * defesa na camada de aplicação (serviço), não no enum.</p>
 */
public enum ProfileScope {

    GENERAL,
    BOT_ONLY,
    GATEWAY_ONLY
}
