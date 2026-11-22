package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de perfil master (Spec 13; gestão Spec 41) — desacoplada do
 * modelo de dominio.
 */
@Entity
@Table(name = "masters")
public class MasterJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "can_manage_masters", nullable = false)
    private boolean canManageMasters;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected MasterJpaEntity() {
    }

    public MasterJpaEntity(UUID id, String username, String passwordHash, String name,
                           boolean active, boolean canManageMasters, Instant createdAt) {
        this(id, username, passwordHash, name, active, canManageMasters, createdAt, 0, null);
    }

    public MasterJpaEntity(UUID id, String username, String passwordHash, String name,
                           boolean active, boolean canManageMasters, Instant createdAt,
                           int failedLoginAttempts, Instant lockedUntil) {
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

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isCanManageMasters() {
        return canManageMasters;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
