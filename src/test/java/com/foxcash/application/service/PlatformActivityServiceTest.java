package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.activity.PlatformActivityQuery;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da consulta do relatório de atividades (Spec 41) — delega a query
 * paginada com filtros ao repositório.
 */
class PlatformActivityServiceTest {

    private final PlatformActivityRepository repository = mock(PlatformActivityRepository.class);

    @Test
    void queryDelegatesToRepository() {
        PlatformActivity activity = PlatformActivity.record(
                PlatformActorType.MERCHANT, UUID.randomUUID(), PlatformAction.MERCHANT_LOGIN,
                "SESSION", UUID.randomUUID(), UUID.randomUUID(), null, null,
                Instant.parse("2026-08-05T12:00:00Z"));
        when(repository.query(any())).thenReturn(PageResult.of(List.of(activity), 0, 20, 1));

        PlatformActivityService service = new PlatformActivityService(repository);
        PlatformActivityQuery query = new PlatformActivityQuery(
                Instant.parse("2026-08-05T00:00:00Z"), null,
                PlatformActorType.MERCHANT, PlatformAction.MERCHANT_LOGIN, null, 0, 20);

        PageResult<PlatformActivity> result = service.query(query);

        assertEquals(1, result.totalElements());
        assertEquals("MERCHANT_LOGIN", result.items().get(0).action().name());
        verify(repository).query(query);
    }
}
