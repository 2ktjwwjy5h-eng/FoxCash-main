package com.foxcash.application.service;

import com.foxcash.domain.model.activity.PlatformActivityQuery;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.in.MerchantDashboardUseCase;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Aplicação: shell do portal (Spec 101) — header global do lojista.
 *
 * <p>Compõe dados já existentes numa resposta única para o header: saudação
 * por hora do dia (fuso do request via {@code X-Timezone-Offset}, usado SÓ para
 * exibição — negócio sempre em UTC), faturamento do mês com meta (Spec 21),
 * leads recentes para o carrossel (Specs 75/83) e notificações leves
 * (Spec 41 — {@code platform_activity_log} do lojista; sem estado de "lida"
 * nesta versão, {@code unreadCount} = nº de itens retornados).
 *
 * <p>Escopo do perfil ativo (Specs 124/139): {@code profileId} opcional escopa
 * o faturamento; {@code botIds} opcional escopa os leads (null → lojista
 * inteiro; vazio → sem leads — header inválido nunca vaza).
 */
@Service
@Transactional(readOnly = true)
public class PortalShellService {

    /** Saudação do header — salutation por hora + nome + data/hora (fuso exibição). */
    public record Greeting(String salutation, String name, String date, String time) {
    }

    /** Faturamento do mês corrente + meta da jornada (Spec 21). */
    public record Billing(Instant periodStart, Instant periodEnd,
                          BigDecimal billedAmount, BigDecimal goalAmount,
                          int progressPercent) {
    }

    /** Lead do carrossel (Spec 101) — id, nome, iniciais e última interação. */
    public record ShellLead(UUID id, String name, String initials, Instant lastInteractionAt) {
    }

    /** Item de notificação leve (Spec 101). */
    public record NotificationItem(UUID id, String type, String message, Instant createdAt) {
    }

    /** Notificações do header — contador + últimas (sem estado de lida). */
    public record Notifications(long unreadCount, List<NotificationItem> items) {
    }

    /** Resposta agregada do shell. */
    public record PortalShell(Greeting greeting, Billing billing,
                              List<ShellLead> recentLeads, Notifications notifications) {
    }

    private static final int RECENT_LEADS_CAP = 8;
    private static final int NOTIFICATIONS_CAP = 10;

    private final MerchantRepository merchantRepository;
    private final MerchantDashboardUseCase dashboard;
    private final BotLeadRepository leadRepository;
    private final PlatformActivityRepository activityRepository;
    private final PortalProperties portalProperties;
    private final Clock clock;

    public PortalShellService(MerchantRepository merchantRepository,
                              MerchantDashboardUseCase dashboard,
                              BotLeadRepository leadRepository,
                              PlatformActivityRepository activityRepository,
                              PortalProperties portalProperties,
                              Clock clock) {
        this.merchantRepository = merchantRepository;
        this.dashboard = dashboard;
        this.leadRepository = leadRepository;
        this.activityRepository = activityRepository;
        this.portalProperties = portalProperties;
        this.clock = clock;
    }

    /** Agregação única do header (Spec 101 D2.1). */
    public PortalShell shell(UUID merchantId, UUID profileId, List<UUID> botIds,
                             ZoneOffset displayOffset) {
        ZoneOffset offset = displayOffset != null ? displayOffset : ZoneOffset.UTC;
        Greeting greeting = greeting(merchantId, offset);
        Billing billing = billing(merchantId, profileId);
        List<ShellLead> recentLeads = recentLeads(merchantId, botIds, RECENT_LEADS_CAP);
        Notifications notifications = notifications(merchantId);
        return new PortalShell(greeting, billing, recentLeads, notifications);
    }

    /** Carrossel de leads (Spec 101 D2.2) — sem o billing. */
    public List<ShellLead> recentLeads(UUID merchantId, List<UUID> botIds, int limit) {
        return leadRepository.findRecentByMerchantId(merchantId, botIds, limit).stream()
                .map(lead -> new ShellLead(lead.id(), lead.name(),
                        initials(lead.name()), lead.lastInteractionAt()))
                .toList();
    }

