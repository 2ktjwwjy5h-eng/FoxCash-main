package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantAlreadyExistsException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do use case de cadastro de lojistas (Spec 39: cadastro com nome,
 * e-mail e senha — sem CPF/CNPJ). E-mail duplicado → 409; a senha é escolhida
 * pelo usuário (apenas o hash BCrypt é persistido); a chave de API é emitida
 * uma única vez (apenas o hash SHA-256 persistido).
 */
class RegisterMerchantServiceTest {

    private static final String API_KEY = "mch_0123456789abcdef0123456789abcdef";
    private static final String API_KEY_HASH = "a".repeat(64);
    // Política de senha (auditoria 2026-08-05/B-07): 8-72 + minúsculas/maiúsculas/números.
    private static final String PASSWORD = "Senha-segura-123";
    private static final String PASSWORD_HASH = "$2a$10$bcryptfakehash";
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final String EMAIL_A = "loja.a@example.com";
    private static final String EMAIL_B = "loja.b@example.com";

    private final MerchantRepository repository = mock(MerchantRepository.class);
    private final MerchantApiKeyRepository merchantApiKeyRepository = mock(MerchantApiKeyRepository.class);
    private final ApiKeyGenerator apiKeyGenerator = mock(ApiKeyGenerator.class);
    private final MerchantCredentialRepository credentialRepository = mock(MerchantCredentialRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final GiftPresetRepository giftPresetRepository = mock(GiftPresetRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private RegisterMerchantService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final DeviceFingerprintService fingerprintService = mock(DeviceFingerprintService.class);

    @BeforeEach
    void setUp() {
        service = new RegisterMerchantService(repository, merchantApiKeyRepository, apiKeyGenerator,
                PricingPlan.fixed(), credentialRepository, passwordHasher, giftPresetRepository,
                activityRecorder, fingerprintService, clock);
        // Spec 141: fingerprint ausente no comando → serviço não grava (best-effort).
        when(fingerprintService.record(any(), any())).thenReturn(java.util.Optional.empty());
    }

    @Test
    void registerWithExistingEmailThrowsMerchantAlreadyExists() {
        Merchant existing = Merchant.register("Loja A", EMAIL_A, PricingPlan.fixed());
        when(repository.findByEmail(EMAIL_A)).thenReturn(Optional.of(existing));

        RegisterMerchantUseCase.Command command =
                new RegisterMerchantUseCase.Command("Loja B", EMAIL_A, PASSWORD);

        assertThrows(MerchantAlreadyExistsException.class, () -> service.register(command));
        verify(repository, never()).save(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void registerWithNewEmailSavesMerchantAndReturnsApiKey() {
        when(repository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(Merchant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyGenerator.generate())
                .thenReturn(new ApiKeyGenerator.ApiKeyPair(API_KEY, API_KEY_HASH));
        when(passwordHasher.hash(PASSWORD)).thenReturn(PASSWORD_HASH);

        RegisterMerchantUseCase.Command command =
                new RegisterMerchantUseCase.Command("Loja B", EMAIL_B, PASSWORD);

        RegisterMerchantUseCase.Result result = service.register(command);

        assertNotNull(result.merchantId());
        assertEquals(API_KEY, result.apiKey());
        verify(repository).save(any(Merchant.class));
        // O lojista persistido carrega apenas o hash — nunca o texto claro.
        verify(repository).save(argThat(
                merchant -> API_KEY_HASH.equals(merchant.apiKeyHash())));
        // A credencial persiste apenas o hash BCrypt — nunca a senha em claro.
        verify(credentialRepository).save(argThat(
                credential -> PASSWORD_HASH.equals(credential.passwordHash())));
        // Spec 23: catalogo de Gift PIX seedado no cadastro (defaults R$ 5/10/20/50).
        verify(giftPresetRepository, times(4)).save(any(GiftPreset.class));
        // Spec 24: a chave do cadastro tambem entra na tabela gerenciável (escopo total).
        verify(merchantApiKeyRepository).save(any());
    }

    @Test
    void registerWithShortPasswordThrowsIllegalArgument() {
        RegisterMerchantUseCase.Command command =
                new RegisterMerchantUseCase.Command("Loja X", EMAIL_B, "123");

        assertThrows(IllegalArgumentException.class, () -> service.register(command));

        // A validação acontece antes do pré-check de duplicidade e da persistência.
        verify(repository, never()).findByEmail(anyString());
        verify(repository, never()).save(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void registerWithInvalidEmailThrowsIllegalArgument() {
        RegisterMerchantUseCase.Command command =
                new RegisterMerchantUseCase.Command("Loja X", "email-invalido", PASSWORD);

        assertThrows(IllegalArgumentException.class, () -> service.register(command));
        verify(repository, never()).findByEmail(anyString());
        verify(repository, never()).save(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void registerWithMissingNameThrowsIllegalArgument() {
        RegisterMerchantUseCase.Command command =
                new RegisterMerchantUseCase.Command("   ", EMAIL_B, PASSWORD);

        assertThrows(IllegalArgumentException.class, () -> service.register(command));
        verify(repository, never()).findByEmail(anyString());
        verify(repository, never()).save(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void registerNormalizesEmailToLowerCase() {
        when(repository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(Merchant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyGenerator.generate())
                .thenReturn(new ApiKeyGenerator.ApiKeyPair(API_KEY, API_KEY_HASH));
        when(passwordHasher.hash(PASSWORD)).thenReturn(PASSWORD_HASH);

        // Maiúsculas/misturado → normalizado em minúsculas (identificador único).
        RegisterMerchantUseCase.Command command =
                new RegisterMerchantUseCase.Command("Loja B", "Loja.B@Example.COM", PASSWORD);

        service.register(command);

        // O pré-check de duplicidade busca o e-mail já normalizado.
        verify(repository).findByEmail("loja.b@example.com");
        // O lojista persistido carrega o e-mail em minúsculas.
        verify(repository).save(argThat(merchant -> "loja.b@example.com".equals(merchant.email())));
    }

    @Test
    void concurrentRegistrationWithSameEmailMapsIntegrityViolationTo409() {
        // Race: ambos passam o pré-check (e-mail não encontrado), mas o índice
        // único uq_merchants_email rejeita a segunda inserção → 409 canônico (QA).
        when(repository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(apiKeyGenerator.generate())
                .thenReturn(new ApiKeyGenerator.ApiKeyPair(API_KEY, API_KEY_HASH));
        when(repository.save(any(Merchant.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        RegisterMerchantUseCase.Command command =
                new RegisterMerchantUseCase.Command("Loja B", EMAIL_B, PASSWORD);

        assertThrows(MerchantAlreadyExistsException.class, () -> service.register(command));
        verify(credentialRepository, never()).save(any());
        verify(giftPresetRepository, never()).save(any());
        verify(merchantApiKeyRepository, never()).save(any());
    }
}
