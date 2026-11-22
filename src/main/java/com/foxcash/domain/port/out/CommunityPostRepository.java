package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.community.CommunityPost;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de posts da Comunidade (Spec 119). Leitura
 * paginada para o portal (pinado primeiro, depois por {@code published_at}
 * desc) e CRUD para o Master Console.
 */
public interface CommunityPostRepository {

    /** Lista paginada de posts (portal — pinados primeiro, cap 50 por página). */
    PageResult<CommunityPost> findAll(int page, int size);

    /** Post por id (404 no console quando inexistente). */
    Optional<CommunityPost> findById(UUID id);

    CommunityPost save(CommunityPost post);

    /** Delete físico (registro sem dependências — KISS). */
    void delete(UUID id);

    /** Existe algum post com o título informado? (idempotência do seed). */
    boolean existsByTitle(String title);
}
