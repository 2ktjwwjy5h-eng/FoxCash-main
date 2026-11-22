package com.foxcash.infrastructure.config;

import com.foxcash.domain.model.master.MasterPermission;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuracao do step-up MFA do master (Spec 69 — D4.5): lista de permissoes
 * que exigem re-prova de posse (default: as 4 acoes sensiveis) e a janela de
 * validade do step-up por sessao (default 15 min).
 *
 * <p><b>Fail-closed:</b> {@code required-permissions} vazia ou com valor fora
 * do catalogo {@link MasterPermission} → boot falha (defaults sao as 4 acoes
 * sensiveis; uma lista vazia desligaria o step-up — rejeitado por seguranca).
 */
@ConfigurationProperties("foxcash.security.master.step-up")
public class MasterStepUpProperties {

    /** Permissoes que exigem step-up (Spec 69 D4.5 + Spec 98: debit/ban sao
     * acoes sensiveis — default: as 6 acoes de alto impacto). */
    private List<String> requiredPermissions = List.of(
            "withdrawal.administrative",
            "receivable.write_off",
            "invoice.collect",
            "master.profiles.manage",
            "merchant.debit",
            "merchant.ban");

    /** Janela de validade do step-up por sessao (default 15 min). */
    private Duration window = Duration.ofMinutes(15);

    public List<String> getRequiredPermissions() {
        return requiredPermissions;
    }

    public void setRequiredPermissions(List<String> requiredPermissions) {
        this.requiredPermissions = requiredPermissions;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    /** A permissao exige step-up? (default configurável — D4.5). */
    public boolean isRequired(MasterPermission permission) {
        return requiredPermissions.contains(permission.value());
    }

    /** Fail-closed (Spec 69 — T4.2): lista invalida/vazia derruba o boot. */
    @PostConstruct
    void validate() {
        if (requiredPermissions == null || requiredPermissions.isEmpty()) {
            throw new IllegalStateException(
                    "foxcash.security.master.step-up.required-permissions nao pode ser vazia: "
                            + "isso desligaria o step-up das acoes sensiveis (Spec 69). Default: "
                            + "withdrawal.administrative, receivable.write_off, invoice.collect, "
                            + "master.profiles.manage.");
        }
        for (String permission : requiredPermissions) {
            try {
                MasterPermission.fromValue(permission);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "foxcash.security.master.step-up.required-permissions contem valor "
                                + "invalido: '" + permission + "' (fora do catalogo MasterPermission, Spec 69).", e);
            }
        }
    }
}
