package com.foxcash.application.service;

import com.foxcash.domain.exception.IdempotencyConflictException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.PspTimeoutException;
import com.foxcash.domain.exception.TooManyPendingChargesException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicacao: cria uma cobranca Pix (QR Code + Pix Copia e Cola) para um lojista.
 *
 * <p>Auditoria 2026-08-05: o PSP e chamado fora da transacao de banco — a
 * conexao do pool nao fica retida durante o I/O de rede da cobranca e falha de
 * save nao deixa cobranca orfa no provedor.
 *
 * <p>Spec 28 (idempotência): a {@code idempotencyKey} é obrigatória e única por
 * lojista. A {@code PENDING} é persistida ANTES do PSP em transação própria
 * ({@link IdempotencyPersister}) — um timeout não apaga o registro; o retry com
 * a mesma chave reusa a MESMA transação. Erro real do PSP → {@code FAILED}
 * (retry → 409 {@code idempotency_conflict}); timeout → 502 {@code psp_timeout}
 * com a {@code PENDING} recuperável pelo retry ou pela reconciliação (Spec 30).
 */
@Service
public class CreatePixChargeService implements CreatePixChargeUseCase {

    /** Teto de validade da cobrança em segundos (auditoria 2026-08-05/B-04):
     * 24h — mesmo limite do Pagar.me v5 (payment.pix.expires_in). */
    public static final int MAX_EXPIRATION_SECONDS = 86_400;

    private final MerchantRepository merchantRepository;
    private final MerchantProfileRepository profileRepository;
    private final StoreBotRepository botRepository;
    private final PixChargePersistence chargePersistence;
    private final IdempotencyPersister idempotencyPersister;
    private final TransactionRepository transactionRepository;
    private final PixProviderClient pixProviderClient;
    private final FraudScreeningUseCase fraudScreening;
    private final PlatformActivityRecorder activityRecorder;
    private final PortalProperties portalProperties;
    private final PricingProperties pricingProperties;
    private final AuthenticatedMerchantResolver authenticatedMerchant;

    public CreatePixChargeService(MerchantRepository merchantRepository,
                                  MerchantProfileRepository profileRepository,
                                  StoreBotRepository botRepository,
                                  PixChargePersistence chargePersistence,
                                  IdempotencyPersister idempotencyPersister,
                                  TransactionRepository transactionRepository,
                                  PixProviderClient pixProviderClient,
                                  FraudScreeningUseCase fraudScreening,
                                  PlatformActivityRecorder activityRecorder,
                                  PortalProperties portalProperties,
                                  PricingProperties pricingProperties,
                                  AuthenticatedMerchantResolver authenticatedMerchant) {
        this.merchantRepository = merchantRepository;
        this.profileRepository = profileRepository;
        this.botRepository = botRepository;
        this.chargePersistence = chargePersistence;
        this.idempotencyPersister = idempotencyPersister;
        this.transactionRepository = transactionRepository;
        this.pixProviderClient = pixProviderClient;
        this.fraudScreening = fraudScreening;
        this.activityRecorder = activityRecorder;
        this.portalProperties = portalProperties;
        this.pricingProperties = pricingProperties;
        this.authenticatedMerchant = authenticatedMerchant;
    }

