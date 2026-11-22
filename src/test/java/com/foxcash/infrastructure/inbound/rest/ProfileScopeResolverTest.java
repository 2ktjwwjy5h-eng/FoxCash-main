package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.profile.MerchantProfile;
import com.foxcash.domain.model.profile.ProfileScope;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link ProfileScopeResolver} (Spec 124 — fase 2, filtro rígido do
 * portal). Semântica de escopo (nunca vaza entre perfis/lojistas):
 * sem header → lojista inteiro; header válido → perfil; header inválido (de
 * outro lojista/inexistente) → VAZIO (nunca cai para o lojista inteiro).
 */
class ProfileScopeResolverTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT_PROFILE = UUID.randomUUID();
    private static final UUID BOT_1 = UUID.randomUUID();
    private static final UUID BOT_2 = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private final MerchantProfileRepository profileRepository = mock(MerchantProfileRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final ProfileScopeResolver resolver =
            new ProfileScopeResolver(profileRepository, botRepository);

    private HttpServletRequest request(String headerValue) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(OperationProfileController.X_PROFILE_ID_HEADER))
                .thenReturn(headerValue);
        return request;
    }

    private MerchantProfile profile(UUID id) {
        return MerchantProfile.restore(id, MERCHANT_ID, "Padrão BR", null, ProfileScope.GENERAL,
                true, 0, null, NOW, NOW);
    }

    // ---- scopeProfileId (escopo de leitura) ----

    @Test
    void scopeProfileIdSemHeaderDevolveVazio() {
        assertThat(resolver.scopeProfileId(MERCHANT_ID, request(null))).isEmpty();
        assertThat(resolver.scopeProfileId(MERCHANT_ID, request("  "))).isEmpty();
        assertThat(resolver.scopeProfileId(MERCHANT_ID, request("not-a-uuid"))).isEmpty();
    }

    @Test
    void scopeProfileIdHeaderValidoDevolveOValor() {
        assertThat(resolver.scopeProfileId(MERCHANT_ID, request(PROFILE_ID.toString())))
                .contains(PROFILE_ID);
    }

    @Test
    void scopeProfileIdHeaderDeOutroLojistaDevolveOValorParaOLeitorEscopar() {
        // O header NÃO é validado aqui de propósito: a query escopa
        // profile_id = :header AND merchant_id = :auth → vazio (nunca lojista
        // inteiro). A validação de pertinência é do resolveActiveProfileId.
        assertThat(resolver.scopeProfileId(MERCHANT_ID, request(OTHER_MERCHANT_PROFILE.toString())))
                .contains(OTHER_MERCHANT_PROFILE);
    }

    // ---- resolveActiveProfileId (perfil REAL — cobrança do portal) ----

    @Test
    void resolveActiveProfileIdHeaderValidoDevolvePerfilDoLojista() {
        when(profileRepository.findByIdAndMerchantId(PROFILE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(profile(PROFILE_ID)));
        assertThat(resolver.resolveActiveProfileId(MERCHANT_ID, request(PROFILE_ID.toString())))
                .contains(PROFILE_ID);
    }

    @Test
    void resolveActiveProfileIdHeaderDeOutroLojistaDevolveVazio() {
        // findByIdAndMerchantId é merchant-scoped → empty (sem vazamento).
        when(profileRepository.findByIdAndMerchantId(OTHER_MERCHANT_PROFILE, MERCHANT_ID))
                .thenReturn(Optional.empty());
        assertThat(resolver.resolveActiveProfileId(MERCHANT_ID,
                request(OTHER_MERCHANT_PROFILE.toString()))).isEmpty();
    }

    @Test
    void resolveActiveProfileIdSemHeaderDevolveVazio() {
        assertThat(resolver.resolveActiveProfileId(MERCHANT_ID, request(null))).isEmpty();
    }

    // ---- botIdsOf (escopo por bot-set) ----

    @Test
    void botIdsOfPerfilNullDevolveListaVazia() {
        assertThat(resolver.botIdsOf(MERCHANT_ID, null)).isEmpty();
    }

    @Test
    void botIdsOfDevolveOsBotsDoPerfil() {
        when(botRepository.findIdsByMerchantIdAndProfileId(MERCHANT_ID, PROFILE_ID))
                .thenReturn(List.of(BOT_1, BOT_2));
        assertThat(resolver.botIdsOf(MERCHANT_ID, PROFILE_ID)).containsExactly(BOT_1, BOT_2);
    }

    // ---- scopeBotIds (conveniência para os módulos por bot-set) ----

    @Test
    void scopeBotIdsSemHeaderDevolveNullLojistaInteiro() {
        // null = sem predicado (regressão — clientes legados sem perfil).
        assertThat(resolver.scopeBotIds(MERCHANT_ID, request(null))).isNull();
    }

    @Test
    void scopeBotIdsHeaderValidoDevolveBotsDoPerfil() {
        when(botRepository.findIdsByMerchantIdAndProfileId(MERCHANT_ID, PROFILE_ID))
                .thenReturn(List.of(BOT_1));
        assertThat(resolver.scopeBotIds(MERCHANT_ID, request(PROFILE_ID.toString())))
                .containsExactly(BOT_1);
    }

    @Test
    void scopeBotIdsHeaderDeOutroLojistaDevolveListaVazia() {
        // findIdsByMerchantIdAndProfileId é merchant-scoped → vazio → IN () → vazio.
        when(botRepository.findIdsByMerchantIdAndProfileId(MERCHANT_ID, OTHER_MERCHANT_PROFILE))
                .thenReturn(List.of());
        assertThat(resolver.scopeBotIds(MERCHANT_ID, request(OTHER_MERCHANT_PROFILE.toString())))
                .isEmpty();
    }

    // ---- defaultProfileId (fallback das cobranças sem bot/header) ----

    @Test
    void defaultProfileIdDevolvePerfilDefaultDoLojista() {
        when(profileRepository.defaultProfileIdOf(MERCHANT_ID)).thenReturn(Optional.of(PROFILE_ID));
        assertThat(resolver.defaultProfileId(MERCHANT_ID)).contains(PROFILE_ID);
    }
}
