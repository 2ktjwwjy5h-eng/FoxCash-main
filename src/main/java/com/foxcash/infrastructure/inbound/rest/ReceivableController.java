package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableSearchQuery;
import com.foxcash.domain.model.billing.ReceivableStatus;
import com.foxcash.domain.port.in.InvoiceCollectionUseCase;
import com.foxcash.domain.port.in.QueryReceivableUseCase;
import com.foxcash.domain.port.in.ReceivableWriteOffUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.CollectionResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ReceivableResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WriteOffRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WriteOffResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Adaptador REST: faturamento e contas a receber (Spec 33). Role
 * {@code ROLE_MASTER} (Spec 13).
 *
 * <p>{@code POST .../invoices/{invoiceId}/collect} cobra a fatura na hora
 * (manual — o job diario faz o mesmo) e audita {@code INVOICE_COLLECT}
 * (Spec 37); {@code POST /receivables/{id}/write-off} baixa uma divida como
 * perda (Spec 34, motivo obrigatorio, auditado); {@code GET /receivables} lista
 * todas as dividas com filtros; {@code GET /merchants/{id}/receivables} lista
 * as do lojista.
 */
@RestController
@RequestMapping("/api/v1/master")
public class ReceivableController {

    private final InvoiceCollectionUseCase invoiceCollection;
    private final QueryReceivableUseCase queryReceivable;
    private final ReceivableWriteOffUseCase writeOff;
    private final ClientIpResolver clientIpResolver;

    public ReceivableController(InvoiceCollectionUseCase invoiceCollection,
                                QueryReceivableUseCase queryReceivable,
                                ReceivableWriteOffUseCase writeOff,
                                ClientIpResolver clientIpResolver) {
        this.invoiceCollection = invoiceCollection;
        this.queryReceivable = queryReceivable;
        this.writeOff = writeOff;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/merchants/{merchantId}/invoices/{invoiceId}/collect")
    public ResponseEntity<CollectionResponse> collect(@PathVariable UUID merchantId,
                                                      @PathVariable UUID invoiceId,
                                                      Authentication authentication,
                                                      HttpServletRequest httpRequest) {
        InvoiceCollectionUseCase.CollectionResult result = invoiceCollection.collect(
                merchantId, invoiceId, (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest), Instant.now());
        return ResponseEntity.ok(CollectionResponse.from(result));
    }

    @PostMapping("/receivables/{receivableId}/write-off")
    public ResponseEntity<WriteOffResponse> writeOff(@PathVariable UUID receivableId,
                                                     @Valid @RequestBody WriteOffRequest request,
                                                     Authentication authentication,
                                                     HttpServletRequest httpRequest) {
        ReceivableWriteOffUseCase.MoneyOutcome outcome = writeOff.writeOff(
                (UUID) authentication.getPrincipal(), receivableId, request.reason(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(WriteOffResponse.from(outcome));
    }

    @GetMapping("/receivables")
    public PageResponse<ReceivableResponse> search(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReceivableSearchQuery query = new ReceivableSearchQuery(
                QueryParamParser.parseUuid(merchantId, "merchantId"),
                QueryParamParser.parseEnum(ReceivableStatus.class, status, "status"),
                QueryParamParser.parseEnum(ReceivableOrigin.class, origin, "origin"),
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(queryReceivable.search(query), ReceivableResponse::from);
    }

    @GetMapping("/merchants/{merchantId}/receivables")
    public PageResponse<ReceivableResponse> listByMerchant(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ReceivableSearchQuery query = new ReceivableSearchQuery(
                merchantId,
                QueryParamParser.parseEnum(ReceivableStatus.class, status, "status"),
                QueryParamParser.parseEnum(ReceivableOrigin.class, origin, "origin"),
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(queryReceivable.search(query), ReceivableResponse::from);
    }
}
