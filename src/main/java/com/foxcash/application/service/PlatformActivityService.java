package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.activity.PlatformActivityQuery;
import com.foxcash.domain.port.in.PlatformActivityUseCase;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import org.springframework.stereotype.Service;

/**
 * Aplicação: consulta do relatório unificado de atividades (Spec 41). O acesso
 * é validado no controller (admin — can_manage_masters).
 */
@Service
public class PlatformActivityService implements PlatformActivityUseCase {

    private final PlatformActivityRepository repository;

    public PlatformActivityService(PlatformActivityRepository repository) {
        this.repository = repository;
    }

    @Override
    public PageResult<PlatformActivity> query(PlatformActivityQuery query) {
        return repository.query(query);
    }
}
