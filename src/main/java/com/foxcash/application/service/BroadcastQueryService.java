package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.port.in.BroadcastQueryUseCase;
import com.foxcash.domain.port.out.BroadcastLogRepository;
import com.foxcash.domain.port.out.BroadcastRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Aplicação: consulta dos broadcasts no portal (Spec 79) — listagem (filtro por
 * modo/status) e detalhe com os logs por destinatário. Tudo por
 * {@code merchantId} do principal (404 {@code broadcast_not_found}
 * cross-lojista).
 */
@Service
public class BroadcastQueryService implements BroadcastQueryUseCase {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastLogRepository logRepository;

    public BroadcastQueryService(BroadcastRepository broadcastRepository,
                                 BroadcastLogRepository logRepository) {
        this.broadcastRepository = broadcastRepository;
        this.logRepository = logRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Broadcast> list(UUID merchantId, BroadcastMode mode, BroadcastStatus status) {
        return list(merchantId, null, mode, status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Broadcast> list(UUID merchantId, List<UUID> botIds,
                                BroadcastMode mode, BroadcastStatus status) {
        // Spec 124 (filtro rígido por bot-set): botIds null → todos os
        // broadcasts do lojista (regressão — sem header); presente → só os de
        // bots do perfil (vazio → sem resultados — header inválido nunca vaza).
        return botIds == null
                ? broadcastRepository.findByMerchantId(merchantId, mode, status)
                : broadcastRepository.findByMerchantIdAndBotIds(merchantId, botIds, mode, status);
    }

    @Override
    @Transactional(readOnly = true)
    public Broadcast get(UUID merchantId, UUID broadcastId) {
        return broadcastRepository.findByIdAndMerchantId(broadcastId, merchantId)
                .orElseThrow(BroadcastNotFoundException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BroadcastLog> logs(UUID merchantId, UUID broadcastId) {
        // Confirma a existência/escopo ANTES de buscar os logs (404 cross-lojista
        // sem revelar existência — padrão IDOR das Specs 24/31).
        broadcastRepository.findByIdAndMerchantId(broadcastId, merchantId)
                .orElseThrow(BroadcastNotFoundException::new);
        return logRepository.findByBroadcastId(broadcastId);
    }
}
