package com.foxcash.application.service;

import com.foxcash.domain.exception.IdempotencyConflictException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.PspTimeoutException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
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
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Aplicacao: orquestra a criacao de uma transacao Pix.
 *
 * <p>Auditoria 2026-08-05: o PSP e chamado fora da transacao de banco — a
 * conexao do pool nao fica retida durante o I/O de rede da cobranca e falha de
 * save nao deixa cobranca orfa no provedor.
 *
 * <p>Spec 28 (idempotência): mesmo fluxo do {@link CreatePixChargeService} — a
 * {@code idempotencyKey} é obrigatória, a {@code PENDING} é persistida antes do
 * PSP em transação própria e o retry com a mesma chave reusa a MESMA transação.
 */
@Service
public class CreatePixTransactionService implements CreatePixTransactionUseCase {

    private final MerchantRepository merchantRepository;
    private final MerchantProfileRepository profileRepository;
    private final PixChargePersistence chargePersistence;
    private final IdempotencyPersister idempotencyPersister;
    private final TransactionRepository transactionRepository;
    private final PixProviderClient pixProviderClient;
    private final FraudScreeningUseCase fraudScreening;
    private final PortalProperties portalProperties;
    private final AuthenticatedMerchantResolver authenticatedMerchant;

    public CreatePixTransactionService(MerchantRepository merchantRepository,
                                       MerchantProfileRepository profileRepository,
                                       PixChargePersistence chargePersistence,
                                       IdempotencyPersister idempotencyPersister,
                                       TransactionRepository transactionRepository,
                                       PixProviderClient pixProviderClient,
                                       FraudScreeningUseCase fraudScreening,
                                       PortalProperties portalProperties,
                                       AuthenticatedMerchantResolver authenticatedMerchant) {
        this.merchantRepository = merchantRepository;
        this.profileRepository = profileRepository;
        this.chargePersistence = chargePersistence;
        this.idempotencyPersister = idempotencyPersister;
        this.transactionRepository = transactionRepository;
        this.pixProviderClient = pixProviderClient;
        this.fraudScreening = fraudScreening;
        this.portalProperties = portalProperties;
        this.authenticatedMerchant = authenticatedMerchant;
    }

