package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.community.CommunityPost;
import com.foxcash.domain.port.out.CommunityPostRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.CommunityPostJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.CommunityPostJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link CommunityPostRepository} (Spec 119).
 */
@Repository
public class CommunityPostPersistenceAdapter implements CommunityPostRepository {

    private final CommunityPostJpaRepository jpaRepository;

    public CommunityPostPersistenceAdapter(CommunityPostJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PageResult<CommunityPost> findAll(int page, int size) {
        org.springframework.data.domain.Pageable pageable = PageRequest.of(page, size);
        var result = jpaRepository.findAllByOrderByPinnedDescPublishedAtDesc(pageable);
        List<CommunityPost> items = result.stream().map(CommunityPostPersistenceAdapter::toDomain).toList();
        return PageResult.of(items, page, size, jpaRepository.count());
    }

    @Override
    public Optional<CommunityPost> findById(UUID id) {
        return jpaRepository.findById(id).map(CommunityPostPersistenceAdapter::toDomain);
    }

    @Override
    public CommunityPost save(CommunityPost post) {
        return toDomain(jpaRepository.save(toEntity(post)));
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public boolean existsByTitle(String title) {
        return jpaRepository.existsByTitle(title);
    }

    private static CommunityPost toDomain(CommunityPostJpaEntity entity) {
        return new CommunityPost(
                entity.getId(), entity.getTitle(), entity.getContent(),
                entity.getPublishedBy(), entity.isPinned(),
                entity.getPublishedAt(), entity.getCreatedAt());
    }

    private static CommunityPostJpaEntity toEntity(CommunityPost post) {
        return new CommunityPostJpaEntity(
                post.id(), post.title(), post.content(), post.publishedBy(),
                post.pinned(), post.publishedAt(), post.createdAt());
    }
}
