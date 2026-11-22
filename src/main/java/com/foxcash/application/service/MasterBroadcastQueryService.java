package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.model.broadcast.BroadcastLogStatus;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.BroadcastLogRepository;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Aplicação: auditoria de broadcasts no Master Console (Spec 89) — leitura
 * global cross-merchant (todos os lojistas) com dados do {@code merchant} +
 * {@code store_bot} em cada linha, e o detalhe com conteúdo + logs por
 * destinatário. Leitura liberada a qualquer master ativo (espelho read-only).
 */
@Service
public class MasterBroadcastQueryService {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastLogRepository logRepository;
    private final StoreBotRepository botRepository;
    private final MerchantRepository merchantRepository;

    public MasterBroadcastQueryService(BroadcastRepository broadcastRepository,
                                       BroadcastLogRepository logRepository,
                                       StoreBotRepository botRepository,
                                       MerchantRepository merchantRepository) {
        this.broadcastRepository = broadcastRepository;
        this.logRepository = logRepository;
        this.botRepository = botRepository;
        this.merchantRepository = merchantRepository;
    }

    /** Lista paginada cross-merchant (filtros opcionais; sem {@code content}). */
    @Transactional(readOnly = true)
    public PageResult<MasterBroadcastView> list(BroadcastRepository.MasterBroadcastSearchQuery query) {
        PageResult<Broadcast> page = broadcastRepository.searchMaster(query);
        List<MasterBroadcastView> items = page.items().stream().map(this::toView).toList();
        return PageResult.of(items, page.page(), page.size(), page.totalElements());
    }

    /** Detalhe de um broadcast para o master (404 se inexistente). */
    @Transactional(readOnly = true)
    public MasterBroadcastView detail(UUID broadcastId) {
        Broadcast broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(BroadcastNotFoundException::new);
        return toView(broadcast);
    }

    /** Logs por destinatário do broadcast (detalhe — Spec 79). */
    @Transactional(readOnly = true)
    public List<BroadcastLog> logs(UUID broadcastId) {
        broadcastRepository.findById(broadcastId).orElseThrow(BroadcastNotFoundException::new);
        return logRepository.findByBroadcastId(broadcastId);
    }

    private MasterBroadcastView toView(Broadcast broadcast) {
        StoreBot bot = botRepository.findById(broadcast.storeBotId()).orElse(null);
        String merchantName = bot == null ? null : merchantName(bot.merchantId());
        return new MasterBroadcastView(broadcast, bot, merchantName);
    }

    private String merchantName(UUID merchantId) {
        return merchantRepository.findById(merchantId).map(Merchant::name).orElse(null);
    }

    /** Broadcast + bot dono + nome do lojista (espelho read-only do master). */
    public record MasterBroadcastView(Broadcast broadcast, StoreBot bot, String merchantName) {

        /** Contagens de logs por status (detalhe — Spec 89). */
        public LogCounts counts(List<BroadcastLog> logs) {
            long success = logs.stream().filter(l -> l.status() == BroadcastLogStatus.SUCCESS).count();
            long blocked = logs.stream().filter(l -> l.status() == BroadcastLogStatus.BLOCKED).count();
            long failed = logs.stream().filter(l -> l.status() == BroadcastLogStatus.FAILED).count();
            return new LogCounts(success, blocked, failed);
        }
    }

    public record LogCounts(long success, long blocked, long failed) {
    }
}
