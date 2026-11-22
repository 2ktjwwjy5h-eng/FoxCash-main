package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.Money;
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
import com.foxcash.domain.port.out.AdminDashboardPort;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do AdminDashboardService (Spec 8: dashboard/admin — endpoints de
 * consulta). O serviço apenas delega para o port de saída; a lógica de filtros
 * (Specifications) e agregação fica no adapter JPA. O {@link #lookupByIp} (Spec
 * 67) também registra a trilha {@code IP_LOOKUP}.
 */
class AdminDashboardServiceTest {

    private final AdminDashboardPort adminDashboardPort = mock(AdminDashboardPort.class);
    private final PlatformActivityRepository activityRepository = mock(PlatformActivityRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AdminDashboardService service =
            new AdminDashboardService(adminDashboardPort, activityRepository, objectMapper);

    @Test
    void searchMerchantsDelegatesToPort() {
        MerchantSearchQuery query = new MerchantSearchQuery(null, "loja", null, null, 0, 20);
        PageResult<Merchant> expected = PageResult.of(List.of(mock(Merchant.class)), 0, 20, 1);
        when(adminDashboardPort.findMerchants(query)).thenReturn(expected);

        PageResult<Merchant> result = service.searchMerchants(query);

        assertSame(expected, result);
    }

    @Test
    void searchInvoicesDelegatesToPort() {
        InvoiceSearchQuery query = new InvoiceSearchQuery(null, null, null, 0, 20);
        PageResult<Invoice> expected = PageResult.of(List.of(mock(Invoice.class)), 0, 20, 1);
        when(adminDashboardPort.findInvoices(query)).thenReturn(expected);

        PageResult<Invoice> result = service.searchInvoices(query);

        assertSame(expected, result);
    }

    @Test
    void summaryDelegatesToPort() {
        DashboardSummary expected = new DashboardSummary(
                new DashboardSummary.MerchantStats(2, 1, 1, 0),
                new DashboardSummary.TransactionStats(5, 4, 1, 0, 0, 0, 0, Money.of("100.00")),
                new DashboardSummary.InvoiceStats(2, 1, 1, 0, 0, Money.of("139.90")));
        when(adminDashboardPort.summary()).thenReturn(expected);

        DashboardSummary result = service.summary();

        assertSame(expected, result);
    }

    @Test
    void pageResultComputesTotalPages() {
        PageResult<String> result = PageResult.of(List.of("a"), 1, 20, 21);

        assertEquals(21, result.totalElements());
        assertEquals(2, result.totalPages());
    }

    @Test
    void lookupByIpDelegatesAndRecordsIpLookup() {
        String ip = "200.150.10.20";
        IpLookupResult expected = new IpLookupResult(ip, List.of(
                new IpLookupResult.MerchantMatch(
                        UUID.randomUUID(), "Loja A", "loja@example.com", "ACTIVE",
                        12, Money.of("35.50"), Instant.parse("2026-08-10T12:00:00Z"),
                        4, Money.of("142.00"),
                        new IpLookupResult.MerchantMatch.BotMatch(
                                UUID.randomUUID(), "Loja A Store", 3, 1,
                                "https://discord.gg/abc"))));
        when(adminDashboardPort.lookupByIp(ip)).thenReturn(expected);

        IpLookupResult result = service.lookupByIp(ip);

        assertSame(expected, result);
        ArgumentCaptor<PlatformActivity> captor = ArgumentCaptor.forClass(PlatformActivity.class);
        verify(activityRepository).save(captor.capture());
        PlatformActivity activity = captor.getValue();
        assertEquals(PlatformActorType.SYSTEM, activity.actorType());
        assertEquals(PlatformAction.IP_LOOKUP, activity.action());
        assertEquals("IP", activity.entityType());
        assertEquals(ip, activity.ip());
        assertEquals("{\"count\":1,\"merchants\":[{\"id\":\"" + expected.matches().get(0).merchantId()
                + "\",\"name\":\"Loja A\",\"status\":\"ACTIVE\"}]}", activity.details());
    }

    @Test
    void lookupByIpWithoutMatchesRecordsEmptyDetails() {
        String ip = "192.168.0.1";
        when(adminDashboardPort.lookupByIp(ip)).thenReturn(new IpLookupResult(ip, List.of()));

        IpLookupResult result = service.lookupByIp(ip);

        assertEquals(0, result.matches().size());
        ArgumentCaptor<PlatformActivity> captor = ArgumentCaptor.forClass(PlatformActivity.class);
        verify(activityRepository).save(captor.capture());
        PlatformActivity activity = captor.getValue();
        assertEquals(PlatformAction.IP_LOOKUP, activity.action());
        assertEquals(ip, activity.ip());
        assertEquals("{\"count\":0,\"merchants\":[]}", activity.details());
    }

    @Test
    void lookupByIpDoesNotExposeSensitiveMerchantDataInDetails() {
        String ip = "203.0.113.7";
        IpLookupResult result = new IpLookupResult(ip, List.of(
                new IpLookupResult.MerchantMatch(
                        UUID.randomUUID(), "Loja", "loja@example.com", "ACTIVE",
                        1, Money.of("10.00"), null, 1, Money.of("10.00"), null)));
        when(adminDashboardPort.lookupByIp(ip)).thenReturn(result);

        service.lookupByIp(ip);

        ArgumentCaptor<PlatformActivity> captor = ArgumentCaptor.forClass(PlatformActivity.class);
        verify(activityRepository).save(captor.capture());
        String details = captor.getValue().details();
        // Sem e-mail/chaves na trilha — só id/nome/status (design §7).
        assertEquals(false, details.contains("loja@example.com"));
        assertEquals(false, details.contains("10.00"));
    }
}
