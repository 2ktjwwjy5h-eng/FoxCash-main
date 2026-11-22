package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.activity.PlatformActivityQuery;

/**
 * Porta de entrada: consulta do relatório unificado de atividades (Spec 41 —
 * somente admin).
 */
public interface PlatformActivityUseCase {

    PageResult<PlatformActivity> query(PlatformActivityQuery query);
}
