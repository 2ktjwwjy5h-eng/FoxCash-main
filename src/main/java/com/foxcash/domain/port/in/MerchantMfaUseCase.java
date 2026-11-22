package com.foxcash.domain.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: gestão do MFA do lojista no portal (Spec 94).
 *
 * <p>Fluxos: ativação em 2 passos (activate → confirm), desativação com posse
 * (senha do portal + TOTP/recovery) e regeneração de recovery codes (TOTP
 * válido). Tudo restrito ao próprio lojista (o portal não gerencia TOTP de
 * terceiros). Espelho do {@code MasterMfaUseCase} (Spec 44) — sem step-up
 * (fora de escopo; o lojista exige MFA apenas no login).
 */
public interface MerchantMfaUseCase {

    /** Passo 1 da ativação: gera o secret e devolve URI + chave manual 1x. */
    ActivateResult activate(UUID merchantId);

    /** Passo 2: valida o primeiro código TOTP (prova de posse) → ativa + recovery codes. */
    ConfirmResult confirm(UUID merchantId, String code);

    /** Desativa o MFA: exige senha atual (portal) + TOTP válido (ou recovery code). */
    void deactivate(UUID merchantId, String code, String password);

    /** Regenera os recovery codes (requer TOTP válido). */
    List<String> regenerateRecoveryCodes(UUID merchantId, String code);

    /** Verifica se o lojista tem MFA ativo (usado pelo login). */
    boolean hasActiveMfa(UUID merchantId);

    /**
     * Verifica o 2º fator no login (chamado pelo {@code MerchantPortalAuthService}
     * quando {@code hasActiveMfa}): TOTP válido (janela ±1, tempo constante,
     * anti-replay) OU recovery code single-use. 401 uniforme
     * ({@code InvalidMfaCodeException}) sem código/código inválido/lockout.
     */
    void verifySecondFactor(UUID merchantId, String code);

    record ActivateResult(String otpauthUri, String manualEntryKey) {
    }

    record ConfirmResult(List<String> recoveryCodes) {
    }
}
