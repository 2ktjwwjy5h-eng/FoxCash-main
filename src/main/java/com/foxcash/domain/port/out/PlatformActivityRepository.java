package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.activity.PlatformActivityQuery;

import java.time.Instant;

/**
 * Porta de saída: persistência/consulta da trilha unificada de atividades
 * (Spec 41 — relatório do admin).
 *
 * <p>{@code deleteOlderThan} (Spec 95) é usado APENAS pelo job de retenção
 * opt-in ({@code PlatformActivityCleanupScheduler}) — a API não ganha rota de
 * mutação (o contrato append-only da Spec 41 permanece).
 */
public interface PlatformActivityRepository {

    PlatformActivity save(PlatformActivity activity);

    PageResult<PlatformActivity> query(PlatformActivityQuery query);

    /** Remove eventos com {@code createdAt < cutoff} (retenção — job opt-in). */
    int deleteOlderThan(Instant cutoff);
}
