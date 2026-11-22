package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve o escopo de perfil de operação do header {@code X-Profile-ID} (Spec
 * 124 — fase 2, filtro rígido do portal). A fonte da verdade é o **banco**
 * (Spec 122): o header é apenas **seleção de contexto** — nunca decide rota de
 * cobrança.
 *
 * <p><b>Semântica de escopo (segurança — nunca vaza entre perfis/lojistas):</b>
 * <ul>
 *   <li><b>Sem header</b> → {@code scopeProfileId} vazio → os services NÃO
 *       adicionam predicado → lojista inteiro (regressão — clientes legados /
 *       API sem perfil).</li>
 *   <li><b>Header válido</b> (perfil não-deletado do lojista autenticado) →
 *       escopa por {@code profile_id = header} → só os dados do perfil.</li>
 *   <li><b>Header inválido</b> (perfil de outro lojista / inexistente) →
 *       {@code scopeProfileId} devolve o valor do header mesmo assim; o
 *       predicado SEMPRE inclui {@code merchant_id = auth} → resultado
 *       <b>VAZIO</b> (nunca cai para o lojista inteiro, nunca vaza).</li>
 * </ul>
 *
 * <p>O escopo por bot-set ({@code botIdsOf}) também inclui {@code merchant_id}
 * na subquery — um perfil de outro lojista retorna lista vazia → {@code IN ()}
 * → vazio por construção.
 */
@Component
public class ProfileScopeResolver {

    private final MerchantProfileRepository profileRepository;
    private final StoreBotRepository botRepository;

    public ProfileScopeResolver(MerchantProfileRepository profileRepository,
                                StoreBotRepository botRepository) {
        this.profileRepository = profileRepository;
        this.botRepository = botRepository;
    }

    /**
     * Perfil do header **validado** (não-deletado e do lojista autenticado).
     * Ausente, não-UUID ou de outro lojista → {@code empty}. Usado onde o
     * contexto precisa do perfil REAL (ex.: cobrança do portal — header
     * inválido → o chamador cai para o default).
     */
    public Optional<UUID> resolveActiveProfileId(UUID merchantId, HttpServletRequest request) {
        UUID header = parseProfileId(request.getHeader(OperationProfileController.X_PROFILE_ID_HEADER));
        if (header == null) {
            return Optional.empty();
        }
        return profileRepository.findByIdAndMerchantId(header, merchantId)
                .map(com.foxcash.domain.model.profile.MerchantProfile::id);
    }

    /**
     * Perfil do header para o **escopo de leitura** (filtro rígido): presente e
     * parseável → {@code of(header)} REGARDLESS de pertinência. A query escopa
     * {@code profile_id = header AND merchant_id = auth} — perfil de outro
     * lojista/inexistente → vazio (nunca lojista inteiro). Ausente/não-UUID →
     * {@code empty} → sem predicado (lojista inteiro — regressão).
     */
    public Optional<UUID> scopeProfileId(UUID merchantId, HttpServletRequest request) {
        UUID header = parseProfileId(request.getHeader(OperationProfileController.X_PROFILE_ID_HEADER));
        return Optional.ofNullable(header);
    }

    /**
     * Ids dos bots do perfil para o escopo por bot-set (CRM/broadcasts/tracking/
     * métricas). Subquery com {@code merchant_id}: perfil de outro lojista →
     * lista vazia (nunca vaza). Vazio também quando o perfil não tem bots.
     */
    public List<UUID> botIdsOf(UUID merchantId, UUID profileId) {
        if (profileId == null) {
            return List.of();
        }
        return botRepository.findIdsByMerchantIdAndProfileId(merchantId, profileId);
    }

    /**
     * Bots do escopo de leitura do header para os módulos por bot-set
     * (CRM/broadcasts/tracking/métricas de bots). Sem header → {@code null}
     * (lojista inteiro — regressão, clientes legados); header presente → ids
     * dos bots do perfil (perfil de outro lojista/inexistente → lista vazia →
     * {@code IN ()} → resultado VAZIO, nunca vaza e nunca cai para o lojista
     * inteiro). Conveniência sobre {@link #scopeProfileId} + {@link #botIdsOf}.
     */
    public List<UUID> scopeBotIds(UUID merchantId, HttpServletRequest request) {
        UUID profileId = scopeProfileId(merchantId, request).orElse(null);
        return profileId == null ? null : botIdsOf(merchantId, profileId);
    }

    /** Perfil default do lojista (fallback das cobranças sem bot/header). */
    public Optional<UUID> defaultProfileId(UUID merchantId) {
        return profileRepository.defaultProfileIdOf(merchantId);
    }

    /** Header ausente ou não-UUID → null (o chamador decide o fallback). */
    private static UUID parseProfileId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
