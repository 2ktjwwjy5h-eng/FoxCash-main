package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantAlreadyExistsException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.MerchantApiKey;
import com.foxcash.domain.model.fingerprint.DeviceFingerprint;
import com.foxcash.domain.model.gift.GiftPreset;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.in.RegisterMerchantUseCase;
import com.foxcash.domain.port.out.ApiKeyGenerator;
import com.foxcash.domain.port.out.GiftPresetRepository;
import com.foxcash.domain.port.out.MerchantApiKeyRepository;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.service.EmailValidator;
import com.foxcash.domain.service.PasswordPolicy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Aplicacao: cadastro de lojistas (Spec 39).
 *
 * <p>Cadastro com **nome, e-mail e senha** (sem CPF/CNPJ): o e-mail é único
 * (normalizado em minúsculas; duplicado → 409) e a **senha é escolhida pelo
 * usuário** — apenas o hash BCrypt é persistido em {@code merchant_credentials}.
 * A chave de API (Spec 12) é gerada e devolvida **uma única vez** no cadastro
 * (apenas o hash SHA-256 é persistido).
 */
@Service
public class RegisterMerchantService implements RegisterMerchantUseCase {

    /** Valores padrao do catalogo de Gift PIX criados no cadastro (Spec 23). */
    private static final List<String> DEFAULT_GIFT_PRESETS = List.of("5", "10", "20", "50");

    private final MerchantRepository merchantRepository;
    private final MerchantApiKeyRepository merchantApiKeyRepository;
    private final ApiKeyGenerator apiKeyGenerator;
    private final PricingPlan pricingPlan;
    private final MerchantCredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;
    private final GiftPresetRepository giftPresetRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final DeviceFingerprintService fingerprintService;
    private final Clock clock;

    public RegisterMerchantService(MerchantRepository merchantRepository,
                                   MerchantApiKeyRepository merchantApiKeyRepository,
                                   ApiKeyGenerator apiKeyGenerator,
                                   PricingPlan pricingPlan,
                                   MerchantCredentialRepository credentialRepository,
                                   PasswordHasher passwordHasher,
                                   GiftPresetRepository giftPresetRepository,
                                   PlatformActivityRecorder activityRecorder,
                                   DeviceFingerprintService fingerprintService,
                                   Clock clock) {
        this.merchantRepository = merchantRepository;
        this.merchantApiKeyRepository = merchantApiKeyRepository;
        this.apiKeyGenerator = apiKeyGenerator;
        this.pricingPlan = pricingPlan;
        this.credentialRepository = credentialRepository;
        this.passwordHasher = passwordHasher;
        this.giftPresetRepository = giftPresetRepository;
        this.activityRecorder = activityRecorder;
        this.fingerprintService = fingerprintService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Result register(Command command) {
        requireName(command.name());
        String email = normalizeEmail(command.email());
        requirePassword(command.password());
        if (merchantRepository.findByEmail(email).isPresent()) {
            throw new MerchantAlreadyExistsException();
        }
        ApiKeyGenerator.ApiKeyPair key = apiKeyGenerator.generate();
        Merchant merchant = Merchant.register(command.name(), email, pricingPlan, key.hash());
        Merchant saved;
        try {
            saved = merchantRepository.save(merchant);
        } catch (DataIntegrityViolationException e) {
            // Race com cadastro concorrente do mesmo e-mail: o índice único
            // uq_merchants_email é o backstop — converte para o 409 canônico.
            throw new MerchantAlreadyExistsException();
        }

        // Senha ESCOLHIDA pelo usuário (Spec 39): hash BCrypt persistido em
        // merchant_credentials — o texto claro nunca é armazenado.
        Instant now = clock.instant();
        MerchantCredential credential = MerchantCredential.create(
                saved.id(), passwordHasher.hash(command.password()), now);
        credentialRepository.save(credential);

        // Chave do cadastro tambem na tabela gerenciável (Spec 24): escopo
        // total, mesmo hash de merchants.api_key_hash (Spec 12 mantida).
        merchantApiKeyRepository.save(MerchantApiKey.create(
                saved.id(), "Chave do cadastro", key.plaintext(), key.hash(),
                Set.of(ApiKeyScope.values()), now));

        // Catalogo de Gift PIX (Spec 23): defaults R$ 5/10/20/50 ativos.
        for (String value : DEFAULT_GIFT_PRESETS) {
            giftPresetRepository.save(GiftPreset.create(saved.id(), Money.of(value), "R$ " + value, now));
        }

        // Spec 41: relatório de atividades — cadastro de lojista registrado.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", saved.name());
        // Spec 141: fingerprint do navegador (sinal de risco PASSIVO, best-effort).
        fingerprintService.record(command.fingerprintHash(), saved.id())
                .ifPresent(fp -> details.put("fingerprint",
                        Map.of("hash", fp.fingerprintHash(), "distinctMerchants", fp.distinctMerchants())));
        activityRecorder.record(PlatformActorType.MERCHANT, saved.id(), PlatformAction.MERCHANT_REGISTERED,
                "MERCHANT", saved.id(), saved.id(), details);

        return new Result(saved.id(), key.plaintext());
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name é obrigatório no cadastro");
        }
        if (name.trim().length() > 120) {
            throw new IllegalArgumentException("name deve ter no máximo 120 caracteres");
        }
    }

    private String normalizeEmail(String email) {
        if (!EmailValidator.isValid(email)) {
            throw new IllegalArgumentException("e-mail inválido");
        }
        return email.trim().toLowerCase();
    }

    /** Politica de senha unica (auditoria 2026-08-05/B-07): 8-72 + complexidade. */
    private void requirePassword(String password) {
        PasswordPolicy.validate(password);
    }
}
