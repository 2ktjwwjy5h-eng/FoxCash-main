package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.BotLeadJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.BotLeadMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.BotLeadJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link BotLeadRepository} (Spec 75) — a UNIQUE
 * {@code (bot_id, platform_user_id)} é o mecanismo de idempotência: replay
 * (update/interação duplicado) → {@code DataIntegrityViolationException} →
 * {@code Optional.empty()} (no-op, sem duplicar).
 */
@Repository
public class BotLeadPersistenceAdapter implements BotLeadRepository {

    private final BotLeadJpaRepository jpaRepository;

    public BotLeadPersistenceAdapter(BotLeadJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<BotLead> findByBotIdAndPlatformUserId(UUID botId, String platformUserId) {
        return jpaRepository.findByBotIdAndPlatformUserId(botId, platformUserId)
                .map(BotLeadMapper::toDomain);
    }

    @Override
    public Optional<BotLead> saveIfAbsent(BotLead lead) {
        try {
            return Optional.of(BotLeadMapper.toDomain(jpaRepository.save(BotLeadMapper.toEntity(lead))));
        } catch (DataIntegrityViolationException e) {
            return Optional.empty(); // UNIQUE (bot_id, platform_user_id) — replay
        }
    }

    @Override
    public void save(BotLead lead) {
        jpaRepository.findByBotIdAndPlatformUserId(lead.botId(), lead.platformUserId())
                .ifPresentOrElse(entity -> {
                    // Atualização in-place (Specs 83/90): status/handoff/updated_at
                    // preservam o id e o created_at da linha (o mapper gera id novo).
                    entity.setStatus(lead.status().name());
                    entity.setHandoffStatus(lead.handoffStatus().name());
                    entity.setUpdatedAt(Instant.now());
                    jpaRepository.save(entity);
                }, () -> jpaRepository.save(BotLeadMapper.toEntity(lead)));
    }

    @Override
    public long countByBotIdAndFirstSeenAtBetween(UUID botId, Instant from, Instant to) {
        return jpaRepository.countByBotIdAndFirstSeenAtBetween(botId, from, to);
    }

    @Override
    public Optional<UUID> findIdByBotIdAndPlatformUserId(UUID botId, String platformUserId) {
        return jpaRepository.findByBotIdAndPlatformUserId(botId, platformUserId)
                .map(BotLeadJpaEntity::getId);
    }

    @Override
    public Optional<BotLead> findByIdAndMerchantId(UUID merchantId, UUID id) {
        return jpaRepository.findByIdAndMerchantId(merchantId, id).map(BotLeadMapper::toDomain);
    }

    @Override
    public Optional<BotLeadRepository.LeadEnrichment> findEnrichment(UUID merchantId, UUID leadId) {
        // Mesmo escopo de findByIdAndMerchantId (404 cross-lojista sem revelar
        // existência): só enriquece leads do lojista autenticado.
        return jpaRepository.findByIdAndMerchantId(merchantId, leadId).map(entity -> {
            Instant lastInteractionAt = jpaRepository.findLastInteractionAt(leadId)
                    .orElse(entity.getCreatedAt());
            String lastPlanName = jpaRepository.findLastPlanName(
                            entity.getBotId(), entity.getPlatformUserId(),
                            org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst().orElse(null);
            return new BotLeadRepository.LeadEnrichment(lastInteractionAt, lastPlanName);
        });
    }

    @Override
    public long countAllByFirstSeenAtBetween(Instant from, Instant to) {
        return jpaRepository.countByFirstSeenAtBetween(from, to);
    }

    @Override
    public long countByMerchantIdAndFirstSeenAtBetween(UUID merchantId, Instant from, Instant to) {
        return jpaRepository.countByMerchantIdAndFirstSeenAtBetween(merchantId, from, to);
    }

    @Override
    public Map<UUID, Long> countGroupedByBotId(List<UUID> botIds, Instant from, Instant to) {
        Map<UUID, Long> result = new LinkedHashMap<>();
        if (botIds.isEmpty()) {
            return result;
        }
        for (Object[] row : jpaRepository.countGroupedByBotId(botIds, from, to)) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
    }

    @Override
    public List<BotLeadRepository.RecentLead> findRecentByMerchantId(
            UUID merchantId, List<UUID> botIds, int limit) {
        int capped = Math.min(Math.max(limit, 1), 8);
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, capped);
        return jpaRepository.findRecentByMerchantId(merchantId, botIds, pageable).stream()
                .map(e -> new BotLeadRepository.RecentLead(
                        e.getId(), e.getName(),
                        e.getUpdatedAt() != null ? e.getUpdatedAt() : e.getCreatedAt()))
                .toList();
    }

    @Override
    public com.foxcash.domain.model.PageResult<BotLead> search(
            UUID merchantId, List<UUID> botIds, String q, UUID botId,
            com.foxcash.domain.model.crm.CrmLeadStatus status,
            Instant from, Instant to, int page, int size) {
        org.springframework.data.domain.PageRequest pageable =
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "firstSeenAt"));
        var result = jpaRepository.findAll(
                crmSpec(merchantId, botIds, q, botId, status, from, to), pageable);
        List<BotLead> items = result.getContent().stream().map(BotLeadMapper::toDomain).toList();
        return com.foxcash.domain.model.PageResult.of(items, page, size, result.getTotalElements());
    }

    @Override
    public Map<com.foxcash.domain.model.crm.CrmLeadStatus, Long> countByStatusForMerchant(
            UUID merchantId, List<UUID> botIds, Instant from, Instant to) {
        Map<com.foxcash.domain.model.crm.CrmLeadStatus, Long> result =
                new java.util.EnumMap<>(com.foxcash.domain.model.crm.CrmLeadStatus.class);
        // Specification sem filtro de status (período opcional) → agrupa em
        // memória por status (cards do painel — Spec 83 C4).
        for (BotLeadJpaEntity entity : jpaRepository.findAll(
                crmSpec(merchantId, botIds, null, null, null, from, to))) {
            com.foxcash.domain.model.crm.CrmLeadStatus s =
                    com.foxcash.domain.model.crm.CrmLeadStatus.valueOf(entity.getStatus());
            result.merge(s, 1L, Long::sum);
        }
        return result;
    }

    /**
     * Specification condicional (padrão Spec 89 §1): predicates só entram com
     * filtro não-nulo — evita o {@code ? IS NULL OR} que quebra no Postgres por
     * inferência de tipo dos parâmetros. Escopo do lojista via subquery em
     * {@code store_bots} (bot_leads não tem merchant_id).
     */
    private static org.springframework.data.jpa.domain.Specification<BotLeadJpaEntity> crmSpec(
            UUID merchantId, List<UUID> botIds, String q, UUID botId,
            com.foxcash.domain.model.crm.CrmLeadStatus status,
            Instant from, Instant to) {
        return (root, cq, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            jakarta.persistence.criteria.Subquery<UUID> botSub = cq.subquery(UUID.class);
            jakarta.persistence.criteria.Root<com.foxcash.infrastructure.outbound.persistence.entity.StoreBotJpaEntity> botRoot =
                    botSub.from(com.foxcash.infrastructure.outbound.persistence.entity.StoreBotJpaEntity.class);
            botSub.select(botRoot.get("id")).where(cb.equal(botRoot.get("merchantId"), merchantId));
            predicates.add(root.get("botId").in(botSub));
            // Spec 124: botIds do perfil ativo (null → todos os bots do lojista;
            // lista vazia → sem leads — header inválido nunca vaza).
            if (botIds != null) {
                predicates.add(root.get("botId").in(botIds));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        // Spec 119: busca por telefone também (além de nome/@/e-mail/ID).
                        cb.like(cb.lower(root.get("phone")), like),
                        cb.like(root.get("platformUserId"), like)));
            }
            if (botId != null) {
                predicates.add(cb.equal(root.get("botId"), botId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status.name()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("firstSeenAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("firstSeenAt"), to));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
