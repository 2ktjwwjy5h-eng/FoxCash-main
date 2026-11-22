package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.admin.DashboardSummary;
import com.foxcash.domain.model.admin.InvoiceSearchQuery;
import com.foxcash.domain.model.admin.IpLookupResult;
import com.foxcash.domain.model.admin.MerchantSearchQuery;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.merchant.Merchant;

/**
 * Porta de saída: leitura agregada/consultas do dashboard/admin.
 */
public interface AdminDashboardPort {

    PageResult<Merchant> findMerchants(MerchantSearchQuery query);

    PageResult<Invoice> findInvoices(InvoiceSearchQuery query);

    DashboardSummary summary();

    /** Lookup de lojista(s) por endereço IP (Spec 67 — {@code !ip}). */
    IpLookupResult lookupByIp(String ip);
}
