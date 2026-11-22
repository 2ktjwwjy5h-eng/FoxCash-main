package com.foxcash.domain.model.botfunnel;

import com.foxcash.domain.model.Money;

/**
 * Métricas de um bot (ou agregado de bots) num período (Spec 75/D3):
 * {@code leads} (COUNT de usuários únicos), {@code vendas} (transações
 * {@code PAID} não estornadas — fonte oficial, Specs 23/29), {@code receita}
 * (soma, {@link Money} — escala normalizada) e {@code conversao}
 * ({@code vendas / leads}; {@code 0} quando não há leads).
 */
public record BotMetrics(
        long leads,
        long vendas,
        Money receita,
        double conversao) {

    public BotMetrics {
        if (leads < 0 || vendas < 0) {
            throw new IllegalArgumentException("leads e vendas nao podem ser negativos");
        }
        if (receita == null) {
            throw new IllegalArgumentException("receita must not be null");
        }
        if (leads == 0 && vendas > 0) {
            throw new IllegalArgumentException("conversao impossivel: vendas sem leads");
        }
    }

    public static BotMetrics of(long leads, long vendas, Money receita) {
        double conversao = leads == 0 ? 0.0 : (double) vendas / leads;
        return new BotMetrics(leads, vendas, receita, conversao);
    }

    /** Agrega duas métricas (portal: total da loja; master: total de todos). */
    public BotMetrics add(BotMetrics other) {
        long sumLeads = leads + other.leads;
        long sumVendas = vendas + other.vendas;
        Money sumReceita = receita.add(other.receita);
        double conversao = sumLeads == 0 ? 0.0 : (double) sumVendas / sumLeads;
        return new BotMetrics(sumLeads, sumVendas, sumReceita, conversao);
    }
}