    @Override
    public Result create(Command command) {
        requireIdempotencyKey(command.idempotencyKey());

        // Teto de valor em TODOS os fluxos (auditoria 2026-08-05): a rota legada
        // /api/v1/transactions também respeita foxcash.portal.charge-max-amount —
        // antes o controle existia apenas em foxcash/pix/cob e no portal (achado de
        // homologação: 15000 passava aqui e era barrado lá).
        validateAmount(command.amount());

        // SEC-13 (revisão de segurança 2026-08-11): se o principal autenticado é
        // um LOJISTA (resolver preenchido) mas o merchantId do comando aponta para
        // OUTRO lojista, falha fechada (403) — antes o findById carregava o
        // lojista alheio (IDOR latente; hoje não alcançável pelas rotas, mas o
        // serviço não podia confiar cegamente no merchantId do comando).
        Optional<Merchant> current = authenticatedMerchant.currentMerchant();
        if (current.isPresent() && !current.get().id().equals(command.merchantId())) {
            throw new MerchantAccessDeniedException();
        }
        var merchant = current
                .filter(m -> m.id().equals(command.merchantId()))
                .orElseGet(() -> merchantRepository.findById(command.merchantId())
                        .orElseThrow(() -> new MerchantNotFoundException(command.merchantId())));
        merchant.ensureActive();

        // Antifraude em tempo real (Spec 19): bloqueia antes de criar apenas o
        // comprador com historico de chargeback — velocidade nao bloqueia
        // venda legitima (decisao 2026-08-04).
        String buyerIdHash = buyerIdHash(command.buyerId());
        fraudScreening.screen(buyerIdHash);

        // Retry idempotente (Spec 28): reusa a transação da chave, se houver.
        Optional<PixTransaction> existing = transactionRepository
                .findByMerchantIdAndIdempotencyKey(command.merchantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return handleRetry(existing.get(), command);
        }

        PixTransaction transaction = PixTransaction.create(
                command.merchantId(), command.amount(), command.description(), command.pixKey(), buyerIdHash);
        // Spec 124 (rota viva): a API legada não tem bot — a cobrança vai para o
        // perfil default do lojista (gravado em pix_transactions.profile_id; sem
        // perfil → null → política por risco atual, regressão).
        transaction.attachProfile(profileRepository.defaultProfileIdOf(command.merchantId()).orElse(null));

        // Fase 1 (Spec 28): persiste a PENDING com a chave ANTES do PSP, em
        // transação própria (REQUIRES_NEW) — um timeout não apaga o registro.
        PixTransaction pending = idempotencyPersister.persistPending(transaction, command.idempotencyKey());
        try {
            PixProviderClient.CreateChargeResult charge = pixProviderClient.createCharge(
                    new PixProviderClient.CreateChargeCommand(
                            pending.id(), pending.merchantId(), pending.amount(), pending.pixKey(),
                            pending.description(),
                            null, pending.profileId())); // sem expiração explícita → default configurado do PSP
            // Fase 2: anexa o payload da cobranca.
            chargePersistence.attach(pending, charge);
        } catch (PixProviderClient.PixProviderTimeoutException e) {
            // Timeout do PSP: a cobrança PODE ter sido criada — PENDING persiste.
            throw new PspTimeoutException(e.getMessage(), e);
        } catch (PixProviderClient.PixProviderException e) {
            // Erro real do PSP: a chave já produziu resultado — FAILED.
            chargePersistence.failPending(pending);
            throw e;
        } catch (RuntimeException e) {
            // Falha inesperada (não-PSP): encerra a PENDING (REJECTED).
            chargePersistence.cancelPending(pending);
            throw e;
        }
        return new Result(pending.id());
    }

    /** Retry idempotente (Spec 28): mesma chave → mesma transação; FAILED → 409. */
    private Result handleRetry(PixTransaction tx, Command command) {
        if (tx.status() == TransactionStatus.FAILED) {
            throw new IdempotencyConflictException(command.idempotencyKey());
        }
        if (tx.externalReference() == null && tx.status() == TransactionStatus.PENDING) {
            // Timeout anterior: conclui a cobrança sem duplicar (code = transactionId).
            PixProviderClient.CreateChargeResult charge = pixProviderClient.createCharge(
                    new PixProviderClient.CreateChargeCommand(
                            tx.id(), tx.merchantId(), tx.amount(), tx.pixKey(), tx.description(),
                            null, tx.profileId()));
            chargePersistence.attach(tx, charge);
        }
        return new Result(tx.id());
    }

    /** Spec 28: a chave de idempotência é OBRIGATÓRIA na criação de cobrança. */
    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey e obrigatoria na criacao de cobranca (Spec 28)");
        }
    }

    /**
     * Teto de valor da cobrança ({@code foxcash.portal.charge-max-amount}): aplicado
     * também na rota legada /api/v1/transactions (homologação 2026-08-05) — mesmo
     * contrato de {@code CreatePixChargeService} e do portal.
     */
    private void validateAmount(Money amount) {
        if (amount == null || amount.amount().signum() <= 0
                || amount.amount().compareTo(portalProperties.getChargeMaxAmount()) > 0) {
            throw new IllegalArgumentException(
                    "Valor invalido: deve ser entre 0.01 e " + portalProperties.getChargeMaxAmount());
        }
    }

    /** Hash SHA-256 do buyerId pseudonimo (Spec 19); null se o bot nao enviou. */
    private String buyerIdHash(String buyerId) {
        return (buyerId == null || buyerId.isBlank()) ? null : Sha256Hasher.hex(buyerId);
    }
}
