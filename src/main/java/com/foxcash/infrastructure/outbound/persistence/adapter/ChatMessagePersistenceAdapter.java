package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.crm.ChatMessage;
import com.foxcash.domain.port.out.ChatMessageRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.ChatMessageJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.ChatMessageMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.ChatMessageJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link ChatMessageRepository} (Spec 83).
 */
@Repository
public class ChatMessagePersistenceAdapter implements ChatMessageRepository {

    private final ChatMessageJpaRepository jpaRepository;

    public ChatMessagePersistenceAdapter(ChatMessageJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        return ChatMessageMapper.toDomain(jpaRepository.save(ChatMessageMapper.toEntity(message)));
    }

    @Override
    public PageResult<ChatMessage> findByLeadId(UUID leadId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        var result = jpaRepository.findByLeadIdOrderByCreatedAtAsc(leadId, pageable);
        List<ChatMessage> items = result.getContent().stream().map(ChatMessageMapper::toDomain).toList();
        return PageResult.of(items, page, size, result.getTotalElements());
    }

    @Override
    public List<ChatMessage> findAllByLeadId(UUID leadId) {
        return jpaRepository.findByLeadIdOrderByCreatedAtAsc(leadId, PageRequest.of(0, 500))
                .getContent().stream().map(ChatMessageMapper::toDomain).toList();
    }

    @Override
    public long countByLeadIdAndReadFalse(UUID leadId) {
        return jpaRepository.countUnreadClient(leadId);
    }

    @Override
    @Transactional
    public void markAllRead(UUID leadId) {
        jpaRepository.markAllRead(leadId);
    }
}
