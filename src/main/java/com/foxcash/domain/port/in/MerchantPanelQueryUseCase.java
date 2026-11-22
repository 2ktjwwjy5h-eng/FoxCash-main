package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.master.MerchantPanelSearchQuery;
import com.foxcash.domain.model.master.MerchantPanelTransactionQuery;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelDetail;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelItem;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelTransaction;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantStoreResponse;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: consultas do painel de usuarios do master (Spec 97) —
 * somente-leitura, {@code ROLE_MASTER}. Compoe dados existentes (merchants,
 * pix_transactions, chargebacks, bot_leads, plans, stores, wallet) em
 * respostas ricas; nada de escrita aqui (Spec 98).
 *
 * <p>Os tipos de retorno sao os read-models da resposta REST (records do
 * pacote dto) — o service da aplicacao monta os DTOs a partir das
 * agregacoes (design D4: "monta DTOs").
 */
public interface MerchantPanelQueryUseCase {

    /** Listagem paginada com metricas de risco (MED%/conversao/Pix pagos/gateway). */
    PageResult<MerchantPanelItem> searchPanel(MerchantPanelSearchQuery query);

    /** Perfil detalhado do lojista (KPIs + dados da conta + taxas/limites); 404 se inexistente. */
    MerchantPanelDetail panelDetail(UUID merchantId);

    /** Transacoes do lojista com dados de venda (comprador/produto/liquido); 404 se inexistente. */
    PageResult<MerchantPanelTransaction> transactions(UUID merchantId, MerchantPanelTransactionQuery query);

    /** Stores do lojista (espelho read-only); 404 se inexistente, [] sem store. */
    List<MerchantStoreResponse> stores(UUID merchantId);
}
