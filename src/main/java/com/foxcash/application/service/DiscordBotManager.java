package com.foxcash.application.service;

import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.infrastructure.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gerenciador de conexão Discord (Spec 74/D6) — em memória, por instância de
 * app, mantém o estado de conexão por {@code store_bot_id}.
 *
 * <p><b>Decisão stateless (Spec 52):</b> o PostgreSQL é a fonte de verdade; a
 * conexão de gateway em si (discord.js, reconexão/backoff, heartbeat) vive no
 * renderizador — o backend registra o estado via REST ({@link DiscordBotApi}:
 * {@code GET /users/@me} valida o token, {@code PUT /applications/{id}/commands}
 * registra os comandos slash) e o job de reconciliação re-popula o estado em
 * memória a partir do banco após um restart. Sem pool multi-instância: o
 * gerenciador é por instância (1 réplica) — o gateway do Discord exige 1 conexão
 * por bot (decisão registrada no design).
 *
 * <p><b>Kill-switch</b> {@code foxcash.bots.discord-gateway-enabled} (default
 * {@code false} — off em dev/test): sem validação externa, o bot é marcado como
 * conectado localmente para o fluxo de registro não depender de gateway real.
 */
@Component
public class DiscordBotManager {

    private static final Logger log = LoggerFactory.getLogger(DiscordBotManager.class);

    /** Comandos slash default registrados no bot de venda (Spec 52/74). */
    static final List<DiscordBotApi.SlashCommand> DEFAULT_COMMANDS = List.of(
            new DiscordBotApi.SlashCommand("comprar", "Escolha um plano e compre"),
            new DiscordBotApi.SlashCommand("menu", "Veja o menu da loja"));

    private final DiscordBotApi discordApi;
    private final BotProperties properties;
    private final BotLeadService botLeadService;
    private final Set<UUID> connected = ConcurrentHashMap.newKeySet();

    public DiscordBotManager(DiscordBotApi discordApi, BotProperties properties,
                             BotLeadService botLeadService) {
        this.discordApi = discordApi;
        this.properties = properties;
        this.botLeadService = botLeadService;
    }

    /**
     * Spec 75 (D6): chegada de interação do usuário no bot Discord — registra o
     * lead (usuário único que inicia conversa), idempotente. Chamado pelo
     * renderizador (bots/) via {@code POST /api/v1/foxcash/bots/{id}/interaction}
     * (o backend é a fonte de verdade; o renderizador é stateless).
     */
    public void recordInteraction(UUID botId, String platformUserId) {
        botLeadService.recordLead(botId, platformUserId);
    }

    /**
     * Conecta o bot: valida o token ({@code getMe}) e registra os comandos
     * slash (quando {@code applicationId} informado). Retorna a identidade do
     * bot, ou {@code null} quando o gateway está desligado (kill-switch off —
     * dev/test: sem chamada externa).
     */
    public DiscordBotApi.DiscordUserInfo connect(UUID botId, String token, String applicationId) {
        if (!properties.isDiscordGatewayEnabled()) {
            connected.add(botId);
            log.info("Discord: gateway desligado (kill-switch) — bot={} registrado sem conexao externa", botId);
            return null;
        }
        DiscordBotApi.DiscordUserInfo info = discordApi.getMe(token);
        if (applicationId != null && !applicationId.isBlank()) {
            discordApi.putCommands(applicationId, token, DEFAULT_COMMANDS);
        }
        connected.add(botId);
        log.info("Discord: bot={} conectado (username={})", botId, info.username());
        return info;
    }

    /**
     * Spec 78 (wizard): valida o token do bot via {@code getMe} SEM conectar nem
     * gravar nada — devolve a identidade (username) para a verificação em tempo
     * real. Token inválido/expirado → {@code DiscordApiException} (400 na API).
     * Sempre chama a API (não passa pelo kill-switch do {@link #connect}): o
     * wizard precisa validar de verdade antes do registro.
     */
    public DiscordBotApi.DiscordUserInfo validate(String token) {
        return discordApi.getMe(token);
    }

    /**
     * Spec 78 (wizard): aplica a configuração inicial opcional do bot (avatar +
     * descrição via {@code PATCH /users/@me} e comandos slash via
     * {@code PUT /applications/{id}/commands}) — stateless, nada é gravado no
     * FoxCash. Campos ausentes são pulados; falha em qualquer chamada →
     * {@code DiscordApiException} (400 na API).
     */
    public void applySetup(String token, String applicationId, String description, byte[] avatar) {
        if ((description != null && !description.isBlank()) || (avatar != null && avatar.length > 0)) {
            discordApi.updateProfile(token, description, avatar);
        }
        if (applicationId != null && !applicationId.isBlank()) {
            discordApi.putCommands(applicationId, token, DEFAULT_COMMANDS);
        }
    }

    /** Encerra a conexão (pause/remover) e remove da memória. */
    public void disconnect(UUID botId) {
        connected.remove(botId);
    }

    /** Estado em memória (re-populado pelo job de reconciliação após restart). */
    public boolean isConnected(UUID botId) {
        return connected.contains(botId);
    }

    /** Quantidade de conexões ativas (observabilidade). */
    public int connectedCount() {
        return connected.size();
    }
}