    @Override
    public Result create(Command command) {
        requireIdempotencyKey(command.idempotencyKey());

        // Spec 65 (Fase 1): o filtro de auth ja carregou o lojista no request —
        // reusa a MESMA entidade em vez de refazer o findById (capacity-model F1:
        // ~5-6 SELECTs de auth por venda -> 2 por request). Fora de request HTTP
        // (jobs/testes/chamadas internas) o fallback ao repositorio mantem o
        // comportamento original.
        // SEC-13 (revisão de segurança 2026-08-11): se o principal autenticado é
        // um LOJISTA (resolver preenchido) mas o merchantId do comando aponta para
        // OUTRO lojista, falha fechada (403) — antes o fallback findById carregava
        // o lojista alheio (IDOR latente; hoje não alcançável pelas rotas, mas o
        // serviço não podia confiar cegamente no merchantId do comando).
        Optional<Merchant> current = authenticatedMerchant.currentMerchant();
        if (current.isPresent() && !current.get().id().equals(command.merchantId())) {
            throw new MerchantAccessDeniedException();
        }
        Merchant merchant = current
                .filter(m -> m.id().equals(command.merchantId()))
                .orElseGet(() -> merchantRepository.findById(command.merchantId())
                        .orElseThrow(() -> new MerchantNotFoundException(command.merchantId())));
        merchant.ensureActive();

        // Validações de contrato (QA 2026-08-04): teto de valor em TODOS os
        // fluxos de cobrança (foxcash/pix/cob, portal avulsa e gift) e expiração
        // positiva quando informada — antes o valor acima do limite só era
        // barrado no portal, e a expiração era aceita mas ignorada.
        validateAmount(command.amount());
        validateExpirationSeconds(command.expirationSeconds());

        // Antifraude em tempo real (Spec 19): bloqueia antes de criar apenas o
        // comprador com historico de chargeback — velocidade nao bloqueia
        // venda legitima (decisao 2026-08-04).
        String buyerIdHash = buyerIdHash(command.buyerId());
        fraudScreening.screen(buyerIdHash);

        // Retry idempotente (Spec 28): a chave já produziu uma transação? Reusa
        // a MESMA — nunca duplica a cobrança no PSP sob timeout.
        Optional<PixTransaction> existing = transactionRepository
                .findByMerchantIdAndIdempotencyKey(command.merchantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return handleRetry(existing.get(), command);
        }

        // Cap de pendentes (Spec 58): a idempotência vem PRIMEIRO (acima) — um
        // replay legítimo NUNCA vira 409 por cap. Só cobranças NOVAS chegam
        // aqui; com o teto de PENDING atingido, responde 409 (degradação
        // controlada — o lojista não acumula pendências ilimitadas).
        ensurePendingChargesBelowCap(command.merchantId());

        // Cobranca: sem pix_key dedicada (a chave fica embutida no BR Code).
        // Decisao de produto (2026-08-04): nenhum dado pessoal do comprador e
        // capturado — o contato com o cliente e do bot do lojista.
        String description = descriptionOf(command.description());
        PixTransaction transaction = PixTransaction.create(
                command.merchantId(), command.amount(), description, null, buyerIdHash,
                command.planId(), command.botId(), command.platform(), command.platformUserId(),
                command.termsAcceptedAt(), command.buyerIp(), command.buyerDevice());
        // Spec 124 (rota viva): resolve o perfil de operação da cobrança — header
        // validado (portal) vence; senão bot → perfil do bot; sem bot → default do
        // lojista. O perfil decide o roteamento (override vence risco — Spec 122)
        // e é gravado em pix_transactions.profile_id (V70) para o filtro rígido.
        UUID profileId = resolveProfileId(command.merchantId(), command.botId(), command.profileId());
        transaction.attachProfile(profileId);

        // Fase 1 (Spec 28): persiste a PENDING com a chave ANTES do PSP, em
        // transação própria (REQUIRES_NEW) — um timeout não apaga o registro e
        // o retry com a mesma chave nunca duplica.
        PixTransaction pending;
        try {
            pending = idempotencyPersister.persistPending(transaction, command.idempotencyKey());
        } catch (DataIntegrityViolationException e) {
            // Auditoria 2026-08-05 (M-A2): corrida — duas requisições com a MESMA
            // chave passaram o pré-check (findBy) simultaneamente e o INSERT da
            // perdedora esbarrou no índice único uq_pix_transactions_idempotency.
            // Mesmo contrato dos demais fluxos (RegisterMerchant/PasswordReset):
            // 409 idempotency_conflict — o cliente re-consulta e obtém a transação
            // existente (sem duplicação financeira; o índice é o backstop).
            throw new IdempotencyConflictException(command.idempotencyKey());
        }
        try {
            PixProviderClient.CreateChargeResult charge = pixProviderClient.createCharge(
                    new PixProviderClient.CreateChargeCommand(
                            pending.id(), pending.merchantId(), pending.amount(), null, description,
                            command.expirationSeconds(), pending.profileId()));
            // Fase 2: anexa o payload + CHARGE_CREATED na mesma transacao.
            chargePersistence.attachAndPublish(pending, charge);
            // Spec 41: relatório de atividades — cobrança criada (valor sem PII).
            activityRecorder.record(PlatformActorType.MERCHANT, pending.merchantId(),
                    PlatformAction.CHARGE_CREATED, "TRANSACTION", pending.id(), pending.merchantId(),
                    Map.of("amount", pending.amount().amount()));
        } catch (PixProviderClient.PixProviderTimeoutException e) {
            // Timeout do PSP: a cobrança PODE ter sido criada. A PENDING (sem QR)
            // persiste e o retry com a mesma chave conclui (502 ao cliente agora).
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

    /**
     * Retry com a mesma chave (Spec 28): transação já criada para esta chave.
     * Se ficou {@code PENDING} sem QR (timeout anterior), re-tenta o PSP com o
     * MESMO transactionId (o {@code code} do Pagar.me é o próprio transactionId
     * — idempotente no provedor real). {@code FAILED} → 409 {@code idempotency_conflict}.
     */
    private Result handleRetry(PixTransaction tx, Command command) {
        if (tx.status() == TransactionStatus.FAILED) {
            throw new IdempotencyConflictException(command.idempotencyKey());
        }
        if (tx.externalReference() == null && tx.status() == TransactionStatus.PENDING) {
            // Timeout anterior: conclui a cobrança sem duplicar (mesmo perfil persistido).
            PixProviderClient.CreateChargeResult charge = pixProviderClient.createCharge(
                    new PixProviderClient.CreateChargeCommand(
                            tx.id(), tx.merchantId(), tx.amount(), null, tx.description(),
                            command.expirationSeconds(), tx.profileId()));
            chargePersistence.attachAndPublish(tx, charge);
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
     * Spec 124 (rota viva): resolve o perfil de operação da cobrança na criação.
     * Prioridade: header validado (portal — {@code fromHeader}) → bot do comando
     * ({@code store_bots.profile_id}) → perfil default do lojista. {@code null}
     * quando nada resolve (lojista sem perfil — dado legado) → política por risco
     * atual (regressão Spec 91).
     */
    private UUID resolveProfileId(UUID merchantId, UUID botId, UUID fromHeader) {
        if (fromHeader != null) {
            return fromHeader;
        }
        if (botId != null) {
            return botRepository.findById(botId)
                    .map(com.foxcash.domain.model.storebot.StoreBot::profileId)
                    .orElse(null);
        }
        return profileRepository.defaultProfileIdOf(merchantId).orElse(null);
    }

    /**
     * Teto de valor da cobrança ({@code foxcash.portal.charge-max-amount}): aplicado
     * em todos os fluxos de criação — antes apenas o portal validava.
     */
    private void validateAmount(Money amount) {
        if (amount == null || amount.amount().signum() <= 0
                || amount.amount().compareTo(portalProperties.getChargeMaxAmount()) > 0) {
            throw new IllegalArgumentException(
                    "Valor invalido: deve ser entre 0.01 e " + portalProperties.getChargeMaxAmount());
        }
    }

    /** Validade da cobrança em segundos: se informada, deve ser positiva. */
    private void validateExpirationSeconds(Integer expirationSeconds) {
        if (expirationSeconds != null && expirationSeconds <= 0) {
            throw new IllegalArgumentException("expirationSeconds deve ser positivo quando informado");
        }
        // Auditoria 2026-08-05 (B-04): sem teto, uma cobrança poderia não expirar
        // (expiração não informada pelo PSP real = comportamento indefinido). Teto
        // de 24h (86400s) — mesmo limite do Pagar.me v5 (payment.pix.expires_in).
        if (expirationSeconds != null && expirationSeconds > MAX_EXPIRATION_SECONDS) {
            throw new IllegalArgumentException(
                    "expirationSeconds deve ser no maximo " + MAX_EXPIRATION_SECONDS
                            + " segundos (24h)");
        }
    }

    /** Descricao padrao quando o portal/bot nao informou uma (Spec 23). */
    private String descriptionOf(String description) {
        return (description == null || description.isBlank()) ? "Cobranca Pix" : description;
    }

    /** Hash SHA-256 do buyerId pseudonimo (Spec 19); null se o bot nao enviou. */
    private String buyerIdHash(String buyerId) {
        return (buyerId == null || buyerId.isBlank()) ? null : Sha256Hasher.hex(buyerId);
    }

    /**
     * Cap de cobranças PENDENTES por lojista (Spec 58): acima do teto
     * ({@code foxcash.pricing.max-pending-charges}, baseline 100), a criação de
     * NOVA cobrança responde 409 — degradação controlada (o lojista não acumula
     * pendências ilimitadas; após o pagamento, volta a funcionar). Chamado
     * SOMENTE para cobranças novas (a idempotência já foi checada antes).
     */
    private void ensurePendingChargesBelowCap(UUID merchantId) {
        long pending = transactionRepository.countByMerchantIdAndStatus(
                merchantId, TransactionStatus.PENDING);
        if (pending >= pricingProperties.getMaxPendingCharges()) {
            throw new TooManyPendingChargesException(pricingProperties.getMaxPendingCharges());
        }
    }
}
