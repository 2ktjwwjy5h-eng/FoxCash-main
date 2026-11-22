package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.master.MerchantPanelSearchQuery;
import com.foxcash.domain.model.master.MerchantPanelTransactionQuery;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.MerchantPanelQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelDetail;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelItem;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelTransaction;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantStoreResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Adaptador REST: painel de usuarios do Master Console (Spec 97) — endpoints
 * ADITIVOS sob {@code ROLE_MASTER} (o {@code GET /api/v1/master/merchants} da
 * Spec 71, o {@code /master/transactions} da Spec 15 e o
 * {@code /master/withdrawals} permanecem intactos). Somente-leitura; escrita/
 * controle = Spec 98.
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterPanelController {

    private final MerchantPanelQueryUseCase panelQuery;

    public MasterPanelController(MerchantPanelQueryUseCase panelQuery) {
        this.panelQuery = panelQuery;
    }

    /** Listagem com metricas de risco (MED%/conversao/Pix pagos/gateway) — D3.1. */
    @GetMapping("/merchants/panel")
    public PageResponse<MerchantPanelItem> panel(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String risk,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        MerchantPanelSearchQuery query = new MerchantPanelSearchQuery(
                QueryParamParser.parseEnum(MerchantStatus.class, status, "status"),
                q,
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.parseEnum(MerchantPanelSearchQuery.RiskLevel.class, risk, "risk"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(panelQuery.searchPanel(query), Function.identity());
    }

    /** Perfil detalhado do lojista (KPIs + dados da conta + taxas/limites) — D3.2. */
    @GetMapping("/merchants/{merchantId}/panel")
    public MerchantPanelDetail panelDetail(@PathVariable UUID merchantId) {
        return panelQuery.panelDetail(merchantId);
    }

    /** Transacoes do lojista com dados de venda (comprador/produto/liquido) — D3.3. */
    @GetMapping("/merchants/{merchantId}/transactions")
    public PageResponse<MerchantPanelTransaction> transactions(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        MerchantPanelTransactionQuery query = new MerchantPanelTransactionQuery(
                QueryParamParser.parseEnum(TransactionStatus.class, status, "status"),
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(panelQuery.transactions(merchantId, query), Function.identity());
    }

    /** Stores do lojista (espelho read-only — "Ver Shops") — D3.4. */
    @GetMapping("/merchants/{merchantId}/stores")
    public List<MerchantStoreResponse> stores(@PathVariable UUID merchantId) {
        return panelQuery.stores(merchantId);
    }
}
