package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.admin.DashboardSummary;
import com.foxcash.domain.model.admin.InvoiceSearchQuery;
import com.foxcash.domain.model.admin.IpLookupResult;
import com.foxcash.domain.model.admin.MerchantSearchQuery;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.merchant.Merchant;

/**
 * Porta de entrada: consultas administrativas (dashboard).
 */
public interface AdminDashboardUseCase {

    PageResult<Merchant> searchMerchants(MerchantSearchQuery query);

    PageResult<Invoice> searchInvoices(InvoiceSearchQuery query);

    DashboardSummary summary();

    /**
     * Lookup de lojista(s) por endereço IP (Spec 67 — comando {@code !ip} do bot
     * de operações): associa o IP a lojistas via {@code pix_transactions.buyer_ip}
     * ou {@code platform_activity_log.ip} e devolve um resumo de vendas + o bot de
     * vendas da loja (quando ativo).
     */
    IpLookupResult lookupByIp(String ip);
}
