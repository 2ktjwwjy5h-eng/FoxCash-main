package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.risk.RiskSearchQuery;
import com.foxcash.domain.port.out.RiskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link QueryRiskService} (Spec 16) — delega ao port.
 */
class QueryRiskServiceTest {

    private final RiskRepository riskRepository = mock(RiskRepository.class);

    private QueryRiskService service;

    @BeforeEach
    void setUp() {
        service = new QueryRiskService(riskRepository);
    }

    @Test
    void searchDelegatesToRepository() {
        RiskSearchQuery query = new RiskSearchQuery(UUID.randomUUID(), 0, 20);
        PageResult<MerchantRiskSnapshot> expected = PageResult.of(List.of(), 0, 20, 0);
        when(riskRepository.search(query)).thenReturn(expected);

        assertThat(service.search(query)).isSameAs(expected);
        verify(riskRepository).search(query);
    }

    @Test
    void latestByMerchantDelegatesToRepository() {
        UUID merchantId = UUID.randomUUID();
        when(riskRepository.latestByMerchant(merchantId)).thenReturn(Optional.empty());

        assertThat(service.latestByMerchant(merchantId)).isEmpty();
        verify(riskRepository).latestByMerchant(merchantId);
    }
}
