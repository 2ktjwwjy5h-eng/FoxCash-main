package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.domain.port.out.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PixChargePersistence} (auditoria 2026-08-05): anexa o payload
 * do PSP à PENDING em etapas; attachAndPublish publica CHARGE_CREATED na MESMA
 * transação (Spec 35).
 */
class PixChargePersistenceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final WebhookEventPublisher webhookEventPublisher = mock(WebhookEventPublisher.class);
    private final PixChargePersistence persistence =
            new PixChargePersistence(transactionRepository, webhookEventPublisher);

    private PixTransaction pending() {
        return PixTransaction.restore(UUID.randomUUID(), MERCHANT_ID, Money.of("150.00"), "Cobranca", null,
                null, null, null, null, TransactionStatus.PENDING,
                Instant.now(), Instant.now(), null, null);
    }

    private PixProviderClient.CreateChargeResult charge() {
        return new PixProviderClient.CreateChargeResult("E123", "qrcode-base64", "000201...");
    }

    @Test
    void attachAndPublishReturnsSavedAndPublishesChargeCreated() {
        PixTransaction pending = pending();
        when(transactionRepository.save(any(PixTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        PixTransaction returned = persistence.attachAndPublish(pending, charge());

        // Mutation (NullReturnVals L49): devolve a transação salva (não null).
        assertThat(returned).isNotNull();
        // Mutation (VoidMethodCall L46): publica CHARGE_CREATED na MESMA transação.
        // Spec 125 (trace): o evento carrega a referência da transação.
        // Spec 127: data.traceId explícito (traceId == transactionId hoje).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(webhookEventPublisher).publish(eq(MERCHANT_ID), eq(WebhookEventType.CHARGE_CREATED),
                dataCaptor.capture(), eq("TRANSACTION"), eq(returned.id()));
        assertThat(dataCaptor.getValue()).containsEntry("traceId", returned.traceId());
        assertThat(dataCaptor.getValue()).containsEntry("transactionId", returned.id());
    }

    @Test
    void attachStoresPspPayloadWithoutPublishing() {
        PixTransaction pending = pending();
        when(transactionRepository.save(any(PixTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        persistence.attach(pending, charge());

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().externalReference()).isEqualTo("E123");
        assertThat(captor.getValue().pixQrCode()).isEqualTo("qrcode-base64");
        assertThat(captor.getValue().pixCopyPaste()).isEqualTo("000201...");
        // attach NÃO publica evento — apenas attachAndPublish.
        verify(webhookEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void attachAndPublishSkipsPublishWhenConcurrentRetryAlreadyAttached() {
        // Spec 62/F4 — corrida de retries com a MESMA idempotencyKey: outra
        // requisição já anexou a cobrança (external_reference preenchido) entre
        // a leitura da PENDING e o attach. O lock (findByIdForUpdate) serializa;
        // o perdedor NÃO re-publica CHARGE_CREATED (1 evento por transação).
        PixTransaction attached = PixTransaction.restore(
                UUID.randomUUID(), MERCHANT_ID, Money.of("150.00"), "Cobranca", null,
                null, "E123", "qrcode-base64", "000201...", TransactionStatus.PENDING,
                Instant.now(), Instant.now(), null, null);
        when(transactionRepository.findByIdForUpdate(any(UUID.class)))
                .thenReturn(Optional.of(attached));

        PixTransaction returned = persistence.attachAndPublish(pending(), charge());

        // Devolve a transação JÁ anexada (o fluxo segue com o mesmo id)...
        assertThat(returned).isSameAs(attached);
        // ...sem re-salvar nem republicar CHARGE_CREATED (0 duplicação de evento).
        verify(transactionRepository, never()).save(any(PixTransaction.class));
        verify(webhookEventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    // ---------- SEC-26 (revisão de segurança 2026-08-11): TOCTOU no encerramento da PENDING ----------

    @Test
    void failPendingDoesNotOverwriteConcurrentlyApprovedTransaction() {
        // SEC-26: o PSP criou a cobrança e o webhook PAID chegou em paralelo
        // (APPROVED + crédito do wallet) ANTES de a resposta de erro chegar ao
        // serviço. A snapshot stale (PENDING em memória) NÃO pode ser salva por
        // cima — a venda paga seria marcada FAILED (transação morta + retry 409).
        PixTransaction staleSnapshot = pending(); // PENDING em memória
        PixTransaction approvedInDb = PixTransaction.restore(
                staleSnapshot.id(), MERCHANT_ID, Money.of("150.00"), "Cobranca", null,
                null, "E123", "qrcode-base64", "000201...", TransactionStatus.APPROVED,
                Instant.now(), Instant.now(), Instant.now(), null);
        when(transactionRepository.findByIdForUpdate(staleSnapshot.id()))
                .thenReturn(Optional.of(approvedInDb));

        persistence.failPending(staleSnapshot);

        // No-op: a linha APPROVED prevalece (nada é sobrescrito).
        verify(transactionRepository, never()).save(any(PixTransaction.class));
    }

    @Test
    void failPendingMarksFreshPendingAsFailedUnderLock() {
        // Erro REAL do PSP (cobrança nunca criada): PENDING sem external_reference
        // → FAILED sob lock (o desfecho continua sendo o do Spec 28).
        PixTransaction pending = pending();
        when(transactionRepository.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        when(transactionRepository.save(any(PixTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        persistence.failPending(pending);

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void cancelPendingDoesNotRejectTransactionWithAttachedCharge() {
        // SEC-26: attachAndPublish ANEXOU o payload (external_reference
        // preenchido) e uma falha POSTERIOR (publicação) lançou → cancelPending
        // NÃO pode marcar REJECTED uma cobrança que o comprador ainda pode pagar;
        // o desfecho fica para o webhook/reconciliação.
        PixTransaction staleSnapshot = pending();
        PixTransaction attachedInDb = PixTransaction.restore(
                staleSnapshot.id(), MERCHANT_ID, Money.of("150.00"), "Cobranca", null,
                null, "E123", "qrcode-base64", "000201...", TransactionStatus.PENDING,
                Instant.now(), Instant.now(), null, null);
        when(transactionRepository.findByIdForUpdate(staleSnapshot.id()))
                .thenReturn(Optional.of(attachedInDb));

        persistence.cancelPending(staleSnapshot);

        verify(transactionRepository, never()).save(any(PixTransaction.class));
    }

    @Test
    void cancelPendingMarksFreshPendingAsRejectedUnderLock() {
        // Falha inesperada ANTES do attach: PENDING sem external_reference →
        // REJECTED sob lock (comportamento original preservado).
        PixTransaction pending = pending();
        when(transactionRepository.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        when(transactionRepository.save(any(PixTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        persistence.cancelPending(pending);

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.REJECTED);
    }
}
