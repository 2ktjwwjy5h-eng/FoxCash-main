package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.BroadcastChatService;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.StoreBotManagementUseCase;
import com.foxcash.domain.port.out.TelegramBotApi;
import com.foxcash.infrastructure.inbound.rest.dto.BotSetupRequest;
import com.foxcash.infrastructure.inbound.rest.dto.BotSetupResponse;
import com.foxcash.infrastructure.inbound.rest.dto.StoreBotRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreBotResponse;
import com.foxcash.infrastructure.inbound.rest.dto.StoreBotUpdateRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ValidateBotTokenRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ValidateBotTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: registro de bots de venda no portal (Spec 74),
 * {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Privado por lojista —
 * bot de outro lojista → 404 {@code bot_not_found} (sem revelar existência).
 * Respostas nunca incluem o token (só {@code tokenMask}); transições inválidas
 * → 409 {@code invalid_bot_state}.
 *
 * <p>Specs 77/78 (wizard): {@code POST /validate} (pré-valida o token via {@code
 * getMe} sem criar registro — Telegram ou Discord via {@code platform}) e
 * {@code POST /setup} (configuração inicial opcional — foto/avatar/descrição/
 * comandos slash — via Bot API, sem gravar no FoxCash).</p>
 */
@RestController
@RequestMapping("/api/v1/portal/bots")
public class PortalBotController {

    private final StoreBotManagementUseCase bots;
    private final BroadcastChatService chatService;
    private final ProfileScopeResolver scopeResolver;

    public PortalBotController(StoreBotManagementUseCase bots,
                               BroadcastChatService chatService,
                               ProfileScopeResolver scopeResolver) {
        this.bots = bots;
        this.chatService = chatService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Spec 79/D5 (destino dinâmico do broadcast): canais/grupos/guilds onde o
     * bot tem permissão, carregados da plataforma via integração da Spec 74
     * (cache curto TTL ~60s — não martela a API da plataforma).
     */
    @GetMapping("/{id}/chats")
    public List<BroadcastChatService.Chat> listChats(Authentication authentication,
                                                     @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return chatService.listChats(merchantId, id);
    }

    @GetMapping
    public List<StoreBotResponse> list(Authentication authentication,
                                       HttpServletRequest httpRequest,
                                       @RequestParam(required = false) String platform,
                                       @RequestParam(required = false) String status) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // Spec 124 (frota do perfil): header presente → só os bots do perfil;
        // sem header → todos os bots do lojista (regressão).
        UUID profileId = scopeResolver.scopeProfileId(merchantId, httpRequest).orElse(null);
        return bots.list(merchantId, profileId, parsePlatform(platform), parseStatus(status))
                .stream().map(StoreBotResponse::from).toList();
    }

    @GetMapping("/{id}")
    public StoreBotResponse get(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return StoreBotResponse.from(bots.get(merchantId, id));
    }

    @PostMapping
    public ResponseEntity<StoreBotResponse> register(Authentication authentication,
                                                     @RequestBody StoreBotRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        StoreBot bot = bots.register(merchantId, toCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(StoreBotResponse.from(bot));
    }

    @PutMapping("/{id}")
    public StoreBotResponse update(Authentication authentication, @PathVariable UUID id,
                                   @RequestBody StoreBotUpdateRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return StoreBotResponse.from(bots.update(merchantId, id, request.displayName()));
    }

    @PostMapping("/{id}/pause")
    public StoreBotResponse pause(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return StoreBotResponse.from(bots.pause(merchantId, id));
    }

    @PostMapping("/{id}/resume")
    public StoreBotResponse resume(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return StoreBotResponse.from(bots.resume(merchantId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        bots.remove(merchantId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Specs 77/78 (wizard): pré-valida o token do bot via {@code getMe} SEM
     * criar registro — devolve nome/@ (Telegram) ou username (Discord) para a
     * verificação em tempo real. Token inválido/expirado → 400
     * {@code telegram_api_error}/{@code discord_api_error}.
     */
    @PostMapping("/validate")
    public ValidateBotTokenResponse validate(Authentication authentication,
                                             @RequestBody ValidateBotTokenRequest request) {
        if (request.isDiscord()) {
            com.foxcash.domain.port.out.DiscordBotApi.DiscordUserInfo me =
                    bots.validateDiscordToken(request.token());
            return new ValidateBotTokenResponse(me.displayName(), me.username());
        }
        TelegramBotApi.TelegramUserInfo me = bots.validateToken(request.token());
        return new ValidateBotTokenResponse(me.displayName(), me.username());
    }

    /**
     * Specs 77/78 (wizard): configuração inicial opcional do bot (Telegram:
     * foto/descrição; Discord: avatar/descrição + comandos slash) via Bot API —
     * stateless, nada é gravado no FoxCash.
     */
    @PostMapping("/setup")
    public BotSetupResponse setup(Authentication authentication,
                                  @RequestBody BotSetupRequest request) {
        if (request.isDiscord()) {
            bots.applyDiscordSetup(new StoreBotManagementUseCase.DiscordSetupCommand(
                    request.token(), request.applicationId(), request.description(),
                    decodeImage(request.avatarBase64(), "avatar")));
        } else {
            bots.applyTelegramSetup(new StoreBotManagementUseCase.TelegramSetupCommand(
                    request.token(), request.description(), request.shortDescription(),
                    decodeImage(request.photoBase64(), "foto"), "bot-photo.jpg"));
        }
        return new BotSetupResponse(true);
    }

    private static byte[] decodeImage(String base64, String label) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] image = Base64.getDecoder().decode(base64);
            // Telegram (setMyPhoto) e Discord (avatar): imagens ≤ 5MB — guarda
            // simples no adapter.
            if (image.length > 5 * 1024 * 1024) {
                throw new IllegalArgumentException(label + " excede 5MB");
            }
            return image;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " invalida (base64)");
        }
    }

    private StoreBotManagementUseCase.RegisterBotCommand toCommand(StoreBotRequest request) {
        return new StoreBotManagementUseCase.RegisterBotCommand(
                request.parsedPlatform(), request.token(), request.displayName(), request.applicationId());
    }

    private static BotPlatform parsePlatform(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BotPlatform.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("platform invalida: " + raw);
        }
    }

    private static BotStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BotStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status invalido: " + raw);
        }
    }
}
