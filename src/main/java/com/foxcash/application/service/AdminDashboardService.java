package com.foxcash.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.admin.DashboardSummary;
import com.foxcash.domain.model.admin.InvoiceSearchQuery;
import com.foxcash.domain.model.admin.IpLookupResult;
import com.foxcash.domain.model.admin.MerchantSearchQuery;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.in.AdminDashboardUseCase;
import com.foxcash.domain.port.out.AdminDashboardPort;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aplicação: consultas administrativas (dashboard) — somente-leitura.
 *
 * <p>Exceção: {@link #lookupByIp(String)} grava a trilha {@code IP_LOOKUP} (Spec
 * 67) e roda em transação de escrita própria ({@code @Transactional} no método —
 * o {@code save} não pode ficar numa transação {@code readOnly}).
 */
@Service
@Transactional(readOnly = true)
public class AdminDashboardService implements AdminDashboardUseCase {

    private final AdminDashboardPort adminDashboardPort;
    private final PlatformActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    public AdminDashboardService(AdminDashboardPort adminDashboardPort,
                                 PlatformActivityRepository activityRepository,
                                 ObjectMapper objectMapper) {
        this.adminDashboardPort = adminDashboardPort;
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<Merchant> searchMerchants(MerchantSearchQuery query) {
        return adminDashboardPort.findMerchants(query);
    }

    @Override
    public PageResult<Invoice> searchInvoices(InvoiceSearchQuery query) {
        return adminDashboardPort.findInvoices(query);
    }

    @Override
    public DashboardSummary summary() {
        return adminDashboardPort.summary();
    }

    @Override
    @Transactional
    public IpLookupResult lookupByIp(String ip) {
        IpLookupResult result = adminDashboardPort.lookupByIp(ip);
        recordIpLookup(ip, result.matches());
        return result;
    }

    /**
     * Registra o {@code IP_LOOKUP} na trilha (Spec 41) com {@code ip} = IP
     * consultado (não o IP do request) e {@code details} sem dados sensíveis —
     * apenas contagem + id/nome/status dos lojistas.
     */
    private void recordIpLookup(String ip, List<IpLookupResult.MerchantMatch> matches) {
        List<Map<String, Object>> merchants = matches.stream()
                .map(m -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", m.merchantId());
                    entry.put("name", m.name());
                    entry.put("status", m.status());
                    return entry;
                })
                .toList();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", matches.size());
        details.put("merchants", merchants);
        try {
            String detailsJson = objectMapper.writeValueAsString(details);
            PlatformActivity activity = PlatformActivity.record(
                    PlatformActorType.SYSTEM, null, PlatformAction.IP_LOOKUP,
                    "IP", null, null, detailsJson, ip, Instant.now());
            activityRepository.save(activity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar details do IP_LOOKUP", e);
        }
    }
}
