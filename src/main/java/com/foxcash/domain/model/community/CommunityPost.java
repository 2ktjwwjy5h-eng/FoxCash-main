package com.foxcash.domain.model.community;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Post da Comunidade (Spec 119) — aviso/novidade da plataforma publicado pelo
 * master (nome do autor resolvido na leitura). Read-only no portal; CRUD no
 * Master Console. Sem chat/comentários (KISS — fora de escopo).
 */
public record CommunityPost(
        UUID id,
        String title,
        String content,
        UUID publishedBy,
        boolean pinned,
        Instant publishedAt,
        Instant createdAt) {

    public CommunityPost {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(publishedBy, "publishedBy must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (title.isBlank() || title.length() > 160) {
            throw new IllegalArgumentException("title deve ter entre 1 e 160 caracteres");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content nao pode ser vazio");
        }
    }

    /** Cria um post novo (publicado pelo master autenticado). */
    public static CommunityPost create(String title, String content, UUID publishedBy,
                                       boolean pinned, Instant now) {
        return new CommunityPost(UUID.randomUUID(), title.trim(), content.trim(),
                publishedBy, pinned, now, now);
    }

    /** Cópia com conteúdo editado (PATCH — título/conteúdo/pinned opcionais). */
    public CommunityPost update(String newTitle, String newContent, Boolean newPinned, Instant now) {
        return new CommunityPost(id,
                newTitle == null ? title : newTitle.trim(),
                newContent == null ? content : newContent.trim(),
                publishedBy,
                newPinned == null ? pinned : newPinned,
                publishedAt, now);
    }
}
