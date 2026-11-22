package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.BroadcastMediaService;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.port.in.BroadcastManagementUseCase;
import com.foxcash.domain.port.in.BroadcastQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastDetailResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastLogResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastMediaResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastRequest;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: broadcasts no portal (Spec 79), {@code ROLE_MERCHANT_PORTAL}
 * (principal = merchantId). Privado por lojista — broadcast de outro lojista →
 * 404 {@code broadcast_not_found} (sem revelar existência). O disparo NUNCA é
 * síncrono: criar/agendar persiste e responde imediatamente; cancelar
 * (DELETE) é permitido apenas em {@code PENDING} (409 se PROCESSING).
 */
@RestController
@RequestMapping("/api/v1/portal/broadcasts")
public class PortalBroadcastController {

    private final BroadcastManagementUseCase broadcasts;
    private final BroadcastQueryUseCase broadcastQueries;
    private final BroadcastMediaService mediaService;
    private final ProfileScopeResolver scopeResolver;

    public PortalBroadcastController(BroadcastManagementUseCase broadcasts,
                                     BroadcastQueryUseCase broadcastQueries,
                                     BroadcastMediaService mediaService,
                                     ProfileScopeResolver scopeResolver) {
        this.broadcasts = broadcasts;
        this.broadcastQueries = broadcastQueries;
        this.mediaService = mediaService;
        this.scopeResolver = scopeResolver;
    }

    @GetMapping
    public List<BroadcastResponse> list(Authentication authentication,
                                        HttpServletRequest httpRequest,
                                        @RequestParam(required = false) String mode,
                                        @RequestParam(required = false) String status) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // Spec 124 (bot-set): null → todos os broadcasts do lojista (sem header);
        // perfil ativo → só os de bots do perfil; header inválido → vazio.
        return broadcastQueries.list(merchantId,
                        scopeResolver.scopeBotIds(merchantId, httpRequest),
                        parseMode(mode), parseStatus(status))
                .stream().map(BroadcastResponse::from).toList();
    }

    @GetMapping("/{id}")
    public BroadcastDetailResponse get(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        List<BroadcastLogResponse> logs = broadcastQueries.logs(merchantId, id).stream()
                .map(BroadcastLogResponse::from).toList();
        return BroadcastDetailResponse.from(broadcastQueries.get(merchantId, id), logs);
    }

    @PostMapping
    public ResponseEntity<BroadcastResponse> create(Authentication authentication,
                                                    @RequestBody BroadcastRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BroadcastResponse.from(broadcasts.create(merchantId, request.toCommand())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        broadcasts.cancel(merchantId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Upload de mídia do broadcast (D7): valida tipo/tamanho (imagem ≤10MB;
     * vídeo/áudio ≤50MB) e devolve {@code {media_url, media_file_id,
     * media_type}} — Telegram reusa o {@code file_id} da CDN nos disparos (sem
     * re-upload por destinatário); Discord devolve a URL da CDN.
     */
    @PostMapping("/media")
    public ResponseEntity<BroadcastMediaResponse> uploadMedia(Authentication authentication,
                                                              @RequestParam("storeBotId") UUID storeBotId,
                                                              @RequestParam("mediaType") String mediaType,
                                                              @RequestPart("file") MultipartFile file) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        BroadcastMediaService.MediaUploadResult result =
                mediaService.upload(merchantId, storeBotId, mediaType, readBytes(file),
                        file.getOriginalFilename() == null ? "media.bin" : file.getOriginalFilename());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new BroadcastMediaResponse(result.mediaUrl(), result.mediaFileId(),
                        result.mediaType()));
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("falha ao ler o arquivo de midia");
        }
    }

    private static BroadcastMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BroadcastMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode invalido: " + raw);
        }
    }

    private static BroadcastStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BroadcastStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status invalido: " + raw);
        }
    }
}
