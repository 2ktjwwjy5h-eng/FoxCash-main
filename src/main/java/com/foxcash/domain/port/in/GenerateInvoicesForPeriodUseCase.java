package com.foxcash.domain.port.in;

import java.time.YearMonth;

/**
 * Porta de entrada: gera automaticamente a fatura mensal de todos os lojistas
 * ACTIVE para um periodo (usada pelo scheduler).
 */
public interface GenerateInvoicesForPeriodUseCase {

    /**
     * Gera a fatura do periodo para cada lojista ACTIVE que ainda nao a possui.
     *
     * @return quantidade de faturas efetivamente criadas
     */
    int generateForPeriod(YearMonth period);
}
