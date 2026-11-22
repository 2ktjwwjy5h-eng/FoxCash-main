package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do post da Comunidade (Spec 119) — desacoplada do modelo de
 * domínio. Autor = {@code masters.id} ({@code published_by}); sem
 * chat/comentários (KISS — fora de escopo).
 */
@Entity
@Table(name = "community_posts")
public class CommunityPostJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "published_by", nullable = false)
    private UUID publishedBy;

    @Column(nullable = false)
    private boolean pinned;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CommunityPostJpaEntity() {
    }

    public CommunityPostJpaEntity(UUID id, String title, String content, UUID publishedBy,
                                  boolean pinned, Instant publishedAt, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.publishedBy = publishedBy;
        this.pinned = pinned;
        this.publishedAt = publishedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public UUID getPublishedBy() { return publishedBy; }
    public boolean isPinned() { return pinned; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
