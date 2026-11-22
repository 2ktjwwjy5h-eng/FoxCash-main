package com.foxcash.domain.model.master;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Perfil master — operador da plataforma (Spec 13, ADR-007; gestão Spec 41).
 *
 * <p>Autenticado por usuário/senha (hash BCrypt); sessão via token opaco em
 * {@link MasterSession}. Diferente do lojista (API key) e do admin técnico
 * (chave única): é um operador humano com identidade própria. Desde a Spec 41,
 * o perfil pode carregar a flag {@code canManageMasters} (admin) — quem cria e
 * gerencia outros perfis master; o primeiro master (seed) nasce admin.
 *
 * <p>Auditoria 2026-08-05 (B-08): lockout de login — falhas seguidas de senha
 * incrementam {@code failedLoginAttempts}; ao atingir o limite, {@code lockedUntil}
 * é setado e o login é recusado (401 uniforme) até o instante expirar.
 */
public class Master {

    private final UUID id;
    private final String username;
    private String passwordHash;
    private final String name;
    private boolean active;
    private final boolean canManageMasters;
    private final Instant createdAt;
    private int failedLoginAttempts;
    private Instant lockedUntil;

    private Master(UUID id, String username, String passwordHash, String name, boolean active,
                   boolean canManageMasters, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.name = name;
        this.active = active;
        this.canManageMasters = canManageMasters;
        this.createdAt = createdAt;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    private Master(UUID id, String username, String passwordHash, String name, boolean active,
                   boolean canManageMasters, Instant createdAt, int failedLoginAttempts,
                   Instant lockedUntil) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.name = name;
        this.active = active;
        this.canManageMasters = canManageMasters;
        this.createdAt = createdAt;
        this.failedLoginAttempts = failedLoginAttempts;
        this.lockedUntil = lockedUntil;
    }

    /**
     * Cria um master ativo (não-admin).
     *
     * @param passwordHash hash BCrypt da senha (calculado na infraestrutura —
     *                     o domínio nunca vê a senha em claro)
     */
    public static Master create(String username, String passwordHash, String name) {
        return create(username, passwordHash, name, false);
    }

    /**
     * Cria um master ativo (Spec 41).
     *
     * @param canManageMasters {@code true} apenas para o primeiro master (admin);
     *                         novos perfis nascem {@code false}
     */
    public static Master create(String username, String passwordHash, String name,
                                boolean canManageMasters) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(name, "name must not be null");
        return new Master(UUID.randomUUID(), username, passwordHash, name, true,
                canManageMasters, Instant.now());
    }

    public static Master restore(UUID id, String username, String passwordHash, String name,
                                 boolean active, boolean canManageMasters, Instant createdAt) {
        return new Master(id, username, passwordHash, name, active, canManageMasters, createdAt);
    }

    /** Restore completo (auditoria 2026-08-05/B-08): inclui estado de lockout. */
    public static Master restore(UUID id, String username, String passwordHash, String name,
                                 boolean active, boolean canManageMasters, Instant createdAt,
                                 int failedLoginAttempts, Instant lockedUntil) {
        return new Master(id, username, passwordHash, name, active, canManageMasters, createdAt,
                failedLoginAttempts, lockedUntil);
    }

    /** Overload legado (Spec 13 — testes existentes): master não-admin. */
    public static Master restore(UUID id, String username, String passwordHash, String name,
                                 boolean active, Instant createdAt) {
        return restore(id, username, passwordHash, name, active, false, createdAt);
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    /**
     * Troca a senha (SEC-25, revisão de segurança 2026-08-11): novo hash BCrypt
     * calculado na infraestrutura — o domínio nunca vê a senha em claro. O
     * chamador é responsável por revogar as sessões existentes
     * (revokeAllByMasterId) para a rotação matar tokens roubados.
     */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash must not be null");
    }

    public boolean isActive() {
        return active;
    }

    /** Admin: pode criar/gerir perfis master e consultar o relatório (Spec 41). */
    public boolean canManageMasters() {
        return canManageMasters;
    }

    public UUID id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Bloqueado no instante {@code now}? (auditoria 2026-08-05/B-08). */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Registra uma tentativa de login com falha (auditoria 2026-08-05/B-08).
     * Ao atingir {@code maxAttempts}, seta {@code lockedUntil} e zera o contador
     * (após a janela expirar o operador tem tentativas novas).
     */
    public void registerFailedLogin(Instant now, int maxAttempts, java.time.Duration lockoutDuration) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockoutDuration);
            failedLoginAttempts = 0;
        }
    }

    /** Login bem-sucedido: limpa contador e lockout (auditoria 2026-08-05/B-08). */
    public void registerSuccessfulLogin() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    public int failedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }
}
