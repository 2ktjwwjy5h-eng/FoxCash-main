package com.foxcash.application.service;

import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.exception.IdempotencyConflictException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.MerchantNotActiveException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.PspTimeoutException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.CreatePixTransactionUseCase;
import com.foxcash.domain.port.in.FraudScreeningUseCase;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import com.foxcash.infrastructure.security.AuthenticatedMerchantResolver;
import com.foxcash.infrastructure.security.Sha256Hasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link CreatePixTransactionService} (rota legada /api/v1/transactions,
 * Spec 28): idempotência OBRIGATÓRIA, teto de valor ({@code foxcash.portal.
 * charge-max-amount}) também na rota legada, triagem antifraude antes do PSP e
 * retry idempotente (FAILED → 409; PENDING sem QR → conclui a cobrança sem duplicar).
 */
class CreatePixTransactionServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String KEY = "cob-key-1";
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MerchantProfileRepository profileRepository = mock(MerchantProfileRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final PixProviderClient pixProviderClient = mock(PixProviderClient.class);
    private final FraudScreeningUseCase fraudScreening = mock(FraudScreeningUseCase.class);
    private final PixChargePersistence chargePersistence = mock(PixChargePersistence.class);
    private final IdempotencyPersister idempotencyPersister = mock(IdempotencyPersister.class);
    private final AuthenticatedMerchantResolver authenticatedMerchant = mock(AuthenticatedMerchantResolver.class);

    private PortalProperties portalProperties;
    private CreatePixTransactionService service;

    @BeforeEach
    void setUp() {
        portalProperties = new PortalProperties();
        service = new CreatePixTransactionService(
                merchantRepository, profileRepository, chargePersistence, idempotencyPersister,
                transactionRepository, pixProviderClient, fraudScreening, portalProperties,
                authenticatedMerchant);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant()));
        when(pixProviderClient.createCharge(any())).thenReturn(
                new PixProviderClient.CreateChargeResult("E123", "qrcode-base64", "000201..."));
        // IdempotencyPersister (Spec 28): transação própria REQUIRES_NEW — devolve
        // o tx com a chave atribuída (simula o commit antecipado da PENDING).
        when(idempotencyPersister.persistPending(any(), anyString())).thenAnswer(inv -> {
            PixTransaction tx = inv.getArgument(0);
            return tx.withIdempotencyKey(inv.getArgument(1));
        });
    }

    private Merchant activeMerchant() {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, Instant.now());
    }

    private Merchant inactiveMerchant() {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.SUSPENDED, PLAN, Money.of("0.00"), false, Instant.now());
    }

    private CreatePixTransactionUseCase.Command command(String buyerId) {
        return command(buyerId, Money.of("150.00"), KEY);
    }

    private CreatePixTransactionUseCase.Command command(String buyerId, Money amount, String key) {
        return new CreatePixTransactionUseCase.Command(
                MERCHANT_ID, amount, "Camiseta", "pixkey-123", buyerId, key);
    }

    private PixTransaction restore(UUID id, TransactionStatus status, String externalReference) {
        Instant now = Instant.now();
        return PixTransaction.restore(id, MERCHANT_ID, Money.of("150.00"), "Camiseta", "pixkey-123",
                null, externalReference, null, null, status, now, now, null, null, KEY);
    }

    @Test
    void createsChargeWithPspAndAttachesResult() {
        CreatePixTransactionUseCase.Result result = service.create(command("buyer-1"));

        // Fase 1: PENDING com a chave ANTES do PSP (a chave é atribuída pelo
        // persister na transação própria — o arg capturado ainda não a tem).
        ArgumentCaptor<PixTransaction> pendingCaptor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(idempotencyPersister).persistPending(pendingCaptor.capture(), eq(KEY));
        PixTransaction pending = pendingCaptor.getValue();
        assertThat(pending.buyerIdHash()).isEqualTo(Sha256Hasher.hex("buyer-1"));

        // Fase 2: PSP chamado com o id da PENDING, sem expiração explícita.
        ArgumentCaptor<PixProviderClient.CreateChargeCommand> cmd =
                ArgumentCaptor.forClass(PixProviderClient.CreateChargeCommand.class);
        verify(pixProviderClient).createCharge(cmd.capture());
        assertThat(cmd.getValue().transactionId()).isEqualTo(pending.id());
        assertThat(cmd.getValue().amount().amount()).isEqualByComparingTo("150.00");
        assertThat(cmd.getValue().pixKey()).isEqualTo("pixkey-123");
        assertThat(cmd.getValue().description()).isEqualTo("Camiseta");
        assertThat(cmd.getValue().expirationSeconds()).isNull();

        // attach recebe o pending RETORNADO pelo persister (com a chave) + o payload do PSP.
        verify(chargePersistence).attach(any(PixTransaction.class),
                eq(new PixProviderClient.CreateChargeResult("E123", "qrcode-base64", "000201...")));
        assertThat(result.transactionId()).isEqualTo(pending.id());
    }

    @Test
    void nullIdempotencyKeyIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.create(command("buyer-1", Money.of("150.00"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        verify(merchantRepository, never()).findById(any());
        verify(idempotencyPersister, never()).persistPending(any(), anyString());
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> service.create(command("buyer-1", Money.of("150.00"), "   ")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(merchantRepository, never()).findById(any());
    }

    @Test
    void nullAmountIsRejected() {
        assertThatThrownBy(() -> service.create(command("buyer-1", null, KEY)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(merchantRepository, never()).findById(any());
    }

    @Test
    void zeroAmountIsRejected() {
        // L142 boundary (`signum() <= 0` → `< 0`): 0.00 passaria no mutante.
        assertThatThrownBy(() -> service.create(command("buyer-1", Money.of("0.00"), KEY)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(merchantRepository, never()).findById(any());
    }

    @Test
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> service.create(command("buyer-1", Money.of("-1.00"), KEY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountAboveChargeMaxIsRejected() {
        assertThatThrownBy(() -> service.create(command("buyer-1", Money.of("10000.01"), KEY)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(merchantRepository, never()).findById(any());
    }

    @Test
    void amountExactlyAtChargeMaxIsAccepted() {
        // L143 boundary (`compareTo(max) > 0` → `>= 0`): valor == max passaria no mutante.
        service.create(command("buyer-1", Money.of("10000.00"), KEY));
        verify(idempotencyPersister).persistPending(any(), anyString());
    }

    @Test
    void merchantNotFoundThrows() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(MerchantNotFoundException.class);
        verify(pixProviderClient, never()).createCharge(any());
    }

    @Test
    void inactiveMerchantIsRejectedBeforePsp() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(inactiveMerchant()));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(MerchantNotActiveException.class);
        verify(pixProviderClient, never()).createCharge(any());
        verify(idempotencyPersister, never()).persistPending(any(), anyString());
    }

    @Test
    void blockedBuyerIsRejectedBeforePspCall() {
        doThrow(new FraudRuleViolationException("fraud_buyer_blocked", "Comprador bloqueado por historico de chargeback"))
                .when(fraudScreening).screen(anyString());

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(FraudRuleViolationException.class);
        verify(pixProviderClient, never()).createCharge(any());
        verify(idempotencyPersister, never()).persistPending(any(), anyString());
    }

    @Test
    void retryOfFailedTransactionReturnsConflict() {
        when(transactionRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, KEY))
                .thenReturn(Optional.of(restore(UUID.randomUUID(), TransactionStatus.FAILED, null)));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(pixProviderClient, never()).createCharge(any());
        verify(idempotencyPersister, never()).persistPending(any(), anyString());
    }

    @Test
    void retryOfPendingWithoutExternalReferenceCompletesCharge() {
        // Timeout anterior: PENDING sem QR — conclui a cobrança SEM duplicar.
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, KEY))
                .thenReturn(Optional.of(restore(txId, TransactionStatus.PENDING, null)));

        CreatePixTransactionUseCase.Result result = service.create(command("buyer-1"));

        ArgumentCaptor<PixProviderClient.CreateChargeCommand> cmd =
                ArgumentCaptor.forClass(PixProviderClient.CreateChargeCommand.class);
        verify(pixProviderClient).createCharge(cmd.capture());
        assertThat(cmd.getValue().transactionId()).isEqualTo(txId);
        verify(chargePersistence).attach(any(PixTransaction.class), any(PixProviderClient.CreateChargeResult.class));
        verify(idempotencyPersister, never()).persistPending(any(), anyString());
        assertThat(result.transactionId()).isEqualTo(txId);
    }

    @Test
    void retryOfPendingWithExternalReferenceDoesNotCallPspAgain() {
        // Cobrança já concluída (tem QR): o retry reusa o tx sem nova chamada ao PSP.
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByMerchantIdAndIdempotencyKey(MERCHANT_ID, KEY))
                .thenReturn(Optional.of(restore(txId, TransactionStatus.PENDING, "E123")));

        CreatePixTransactionUseCase.Result result = service.create(command("buyer-1"));

        verify(pixProviderClient, never()).createCharge(any());
        verify(chargePersistence, never()).attach(any(), any());
        assertThat(result.transactionId()).isEqualTo(txId);
    }

    @Test
    void pspTimeoutWrapsAndKeepsPending() {
        doThrow(new PixProviderClient.PixProviderTimeoutException("timeout", new RuntimeException()))
                .when(pixProviderClient).createCharge(any());

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(PspTimeoutException.class);
        verify(chargePersistence, never()).failPending(any());
        verify(chargePersistence, never()).cancelPending(any());
    }

    @Test
    void pspErrorFailsPending() {
        doThrow(new PixProviderClient.PixProviderException("400 invalid key"))
                .when(pixProviderClient).createCharge(any());

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(PixProviderClient.PixProviderException.class);
        // Erro real do PSP: a chave já produziu resultado → FAILED (retry → 409).
        verify(chargePersistence).failPending(any(PixTransaction.class));
        verify(chargePersistence, never()).cancelPending(any());
    }

    @Test
    void unexpectedErrorCancelsPending() {
        doThrow(new IllegalStateException("boom"))
                .when(pixProviderClient).createCharge(any());

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(IllegalStateException.class);
        verify(chargePersistence, never()).failPending(any());
        verify(chargePersistence).cancelPending(any(PixTransaction.class));
    }

    @Test
    void blankBuyerIdScreensNullHash() {
        service.create(command("   "));

        verify(fraudScreening).screen(null);
    }

    @Test
    void nullBuyerIdScreensNullHash() {
        service.create(command(null));

        verify(fraudScreening).screen(null);
        verify(idempotencyPersister).persistPending(any(), anyString());
    }

    // ---------- SEC-13 (revisão de segurança 2026-08-11): IDOR latente ----------

    @Test
    void authenticatedMerchantForAnotherMerchantIsDenied() {
        // SEC-13: o principal autenticado é o lojista A (resolver preenchido) mas
        // o merchantId do comando aponta para o lojista B → 403 fechado. O serviço
        // não pode confiar cegamente no merchantId do comando (IDOR latente).
        Merchant other = Merchant.restore(UUID.randomUUID(), "Loja B LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, Instant.now());
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.create(command("buyer-1")))
                .isInstanceOf(MerchantAccessDeniedException.class);
        verify(idempotencyPersister, never()).persistPending(any(), anyString());
        verify(pixProviderClient, never()).createCharge(any());
    }

    @Test
    void authenticatedMerchantForOwnMerchantUsesResolvedEntity() {
        // SEC-13 (sem regressão): principal autenticado = o MESMO lojista do
        // comando → o resolver fornece a entidade (sem findById redundante).
        Merchant current = activeMerchant();
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.of(current));

        CreatePixTransactionUseCase.Result result = service.create(command("buyer-1"));

        assertThat(result.transactionId()).isNotNull();
        verify(merchantRepository, never()).findById(MERCHANT_ID);
        verify(idempotencyPersister).persistPending(any(), anyString());
    }

    // ---------- Spec 124 (rota viva): perfil default na rota legada ----------

    @Test
    void legacyRouteAttachesDefaultProfileToPersistedTransaction() {
        // L111 (VoidMethodCallMutator): a rota legada (sem bot) aplica o perfil
        // DEFAULT do lojista na transação persistida (Spec 124 — gravado em
        // pix_transactions.profile_id; sem perfil → null → regressão).
        UUID profileId = UUID.randomUUID();
        when(profileRepository.defaultProfileIdOf(MERCHANT_ID)).thenReturn(Optional.of(profileId));

        service.create(command("buyer-1"));

        ArgumentCaptor<PixTransaction> txCaptor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(idempotencyPersister).persistPending(txCaptor.capture(), eq(KEY));
        assertThat(txCaptor.getValue().profileId()).isEqualTo(profileId);
    }
}
