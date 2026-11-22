package com.foxcash.application.service;

import com.foxcash.domain.exception.BotInactiveException;
import com.foxcash.domain.exception.PlanInactiveException;
import com.foxcash.domain.exception.PlanNotFoundException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.CreatePixChargeUseCase;
import com.foxcash.domain.port.in.PlanPurchaseUseCase;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: compra de plano (Spec 49) — cria a cobrança Pix vinculada ao
 * plano com o destinatário persistido na transação. Valida: plano ativo (409
 * {@code plan_inactive}), inexistente na loja (404 {@code plan_not_found}) e
 * {@code platform}/{@code platformUserId} obrigatórios (400). Idempotência
 * (Spec 28) e triagem antifraude (Spec 19) herdam do {@link CreatePixChargeService}.
 *
 * <p>Spec 75: quando {@code botId} presente (opcional), valida o bot de venda —
 * pertence ao lojista (404 {@code bot_not_found} cross-lojista, sem revelar
 * existência) e status {@code ACTIVE} (409 {@code bot_inactive}); atribui a
 * cobrança ao bot ({@code pix_transactions.bot_id}) e grava o funil
 * {@code checkout_started} NA MESMA transação.
 */
@Service
public class PlanPurchaseService implements PlanPurchaseUseCase {

    private final StoreRepository storeRepository;
    private final PlanRepository planRepository;
    private final StoreBotRepository botRepository;
    private final CreatePixChargeUseCase createPixCharge;
    private final BotFunnelService botFunnelService;
    private final PlatformActivityRecorder activityRecorder;

    public PlanPurchaseService(StoreRepository storeRepository,
                               PlanRepository planRepository,
                               StoreBotRepository botRepository,
                               CreatePixChargeUseCase createPixCharge,
                               BotFunnelService botFunnelService,
                               PlatformActivityRecorder activityRecorder) {
        this.storeRepository = storeRepository;
        this.planRepository = planRepository;
        this.botRepository = botRepository;
        this.createPixCharge = createPixCharge;
        this.botFunnelService = botFunnelService;
        this.activityRecorder = activityRecorder;
    }

    @Override
    @Transactional
    public Result purchase(UUID merchantId, UUID planId, PurchaseCommand command) {
        UUID storeId = storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new)
                .id();
        Plan plan = planRepository.findByIdAndStoreId(planId, storeId)
                .orElseThrow(PlanNotFoundException::new);
        if (!plan.active()) {
            throw new PlanInactiveException();
        }
        if (command.platform() == null || command.platformUserId() == null
                || command.platformUserId().isBlank()) {
            throw new IllegalArgumentException(
                    "platform e platformUserId sao obrigatorios na compra de plano");
        }
        // Spec 75: botId opcional — pertence ao lojista (404) e ACTIVE (409).
        if (command.botId() != null) {
            StoreBot bot = botRepository.findByIdAndMerchantId(command.botId(), merchantId)
                    .orElseThrow(StoreBotNotFoundException::new);
            if (bot.status() != BotStatus.ACTIVE) {
                throw new BotInactiveException();
            }
        }

        CreatePixChargeUseCase.Result result = createPixCharge.create(
                new CreatePixChargeUseCase.Command(
                        merchantId, plan.price(), command.expirationSeconds(), command.buyerId(),
                        plan.name(), command.idempotencyKey(), planId, command.platform(),
                        command.platformUserId(), command.termsAcceptedAt(),
                        command.buyerIp(), command.buyerDevice(), command.botId()));
        // Spec 75: funil na MESMA transação — o usuário abriu o checkout do plano.
        if (command.botId() != null) {
            botFunnelService.recordCheckout(command.botId(), command.platformUserId(),
                    result.transactionId(), plan.price());
        }
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.PLAN_PURCHASED, "PLAN", planId, merchantId,
                Map.of("planId", planId, "amount", plan.price().amount()));
        return new Result(result.transactionId());
    }
}
