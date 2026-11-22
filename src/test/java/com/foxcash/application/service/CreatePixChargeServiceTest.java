package com.foxcash.application.service;

import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.exception.IdempotencyConflictException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.MerchantNotActiveException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.PspTimeoutException;
import com.foxcash.domain.exception.TooManyPendingChargesException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.CreatePixChargeUseCase;
import com.foxcash.domain.port.in.FraudScreeningUseCase;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.security.AuthenticatedMerchantResolver;
import com.foxcash.infrastructure.security.Sha256Hasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link CreatePixChargeService} (Spec 19): a triagem em tempo real
 * roda antes de gerar o Pix — comprador com historico de chargeback e
 * bloqueado sem persistir nada; o buyerId opaco e persistido apenas como hash
 * SHA-256 (nunca o valor enviado pelo bot). Auditoria 2026-08-05: PSP fora da
 * transacao (save em 2 etapas) e falha do provedor encerra a PENDING.
 */
class CreatePixChargeServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MerchantProfileRepository profileRepository = mock(MerchantProfileRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final PixProviderClient pixProviderClient = mock(PixProviderClient.class);
    private final FraudScreeningUseCase fraudScreening = mock(FraudScreeningUseCase.class);
    private final WebhookEventPublisher webhookEventPublisher = mock(WebhookEventPublisher.class);
    private final IdempotencyPersister idempotencyPersister = mock(IdempotencyPersister.class);

    private PortalProperties portalProperties;
    private PricingProperties pricingProperties;
    private CreatePixChargeService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final AuthenticatedMerchantResolver authenticatedMerchant =
            mock(AuthenticatedMerchantResolver.class);

    /** Última linha persistida (simula o banco para o findByIdForUpdate — SEC-26). */
    private final AtomicReference<PixTransaction> lastSaved = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        portalProperties = new PortalProperties();
        pricingProperties = new PricingProperties();
        pricingProperties.setMaxPendingCharges(100);
        PixChargePersistence persistence = new PixChargePersistence(transactionRepository, webhookEventPublisher);
        service = new CreatePixChargeService(
                merchantRepository, profileRepository, botRepository,
                persistence, idempotencyPersister, transactionRepository,
                pixProviderClient, fraudScreening, activityRecorder, portalProperties, pricingProperties,
                authenticatedMerchant);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant()));
        when(pixProviderClient.createCharge(any())).thenReturn(
                new PixProviderClient.CreateChargeResult("E123", "qrcode-base64", "000201..."));
        // Em producao o save devolve uma NOVA instancia (mapper). O snapshot
        // evita que a mutacao da 2a etapa (attach/reject) corrompa a 1a captura.
        // SEC-26: o repositório "grava" e o findByIdForUpdate "vê" a última
        // linha persistida — modela o banco (o settlePending do PixChargePersistence
        // relê a PENDING sob lock antes de encerrar).
        when(transactionRepository.save(any(PixTransaction.class))).thenAnswer(inv -> {
            PixTransaction saved = snapshot((PixTransaction) inv.getArgument(0));
            lastSaved.set(saved);
            return saved;
        });
        when(transactionRepository.findByIdForUpdate(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(lastSaved.get()));
        // IdempotencyPersister (Spec 28): transacao própria REQUIRES_NEW — devolve
        // o tx com a chave atribuida (simula o commit antecipado da PENDING).
        when(idempotencyPersister.persistPending(any(), anyString())).thenAnswer(inv -> {
            PixTransaction tx = inv.getArgument(0);
            String key = inv.getArgument(1);
            // Em producao o persister faz o save da PENDING (REQUIRES_NEW); aqui o
            // mock repassa ao transactionRepository para preservar a contagem.
            return transactionRepository.save(tx.withIdempotencyKey(key));
        });
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Copia imutavel da transacao — simula o retorno do mapper na persistencia. */
    private static PixTransaction snapshot(PixTransaction tx) {
        return PixTransaction.restore(tx.id(), tx.merchantId(), tx.amount(), tx.description(),
                tx.pixKey(), tx.endToEndId(), tx.externalReference(), tx.pixQrCode(), tx.pixCopyPaste(),
                tx.status(), tx.createdAt(), tx.updatedAt(), tx.paidAt(), tx.buyerIdHash(),
                tx.idempotencyKey());
    }

    private Merchant activeMerchant() {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, Instant.now());
    }

    private CreatePixChargeUseCase.Command command(String buyerId) {
        return new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("150.00"), 3600, buyerId, null, "cob-key-1",
                null, null, null);
    }

    @Test
    void blockedBuyerIsRejectedBeforePspCall() {
        doThrow(new FraudRuleViolationException("fraud_buyer_blocked", "Comprador bloqueado por historico de chargeback"))
                .when(fraudScreening).screen(anyString());

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(FraudRuleViolationException.class)
                .hasMessageContaining("chargeback");

        verify(pixProviderClient, never()).createCharge(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void cleanBuyerCreatesChargeAndPersistsOnlyHash() {
        CreatePixChargeUseCase.Result result = service.create(command("buyer-1"));

        assertThat(result.transactionId()).isNotNull();

        // Auditoria 2026-08-05: save em 2 etapas (PENDING + attach do PSP).
        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        PixTransaction saved = captor.getValue();

        assertThat(saved.buyerIdHash()).isEqualTo(Sha256Hasher.hex("buyer-1"));
        assertThat(saved.buyerIdHash()).isNotEqualTo("buyer-1"); // nunca o valor enviado pelo bot
        assertThat(saved.externalReference()).isEqualTo("E123"); // payload do PSP anexado
        verify(pixProviderClient).createCharge(any());
    }

    @Test
    void reusesMerchantResolvedByAuthFilterInsteadOfReloading() {
        // Spec 65 (Fase 1): com o lojista resolvido pelo filtro no request, o
        // service NAO refaz o findById — consolida as leituras de auth (o reload
        // do service era o SELECT redundante do caminho do charge, capacity-model F1).
        Merchant merchant = activeMerchant();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/foxcash/pix/cob");
        request.setAttribute(AuthenticatedMerchantResolver.MERCHANT_ATTRIBUTE, merchant);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.of(merchant));

        service.create(command("buyer-1"));

        verify(merchantRepository, never()).findById(MERCHANT_ID);
        verify(pixProviderClient).createCharge(any());
    }

    @Test
    void fallsBackToRepositoryWhenNoRequestMerchant() {
        // Fora de request HTTP (jobs/testes): o resolver nao tem merchant e o
        // service mantem o comportamento original (reload via repositorio).
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.empty());

        CreatePixChargeUseCase.Result result = service.create(command("buyer-1"));

        assertThat(result.transactionId()).isNotNull();
        verify(merchantRepository).findById(MERCHANT_ID);
    }

    @Test
    void mismatchedResolvedMerchantFailsClosedWithDenied() {
        // SEC-13 (revisão de segurança 2026-08-11): o principal autenticado é um
        // LOJISTA (resolver preenchido) mas o merchantId do comando aponta para
        // OUTRO lojista → falha fechada (403). Antes, o fallback findById
        // carregava o lojista alheio (IDOR latente).
        Merchant other = Merchant.restore(UUID.randomUUID(), "Outra Loja", null, null,
                "hash", MerchantStatus.ACTIVE, PricingPlan.fixed(),
                Money.of("0.00"), false, Instant.now());
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(MerchantAccessDeniedException.class);
        verify(merchantRepository, never()).findById(any());
        verify(pixProviderClient, never()).createCharge(any());
    }

    @Test
    void screeningReceivesHashedBuyerId() {
        service.create(command("buyer-1"));

        verify(fraudScreening).screen(Sha256Hasher.hex("buyer-1"));
    }

    @Test
    void chargeWithoutBuyerIdSkipsScreeningAndPersistsNullHash() {
        service.create(command(null));

        verify(fraudScreening).screen(null);

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().buyerIdHash()).isNull();
    }

    @Test
    void pspFailureCancelsPendingTransaction() {
        // Auditoria 2026-08-05: PSP falhou (timeout/5xx) apos a PENDING salva —
        // a transacao e encerrada (REJECTED) e nenhum evento CHARGE_CREATED e
        // publicado (nada foi criado no provedor).
        when(pixProviderClient.createCharge(any()))
                .thenThrow(new IllegalStateException("Pagar.me: timeout"));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(captor.getAllValues().get(1).status()).isEqualTo(TransactionStatus.REJECTED);
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void unknownMerchantThrowsNotFound() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    void inactiveMerchantIsRejected() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(
                Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                        null, MerchantStatus.INACTIVE, PLAN, Money.of("0.00"), false, Instant.now())));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(MerchantNotActiveException.class);
    }

    @Test
    void rejectsAmountAboveChargeMaxLimit() {
        portalProperties.setChargeMaxAmount(new BigDecimal("100.00"));
        CreatePixChargeUseCase.Command command = new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("100.01"), 3600, null, null, "cob-key-1",
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class);

        verify(pixProviderClient, never()).createCharge(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void rejectsNonPositiveExpirationSeconds() {
        CreatePixChargeUseCase.Command command = new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("150.00"), 0, null, null, "cob-key-1",
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class);

        verify(pixProviderClient, never()).createCharge(any());
    }

    @Test
    void propagatesExpirationSecondsToPspCommand() {
        service.create(new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("150.00"), 7200, null, null, "cob-key-1",
                null, null, null));

        ArgumentCaptor<PixProviderClient.CreateChargeCommand> captor =
                ArgumentCaptor.forClass(PixProviderClient.CreateChargeCommand.class);
        verify(pixProviderClient).createCharge(captor.capture());
        assertThat(captor.getValue().expirationSeconds()).isEqualTo(7200);
    }

    @Test
    void defaultDescriptionIsUsedWhenCommandOmitsIt() {
        // Spec 23: descricao padrao "Cobranca Pix" quando o bot/portal nao
        // informou (mutation: EmptyObjectReturnVals em descriptionOf).
        service.create(command("buyer-1")); // command() usa description = null

        ArgumentCaptor<PixProviderClient.CreateChargeCommand> captor =
                ArgumentCaptor.forClass(PixProviderClient.CreateChargeCommand.class);
        verify(pixProviderClient).createCharge(captor.capture());
        assertThat(captor.getValue().description()).isEqualTo("Cobranca Pix");
    }

    @Test
    void customDescriptionIsPreserved() {
        service.create(new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("150.00"), 3600, null, "Plano Bronze",
                "cob-key-desc", null, null, null));

        ArgumentCaptor<PixProviderClient.CreateChargeCommand> captor =
                ArgumentCaptor.forClass(PixProviderClient.CreateChargeCommand.class);
        verify(pixProviderClient).createCharge(captor.capture());
        assertThat(captor.getValue().description()).isEqualTo("Plano Bronze");
    }

    @Test
    void rejectsZeroAmount() {
        // Boundary do teto minimo (mutation: signum() <= 0 <-> < 0): zero e
        // invalido — deve lançar ANTES de tocar o PSP.
        CreatePixChargeUseCase.Command command = new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("0"), 3600, null, null, "cob-key-zero",
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class);

        verify(pixProviderClient, never()).createCharge(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void acceptsAmountExactlyAtChargeMaxLimit() {
        // Boundary do teto maximo (mutation: compareTo(max) > 0 <-> >= 0): o
        // valor IGUAL ao teto e aceito.
        portalProperties.setChargeMaxAmount(new BigDecimal("100.00"));
        CreatePixChargeUseCase.Command command = new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("100.00"), 3600, null, null, "cob-key-max-exact",
                null, null, null);

        assertThat(service.create(command).transactionId()).isNotNull();
        verify(pixProviderClient).createCharge(any());
    }

    @Test
    void acceptsExpirationExactlyAtMax() {
        // Boundary do teto de expiracao (mutation: > MAX <-> >= MAX): o valor
        // IGUAL ao teto (24h) e aceito.
        CreatePixChargeUseCase.Command command = new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("150.00"), CreatePixChargeService.MAX_EXPIRATION_SECONDS,
                null, null, "cob-key-exp-exact", null, null, null);

        assertThat(service.create(command).transactionId()).isNotNull();
        verify(pixProviderClient).createCharge(any());
    }

    @Test
    void missingIdempotencyKeyIsRejectedWith400() {
        // Spec 28: a chave de idempotência é OBRIGATÓRIA na criação de cobrança.
        CreatePixChargeUseCase.Command command = new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("150.00"), 3600, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class);

        verify(pixProviderClient, never()).createCharge(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void replayOfSameKeyReturnsSameTransactionWithoutNewPspCall() {
        // Spec 28 (critério 3): replay da mesma chave após a cobrança criada
        // (externalReference presente) → mesmo transactionId e SEM nova chamada
        // ao PSP (o retry idempotente reusa a transação existente).
        UUID txId = UUID.randomUUID();
        PixTransaction created = PixTransaction.restore(txId, MERCHANT_ID, Money.of("150.00"),
                "Cobranca Pix", null, "E123", "E123", "qr", "copia", TransactionStatus.PENDING,
                Instant.now(), Instant.now(), null, null, "cob-key-1");
        when(transactionRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, "cob-key-1"))
                .thenReturn(Optional.of(created));

        CreatePixChargeUseCase.Result result = service.create(command("buyer-1"));

        assertThat(result.transactionId()).isEqualTo(txId);
        verify(pixProviderClient, never()).createCharge(any());
        verify(idempotencyPersister, never()).persistPending(any(), anyString());
    }

    @Test
    void retryAfterPspTimeoutCompletesChargeWithSameTransactionId() {
        // Spec 28 (critério 5): timeout simulado → PENDING persistida sem QR;
        // retry com a mesma chave re-tenta o PSP e devolve o MESMO transactionId.
        UUID txId = UUID.randomUUID();
        PixTransaction pendingWithoutQr = PixTransaction.restore(txId, MERCHANT_ID, Money.of("150.00"),
                "Cobranca Pix", null, null, null, null, null, TransactionStatus.PENDING,
                Instant.now(), Instant.now(), null, null, "cob-key-1");
        when(transactionRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, "cob-key-1"))
                .thenReturn(Optional.of(pendingWithoutQr));

        CreatePixChargeUseCase.Result result = service.create(command("buyer-1"));

        assertThat(result.transactionId()).isEqualTo(txId);
        // O retry chamou o PSP com o MESMO transactionId (code idempotente no provedor real).
        ArgumentCaptor<PixProviderClient.CreateChargeCommand> captor =
                ArgumentCaptor.forClass(PixProviderClient.CreateChargeCommand.class);
        verify(pixProviderClient).createCharge(captor.capture());
        assertThat(captor.getValue().transactionId()).isEqualTo(txId);
        // O payload do PSP foi anexado (cobrança concluída, sem duplicidade).
        ArgumentCaptor<PixTransaction> savedCaptor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().externalReference()).isEqualTo("E123");
    }

    @Test
    void failedKeyReplayThrowsIdempotencyConflict() {
        // Spec 28 (critério 6): erro real do PSP → transação FAILED; retry com a
        // mesma chave → 409 idempotency_conflict.
        UUID txId = UUID.randomUUID();
        PixTransaction failed = PixTransaction.restore(txId, MERCHANT_ID, Money.of("150.00"),
                "Cobranca Pix", null, null, null, null, null, TransactionStatus.FAILED,
                Instant.now(), Instant.now(), null, null, "cob-key-1");
        when(transactionRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, "cob-key-1"))
                .thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(pixProviderClient, never()).createCharge(any());
    }

    @Test
    void pspRealErrorMarksTransactionFailed() {
        // Spec 28: erro real do PSP (4xx/5xx) → transação FAILED (não REJECTED).
        when(pixProviderClient.createCharge(any()))
                .thenThrow(new PixProviderClient.PixProviderException("Pagar.me: 422"));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(PixProviderClient.PixProviderException.class);

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(captor.getAllValues().get(1).status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void pspTimeoutKeepsPendingTransactionAndThrowsPspTimeout() {
        // Spec 28 (critério 5): timeout → 502 psp_timeout e a PENDING persiste
        // (sem QR) — recuperável pelo retry com a mesma chave.
        when(pixProviderClient.createCharge(any()))
                .thenThrow(new PixProviderClient.PixProviderTimeoutException(
                        "Pagar.me: timeout", new IllegalStateException("sim")));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(PspTimeoutException.class);

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.PENDING);
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void rejectsExpirationAboveMax() {
        // Auditoria 2026-08-05 (B-04): sem teto, uma cobrança poderia não expirar.
        CreatePixChargeUseCase.Command command = new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("150.00"), CreatePixChargeService.MAX_EXPIRATION_SECONDS + 1,
                null, null, "cob-key-max", null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("86400");

        verify(pixProviderClient, never()).createCharge(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void concurrentDuplicateIdempotencyKeyMapsConstraintViolationTo409() {
        // Auditoria 2026-08-05 (M-A2): corrida — duas requisições com a MESMA
        // chave passam o pré-check (findBy) simultaneamente; o INSERT da perdedora
        // esbarra no índice único → 409 idempotency_conflict (antes: 500).
        // doThrow (em vez de when().thenThrow): o when() re-executaria o thenAnswer
        // do setUp (persistPending) com argumentos null → NPE antes do stub valer.
        doThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_pix_transactions_idempotency\""))
                .when(idempotencyPersister).persistPending(any(), anyString());

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(IdempotencyConflictException.class);

        // O PSP nunca é chamado para a perdedora — sem cobrança duplicada.
        verify(pixProviderClient, never()).createCharge(any());
        verify(transactionRepository, never()).save(any());
    }

    // ---------- Spec 58: cap de cobranças pendentes por lojista ----------

    @Test
    void rejectsNewChargeWhenPendingCapReached() {
        // Cap atingido (100 PENDING) → nova cobrança (chave NOVA) → 409.
        pricingProperties.setMaxPendingCharges(3);
        when(transactionRepository.countByMerchantIdAndStatus(MERCHANT_ID, TransactionStatus.PENDING))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(TooManyPendingChargesException.class);

        // Nada é persistido nem o PSP é chamado — sem custo para o lojista.
        verify(pixProviderClient, never()).createCharge(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void allowsChargeWhenPendingBelowCap() {
        pricingProperties.setMaxPendingCharges(3);
        when(transactionRepository.countByMerchantIdAndStatus(MERCHANT_ID, TransactionStatus.PENDING))
                .thenReturn(2L);

        CreatePixChargeUseCase.Result result = service.create(command("buyer-1"));

        assertThat(result).isNotNull();
        verify(pixProviderClient).createCharge(any());
    }

    @Test
    void idempotentReplayStillConvergesWhenPendingCapReached() {
        // CRITÉRIO 9 (Spec 58): a idempotência vem ANTES do cap — um replay da
        // MESMA chave NUNCA vira 409 por cap, mesmo com o teto de pendentes
        // atingido. O replay deve convergir para o resultado idempotente.
        pricingProperties.setMaxPendingCharges(3);
        when(transactionRepository.countByMerchantIdAndStatus(MERCHANT_ID, TransactionStatus.PENDING))
                .thenReturn(3L); // cap atingido

        // Transação existente para a chave (PENDING sem QR — timeout anterior).
        PixTransaction existing = PixTransaction.create(
                MERCHANT_ID, Money.of("150.00"), "Cobranca Pix", null,
                Sha256Hasher.hex("buyer-1"), null, null, null, null, null, null);
        when(transactionRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, "cob-key-1"))
                .thenReturn(Optional.of(existing));

        CreatePixChargeUseCase.Result result = service.create(command("buyer-1"));

        // Converge para a MESMA transação (idempotente) — não lança 409.
        assertThat(result.transactionId()).isEqualTo(existing.id());
        verify(transactionRepository).findByMerchantIdAndIdempotencyKey(MERCHANT_ID, "cob-key-1");
        // O cap NÃO é checado para replay (idempotência primeiro).
        verify(transactionRepository, never()).countByMerchantIdAndStatus(MERCHANT_ID, TransactionStatus.PENDING);
    }

    // ---------- Spec 124 (rota viva): resolução do perfil de operação na criação ----------

    private CreatePixChargeUseCase.Command command(UUID botId, UUID profileId, String key) {
        return new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("150.00"), 3600, "buyer-1", null, key,
                null, null, null, null, null, null, botId, profileId);
    }

    @Test
    void chargeUsesHeaderProfileOverBotAndDefault() {
        // L237 (NegateConditionalsMutator `fromHeader != null` → `== null`) +
        // L156 (VoidMethodCallMutator: attachProfile removido): o perfil do
        // header (portal validado) VENCE sobre bot/default e é gravado na
        // transação persistida (Spec 124).
        UUID headerProfile = UUID.randomUUID();
        when(profileRepository.defaultProfileIdOf(MERCHANT_ID)).thenReturn(Optional.of(UUID.randomUUID()));

        service.create(command(UUID.randomUUID(), headerProfile, "cob-key-hdr"));

        ArgumentCaptor<PixTransaction> txCaptor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(idempotencyPersister).persistPending(txCaptor.capture(), eq("cob-key-hdr"));
        assertThat(txCaptor.getValue().profileId()).isEqualTo(headerProfile);
    }

    @Test
    void chargeUsesBotProfileWhenNoHeaderProfile() {
        // L240 (NegateConditionalsMutator `botId != null` → `== null`): sem
        // perfil no header, o perfil do BOT decide o roteamento da cobrança.
        UUID botProfile = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        StoreBot bot = StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "token", "tokenHash",
                "secret", "secretHash", "Bot Loja", Instant.now());
        bot.assignProfile(botProfile);
        when(botRepository.findById(botId)).thenReturn(Optional.of(bot));

        service.create(command(botId, null, "cob-key-bot"));

        ArgumentCaptor<PixTransaction> txCaptor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(idempotencyPersister).persistPending(txCaptor.capture(), eq("cob-key-bot"));
        assertThat(txCaptor.getValue().profileId()).isEqualTo(botProfile);
    }

    @Test
    void chargeUsesDefaultProfileWhenNoHeaderOrBot() {
        // L245 (NullReturnValsMutator): sem header nem bot, o perfil DEFAULT do
        // lojista é aplicado — retornar null quebraria o roteamento rígido
        // (regressão Spec 91).
        UUID defaultProfile = UUID.randomUUID();
        when(profileRepository.defaultProfileIdOf(MERCHANT_ID)).thenReturn(Optional.of(defaultProfile));

        service.create(command(null, null, "cob-key-default"));

        ArgumentCaptor<PixTransaction> txCaptor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(idempotencyPersister).persistPending(txCaptor.capture(), eq("cob-key-default"));
        assertThat(txCaptor.getValue().profileId()).isEqualTo(defaultProfile);
    }
}
