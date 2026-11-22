package com.foxcash.application.service;

import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.port.in.FraudScreeningUseCase;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.infrastructure.config.FraudProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Triagem de antifraude em tempo real (Spec 19), executada antes de criar uma
 * cobranca.
 *
 * <p><b>Unica regra de bloqueio automatico:</b> comprador com historico de
 * chargeback (janela configuravel) → bloqueia. Decisao de produto (2026-08-04):
 * <b>velocidade nunca bloqueia</b> — compra rapida em sequencia e comportamento
 * normal dos clientes, e bloquear venda legitima e inaceitavel. O chargeback e
 * o sinal que custa dinheiro: quem ja deu chargeback nao volta pela mesma
 * porta (mesmo buyerId).
 *
 * <p>O sinal e o pseudonimo opaco {@code buyerId} do bot (armazenado apenas
 * como hash SHA-256 — sem PII).
 */
@Service
public class FraudScreeningService implements FraudScreeningUseCase {

    private final ChargebackRepository chargebackRepository;
    private final FraudProperties properties;

    public FraudScreeningService(ChargebackRepository chargebackRepository,
                                 FraudProperties properties) {
        this.chargebackRepository = chargebackRepository;
        this.properties = properties;
    }

    @Override
    public void screen(String buyerIdHash) {
        if (buyerIdHash == null) {
            return;
        }
        long chargebacks = chargebackRepository.countByBuyerIdHashSince(
                buyerIdHash, Instant.now().minus(Duration.ofDays(properties.getBuyerChargebackWindowDays())));
        if (chargebacks >= properties.getBuyerChargebackBlockCount()) {
            throw new FraudRuleViolationException("fraud_buyer_blocked",
                    "Comprador bloqueado por historico de chargeback");
        }
    }
}