    private Greeting greeting(UUID merchantId, ZoneOffset offset) {
        String merchantName = merchantRepository.findById(merchantId)
                .map(Merchant::name)
                .orElse("lojista");
        LocalDateTime local = Instant.now(clock).atOffset(offset).toLocalDateTime();
        String salutation = salutationFor(local.getHour());
        return new Greeting(salutation, merchantName,
                local.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))),
                local.format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("pt-BR"))));
    }

    private Billing billing(UUID merchantId, UUID profileId) {
        LocalDate today = LocalDate.now(clock);
        Instant periodStart = today.withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant periodEnd = Instant.now(clock);
        MerchantDashboardUseCase.Summary summary =
                dashboard.summary(merchantId, profileId, periodStart, periodEnd);
        BigDecimal goal = portalProperties.getJourneyGoalAmount();
        return new Billing(periodStart, periodEnd,
                summary.grossRevenue().amount(),
                goal != null ? goal : BigDecimal.ZERO,
                summary.journeyProgress());
    }

    private Notifications notifications(UUID merchantId) {
        var result = activityRepository.query(new PlatformActivityQuery(
                null, null, PlatformActorType.MERCHANT, null, merchantId, 0, NOTIFICATIONS_CAP));
        List<NotificationItem> items = result.items().stream()
                .map(a -> new NotificationItem(a.id(), a.action().name(),
                        messageFor(a.action()), a.createdAt()))
                .toList();
        // Sem estado de "lida" nesta versão — todas as recentes contam.
        return new Notifications(items.size(), items);
    }

    /** Saudação por faixa de hora local (Spec 101 D2.1): 0–5 madrugada, 6–11 bom dia, 12–17 boa tarde, 18–23 boa noite. */
    static String salutationFor(int hour) {
        if (hour >= 0 && hour < 6) {
            return "Boa madrugada";
        }
        if (hour < 12) {
            return "Boa dia";
        }
        if (hour < 18) {
            return "Boa tarde";
        }
        return "Boa noite";
    }

    /** Iniciais do lead para o avatar (Spec 101 — 2 letras, mesma lógica do CRM). */
    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String trimmed = name.trim();
        String[] parts = trimmed.split("\\s+");
        String first = parts[0];
        if (parts.length == 1) {
            // Nome único: as 2 primeiras letras ("Maria" → "MA").
            return (first.length() >= 2 ? first.substring(0, 2) : first)
                    .toUpperCase(Locale.ROOT);
        }
        String last = parts[parts.length - 1];
        return (String.valueOf(first.charAt(0)) + last.charAt(0)).toUpperCase(Locale.ROOT);
    }

    /** Mensagem legível por ação (Spec 101 — sem PII; o details nunca entra). */
    static String messageFor(PlatformAction action) {
        return switch (action) {
            case SALE_PAID -> "Venda aprovada";
            case SALE_REFUNDED -> "Venda estornada";
            case CHARGE_CREATED -> "Cobrança criada";
            case CHARGEBACK_REGISTERED -> "Chargeback registrado";
            case WITHDRAWAL_REQUESTED -> "Saque solicitado";
            case WITHDRAWAL_COMPLETED -> "Saque concluído";
            case WITHDRAWAL_FAILED -> "Saque falhou";
            case BILL_PAYMENT_REQUESTED -> "Pagamento via saldo solicitado";
            case MERCHANT_LOGIN -> "Novo acesso ao portal";
            case BOT_REGISTERED -> "Bot registrado";
            case BOT_PAUSED -> "Bot pausado";
            case BOT_RESUMED -> "Bot retomado";
            case BROADCAST_CREATED -> "Transmissão criada";
            case BROADCAST_CANCELLED -> "Transmissão cancelada";
            case PROFILE_CREATED -> "Perfil de operação criado";
            case GATEWAY_ROUTE_CHANGED -> "Rota de gateway alterada";
            case CHAT_MESSAGE_SENT -> "Mensagem enviada no chat";
            case LEAD_BLOCKED -> "Cliente bloqueou o bot";
            default -> action.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }
}
