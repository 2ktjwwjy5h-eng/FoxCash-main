package com.foxcash.domain.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: gestão do MFA do master (Spec 44).
 *
 * <p>Fluxos: ativação em 2 passos (activate → confirm), desativação com posse
 * (senha + TOTP/recovery) e regeneração de recovery codes (TOTP válido). Tudo
 * restrito ao próprio master (admin não gerencia TOTP de terceiros — evita
 * escalada admin-sobre-admin).
 */
public interface MasterMfaUseCase {

    /** Passo 1 da ativação: gera o secret e devolve URI + chave manual 1x. */
    ActivateResult activate(UUID masterId);

    /** Passo 2: valida o primeiro código TOTP (prova de posse) → ativa + recovery codes. */
    ConfirmResult confirm(UUID masterId, String code);

    /** Desativa o MFA: exige senha atual + TOTP válido (ou recovery code). */
    void deactivate(UUID masterId, String code, String password);

    /** Regenera os recovery codes (requer TOTP válido). */
    List<String> regenerateRecoveryCodes(UUID masterId, String code);

    /** Verifica se o master tem MFA ativo (usado pelo login). */
    boolean hasActiveMfa(UUID masterId);

    record ActivateResult(String otpauthUri, String manualEntryKey) {
    }

    record ConfirmResult(List<String> recoveryCodes) {
    }
}
