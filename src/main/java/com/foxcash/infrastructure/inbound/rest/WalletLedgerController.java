package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.QueryWalletLedgerUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WalletLedgerEntryResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: consulta do ledger do wallet (Spec 27) — somente leitura.
 *
 * <p>Master: {@code GET /api/v1/master/merchants/{merchantId}/ledger} (auditoria
 * de qualquer lojista, role {@code ROLE_MASTER}). Portal: {@code GET
 * /api/v1/portal/wallet/ledger} (role {@code ROLE_MERCHANT_PORTAL} — só o
 * próprio; o principal define o lojista, sem parâmetro — impossível IDOR).
 * Parâmetros inválidos → 400 {@code invalid_argument}.
 */
@RestController
@RequestMapping("/api/v1")
public class WalletLedgerController {

    private final QueryWalletLedgerUseCase query;

    public WalletLedgerController(QueryWalletLedgerUseCase query) {
        this.query = query;
    }

    @GetMapping("/master/merchants/{merchantId}/ledger")
    public PageResponse<WalletLedgerEntryResponse> masterLedger(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(
                query.findByMerchantId(merchantId,
                        QueryParamParser.parseEnum(WalletLedgerEntryType.class, type, "type"),
                        QueryParamParser.parseInstant(from, "from"),
                        QueryParamParser.parseInstant(to, "to"),
                        QueryParamParser.clampPage(page),
                        QueryParamParser.clampSize(size)),
                WalletLedgerEntryResponse::from);
    }

    @GetMapping("/portal/wallet/ledger")
    public PageResponse<WalletLedgerEntryResponse> portalLedger(
            Authentication authentication,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return PageResponse.from(
                query.findByMerchantId(merchantId,
                        QueryParamParser.parseEnum(WalletLedgerEntryType.class, type, "type"),
                        QueryParamParser.parseInstant(from, "from"),
                        QueryParamParser.parseInstant(to, "to"),
                        QueryParamParser.clampPage(page),
                        QueryParamParser.clampSize(size)),
                WalletLedgerEntryResponse::from);
    }
}
