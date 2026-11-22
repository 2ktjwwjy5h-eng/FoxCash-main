package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Aplicação: consulta do registro de bots de venda (Spec 74) — portal (só do
 * lojista) e espelho read-only do Master Console (todos os lojistas, com o nome
 * do lojista). Respostas nunca incluem o token (máscara/últimos 4).
 */
@Service
public class StoreBotQueryService {

    private final StoreBotRepository botRepository;
    private final MerchantRepository merchantRepository;

    public StoreBotQueryService(StoreBotRepository botRepository, MerchantRepository merchantRepository) {
        this.botRepository = botRepository;
        this.merchantRepository = merchantRepository;
    }

    /** Todos os bots NÃO removidos de todos os lojistas (espelho master). */
    @Transactional(readOnly = true)
    public List<MasterBotView> listAllForMaster() {
        return botRepository.findAllNonDeleted().stream()
                .map(bot -> new MasterBotView(bot, merchantName(bot.merchantId())))
                .toList();
    }

    /** Detalhe de um bot para o master (404 se inexistente/removido). */
    @Transactional(readOnly = true)
    public MasterBotView getForMaster(UUID botId) {
        StoreBot bot = botRepository.findById(botId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(StoreBotNotFoundException::new);
        return new MasterBotView(bot, merchantName(bot.merchantId()));
    }

    private String merchantName(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .map(Merchant::name)
                .orElse(null);
    }

    /** Bot + nome do lojista dono (espelho read-only do Master Console). */
    public record MasterBotView(StoreBot bot, String merchantName) {
    }
}
