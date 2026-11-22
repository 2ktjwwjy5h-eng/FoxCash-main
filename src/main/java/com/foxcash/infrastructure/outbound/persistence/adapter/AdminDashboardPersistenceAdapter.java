package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.admin.DashboardSummary;
import com.foxcash.domain.model.admin.InvoiceSearchQuery;
import com.foxcash.domain.model.admin.IpLookupResult;
import com.foxcash.domain.model.admin.MerchantSearchQuery;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.billing.InvoiceStatus;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.AdminDashboardPort;
import com.foxcash.infrastructure.outbound.persistence.entity.InvoiceJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.InvoiceMapper;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.InvoiceJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.PixTransactionJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link AdminDashboardPort} — consultas do dashboard.
 * Filtros dinâmicos via Specifications; somas calculadas no PostgreSQL
 * (SUM/COALESCE).
 */
@Repository
public class AdminDashboardPersistenceAdapter implements AdminDashboardPort {

    private final MerchantJpaRepository merchantRepository;
    private final InvoiceJpaRepository invoiceRepository;
    private final PixTransactionJpaRepository transactionRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public AdminDashboardPersistenceAdapter(MerchantJpaRepository merchantRepository,
                                            InvoiceJpaRepository invoiceRepository,
                                            PixTransactionJpaRepository transactionRepository,
                                            EntityManager entityManager,
                                            ObjectMapper objectMapper) {
        this.merchantRepository = merchantRepository;
        this.invoiceRepository = invoiceRepository;
        this.transactionRepository = transactionRepository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<Merchant> findMerchants(MerchantSearchQuery query) {
        Page<MerchantJpaEntity> page = merchantRepository.findAll(
                merchantSpec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Merchant> items = page.getContent().stream().map(MerchantMapper::toDomain).toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    @Override
    public PageResult<Invoice> findInvoices(InvoiceSearchQuery query) {
        Page<InvoiceJpaEntity> page = invoiceRepository.findAll(
                invoiceSpec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Invoice> items = page.getContent().stream().map(InvoiceMapper::toDomain).toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    @Override
    public DashboardSummary summary() {
        DashboardSummary.MerchantStats merchantStats = new DashboardSummary.MerchantStats(
                merchantRepository.count(),
                merchantRepository.countByStatus(MerchantStatus.ACTIVE),
                merchantRepository.countByStatus(MerchantStatus.SUSPENDED),
                merchantRepository.countByStatus(MerchantStatus.INACTIVE));

        DashboardSummary.TransactionStats transactionStats = new DashboardSummary.TransactionStats(
                transactionRepository.count(),
                transactionRepository.countByStatus(TransactionStatus.APPROVED),
                transactionRepository.countByStatus(TransactionStatus.PENDING),
                transactionRepository.countByStatus(TransactionStatus.REJECTED),
                transactionRepository.countByStatus(TransactionStatus.CANCELLED),
                transactionRepository.countByStatus(TransactionStatus.FAILED),
                transactionRepository.countByStatus(TransactionStatus.REFUNDED),
                Money.of(transactionRepository.sumAmountByStatus(TransactionStatus.APPROVED)));

        Set<InvoiceStatus> issued = Set.of(InvoiceStatus.PENDING, InvoiceStatus.PAID, InvoiceStatus.OVERDUE);
        DashboardSummary.InvoiceStats invoiceStats = new DashboardSummary.InvoiceStats(
                invoiceRepository.count(),
                invoiceRepository.countByStatus(InvoiceStatus.PENDING),
                invoiceRepository.countByStatus(InvoiceStatus.PAID),
                invoiceRepository.countByStatus(InvoiceStatus.OVERDUE),
                invoiceRepository.countByStatus(InvoiceStatus.CANCELLED),
                Money.of(invoiceRepository.sumTotalAmountByStatuses(issued)));

        return new DashboardSummary(merchantStats, transactionStats, invoiceStats);
    }

    /**
     * Lookup de lojista(s) por IP (Spec 67 — {@code !ip}): lojistas associados via
     * {@code pix_transactions.buyer_ip} (comprador na cobrança) ou
     * {@code platform_activity_log.ip} (origem do ator na trilha), com resumo de
     * vendas aprovadas e o bot de vendas da loja (quando ativo).
     */
    @Override
    @SuppressWarnings("unchecked")
    public IpLookupResult lookupByIp(String ip) {
        // 1. Lojistas por IP (união cobrança + trilha) — design §7.
        List<UUID> merchantIds = (List<UUID>) entityManager.createNativeQuery(
                        "SELECT DISTINCT merchant_id FROM ("
                                + "  SELECT pt.merchant_id FROM pix_transactions pt WHERE pt.buyer_ip = :ip"
                                + "  UNION"
                                + "  SELECT pal.merchant_id FROM platform_activity_log pal"
                                + "    WHERE pal.ip = :ip AND pal.merchant_id IS NOT NULL"
                                + ") t WHERE merchant_id IS NOT NULL",
                        UUID.class)
                .setParameter("ip", ip)
                .getResultList();
        if (merchantIds.isEmpty()) {
            return new IpLookupResult(ip, List.of());
        }

        // 2. Resumo de vendas aprovadas (uma query p/ todos os lojistas) —
        //    inclui a janela dos últimos 7 dias (Spec 67 — !ip mostra o
        //    faturamento da última semana). Mesmo padrão de `now() - interval`
        //    das queries do bot (sem ambigüidade de timezone).
        Map<UUID, SalesSummary> salesByMerchant = new HashMap<>();
        for (Object[] row : (List<Object[]>) entityManager.createNativeQuery(
                        "SELECT pt.merchant_id, COUNT(*) AS total, COALESCE(AVG(pt.amount), 0) AS ticket,"
                                + " MAX(pt.paid_at) AS ultima,"
                                + " COUNT(*) FILTER (WHERE pt.paid_at >= now() - interval '7 days') AS total7d,"
                                + " COALESCE(SUM(pt.amount) FILTER (WHERE pt.paid_at >= now() - interval '7 days'), 0) AS revenue7d"
                                + " FROM pix_transactions pt"
                                + " WHERE pt.merchant_id IN (:ids) AND pt.status = 'APPROVED'"
                                + " GROUP BY pt.merchant_id")
                .setParameter("ids", merchantIds)
                .getResultList()) {
            salesByMerchant.put((UUID) row[0],
                    new SalesSummary(((Number) row[1]).longValue(),
                            Money.of((BigDecimal) row[2]),
                            toInstant(row[3]),
                            ((Number) row[4]).longValue(),
                            Money.of((BigDecimal) row[5])));
        }

        // 3. Store/bot (plans ativos + webhooks ativos + social_links p/ o link).
        Map<UUID, BotMatch> botByMerchant = new HashMap<>();
        for (Object[] row : (List<Object[]>) entityManager.createNativeQuery(
                        "SELECT s.merchant_id, s.id AS store_id, s.name AS store_name, s.social_links,"
                                + "       (SELECT COUNT(*) FROM plans p WHERE p.store_id = s.id AND p.active = TRUE) AS active_plans,"
                                + "       (SELECT COUNT(*) FROM merchant_webhooks w"
                                + "          WHERE w.merchant_id = s.merchant_id AND w.status = 'ACTIVE') AS active_webhooks"
                                + " FROM stores s WHERE s.merchant_id IN (:ids)")
                .setParameter("ids", merchantIds)
                .getResultList()) {
            UUID merchantId = (UUID) row[0];
            int activePlans = ((Number) row[4]).intValue();
            int activeWebhooks = ((Number) row[5]).intValue();
            // Bot ativo = loja existe E (≥1 plano ativo OU ≥1 webhook ativo).
            if (activePlans > 0 || activeWebhooks > 0) {
                botByMerchant.put(merchantId, new BotMatch(
                        (UUID) row[1],
                        (String) row[2],
                        activePlans,
                        activeWebhooks,
                        botLink((String) row[3])));
            }
        }

        // 4. Detalhes do lojista (nome/e-mail/status).
        Map<UUID, MerchantJpaEntity> merchantsById = new HashMap<>();
        for (MerchantJpaEntity merchant : merchantRepository.findAllById(merchantIds)) {
            merchantsById.put(merchant.getId(), merchant);
        }

        List<IpLookupResult.MerchantMatch> matches = new ArrayList<>();
        for (UUID merchantId : merchantIds) {
            MerchantJpaEntity merchant = merchantsById.get(merchantId);
            if (merchant == null) {
                continue; // lojista excluído entre as queries — sem dado para exibir
            }
            SalesSummary sales = salesByMerchant.get(merchantId);
            BotMatch bot = botByMerchant.get(merchantId);
            matches.add(new IpLookupResult.MerchantMatch(
                    merchantId,
                    merchant.getName(),
                    merchant.getEmail(),
                    merchant.getStatus().name(),
                    sales != null ? sales.totalSales() : 0,
                    sales != null ? sales.averageTicket() : Money.of(BigDecimal.ZERO),
                    sales != null ? sales.lastSaleAt() : null,
                    sales != null ? sales.sales7d() : 0,
                    sales != null ? sales.revenue7d() : Money.of(BigDecimal.ZERO),
                    bot != null ? new IpLookupResult.MerchantMatch.BotMatch(
                            bot.storeId(), bot.storeName(), bot.activePlans(),
                            bot.activeWebhooks(), bot.link()) : null));
        }
        return new IpLookupResult(ip, matches);
    }

    /** Extrai o primeiro link de bot (Discord invite / Telegram) do JSON {@code social_links}. */
    private String botLink(String socialLinksJson) {
        if (socialLinksJson == null || socialLinksJson.isBlank()) {
            return null;
        }
        try {
            List<Map<String, String>> links = objectMapper.readValue(
                    socialLinksJson, new TypeReference<>() {
                    });
            for (Map<String, String> link : links) {
                String url = link.get("url");
                if (url != null && (url.contains("discord.gg") || url.contains("discord.com/invite")
                        || url.contains("t.me") || url.contains("telegram.me"))) {
                    return url;
                }
            }
        } catch (JsonProcessingException e) {
            // social_links malformado no banco — não derruba o lookup (sem link).
        }
        return null;
    }

    /** Resumo agregado de vendas aprovadas de um lojista (query 2). */
    private record SalesSummary(long totalSales, Money averageTicket, Instant lastSaleAt,
                                long sales7d, Money revenue7d) {
    }

    /** Store/bot ativo de um lojista (query 3). */
    private record BotMatch(UUID storeId, String storeName, int activePlans, int activeWebhooks, String link) {
    }

    /** Colunas {@code TIMESTAMP} de queries nativas voltam como {@link java.sql.Timestamp}. */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof java.sql.Timestamp timestamp
                ? timestamp.toInstant()
                : (Instant) value;
    }

    private Specification<MerchantJpaEntity> merchantSpec(MerchantSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.status() != null) {
                predicates.add(cb.equal(root.get("status"), query.status()));
            }
            if (query.query() != null && !query.query().isBlank()) {
                String like = "%" + query.query().trim().toLowerCase() + "%";
                // Spec 39: trading_name/legal_name foram substituídos por um único
                // `name` (V31) — a busca textual agora filtra apenas por `name`.
                predicates.add(cb.like(cb.lower(root.get("name")), like));
            }
            if (query.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.createdFrom()));
            }
            if (query.createdTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), query.createdTo()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Specification<InvoiceJpaEntity> invoiceSpec(InvoiceSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.merchantId() != null) {
                predicates.add(cb.equal(root.get("merchantId"), query.merchantId()));
            }
            if (query.status() != null) {
                predicates.add(cb.equal(root.get("status"), query.status()));
            }
            if (query.period() != null) {
                predicates.add(cb.equal(root.get("billingPeriod"), query.period().yearMonth().toString()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
