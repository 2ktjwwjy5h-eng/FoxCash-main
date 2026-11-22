package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidResetTokenException;
import com.foxcash.domain.exception.ResetTokenAlreadyOpenException;
import com.foxcash.domain.model.auth.PasswordResetToken;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.port.in.PasswordResetUseCase;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantSessionRepository;
import com.foxcash.domain.port.out.PasswordResetTokenRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.port.out.ResetTokenDeliverer;
import com.foxcash.domain.port.out.ResetTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da recuperação de senha do lojista (Spec 40): forgot emite token
 * one-time (hash SHA-256 persistido, um ativo por lojista, expiração); reset
 * consome o token, troca o hash BCrypt da credencial e revoga as sessões.
 */
class PasswordResetServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String EMAIL = "loja@example.com";
    private static final String EMAIL_MIXED = "Loja@Example.COM";
    // Política de senha (auditoria 2026-08-05/B-07): 8-72 + minúsculas/maiúsculas/números.
    private static final String NEW_PASSWORD = "Nova-senha-segura-123";
    private static final String NEW_PASSWORD_HASH = "$2a$10$bcryptnovo";
    private static final String TOKEN = "rst_0123456789abcdef0123456789abcdef";
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MerchantCredentialRepository credentialRepository = mock(MerchantCredentialRepository.class);
    private final MerchantSessionRepository sessionRepository = mock(MerchantSessionRepository.class);
    private final PasswordResetTokenRepository resetTokenRepository = mock(PasswordResetTokenRepository.class);
    private final ResetTokenGenerator resetTokenGenerator = mock(ResetTokenGenerator.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final ResetTokenDeliverer resetTokenDeliverer = mock(ResetTokenDeliverer.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PasswordResetService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(merchantRepository, credentialRepository, sessionRepository,
                resetTokenRepository, resetTokenGenerator, passwordHasher, activityRecorder,
                resetTokenDeliverer, clock, TTL);
    }

    private Merchant merchant() {
        return Merchant.restore(MERCHANT_ID, "Loja A", EMAIL, null,
                "hash", com.foxcash.domain.model.merchant.MerchantStatus.ACTIVE,
                com.foxcash.domain.model.pricing.PricingPlan.fixed(),
                com.foxcash.domain.model.Money.of("0.00"), false, NOW);
    }

    private MerchantCredential credential() {
        return MerchantCredential.restore(UUID.randomUUID(), MERCHANT_ID,
                "$2a$10$bcryptantigo", NOW, NOW, null);
    }

    private PasswordResetToken validToken() {
        return PasswordResetToken.restore(UUID.randomUUID(), MERCHANT_ID, TOKEN_HASH,
                NOW, NOW.plus(TTL), null);
    }

    // ---------- forgot ----------

    @Test
    void forgotWithExistingEmailIssuesToken() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant()));
        when(resetTokenGenerator.generate())
                .thenReturn(new ResetTokenGenerator.TokenPair(TOKEN, TOKEN_HASH));
        when(resetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PasswordResetUseCase.ForgotResult result =
                service.forgot(new PasswordResetUseCase.ForgotCommand(EMAIL));

        assertTrue(result.emailFound());
        assertEquals(TOKEN, result.resetToken());
        // Um token ativo por lojista: o aberto anterior é apagado.
        verify(resetTokenRepository).deleteOpenByMerchantId(MERCHANT_ID);
        // Apenas o hash é persistido — nunca o token em claro.
        verify(resetTokenRepository).save(argThat(
                t -> TOKEN_HASH.equals(t.tokenHash()) && t.merchantId().equals(MERCHANT_ID)
                        && t.expiresAt().equals(NOW.plus(TTL))));
    }

    @Test
    void forgotWithUnknownEmailReturnsEmptyWithoutPersisting() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        PasswordResetUseCase.ForgotResult result =
                service.forgot(new PasswordResetUseCase.ForgotCommand(EMAIL));

        assertFalse(result.emailFound());
        assertEquals(null, result.resetToken());
        verify(resetTokenRepository, never()).save(any());
        verify(resetTokenRepository, never()).deleteOpenByMerchantId(any());
    }

    @Test
    void forgotNormalizesEmailToLowerCase() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant()));
        when(resetTokenGenerator.generate())
                .thenReturn(new ResetTokenGenerator.TokenPair(TOKEN, TOKEN_HASH));
        when(resetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.forgot(new PasswordResetUseCase.ForgotCommand(EMAIL_MIXED));

        verify(merchantRepository).findByEmail(EMAIL);
    }

    @Test
    void forgotWithInvalidEmailThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                service.forgot(new PasswordResetUseCase.ForgotCommand("email-invalido")));
        verify(merchantRepository, never()).findByEmail(anyString());
    }

    @Test
    void forgotConcurrentRaceMapsIntegrityViolationTo409() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant()));
        when(resetTokenGenerator.generate())
                .thenReturn(new ResetTokenGenerator.TokenPair(TOKEN, TOKEN_HASH));
        when(resetTokenRepository.save(any(PasswordResetToken.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThrows(ResetTokenAlreadyOpenException.class, () ->
                service.forgot(new PasswordResetUseCase.ForgotCommand(EMAIL)));
    }

    // ---------- reset ----------

    @Test
    void resetWithValidTokenChangesPasswordMarksUsedAndRevokesSessions() {
        when(resetTokenRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(validToken()));
        when(resetTokenGenerator.hash(TOKEN)).thenReturn(TOKEN_HASH);
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(passwordHasher.hash(NEW_PASSWORD)).thenReturn(NEW_PASSWORD_HASH);
        when(resetTokenRepository.consumeIfOpen(TOKEN_HASH, NOW)).thenReturn(true);

        service.reset(new PasswordResetUseCase.ResetCommand(TOKEN, NEW_PASSWORD));

        // Senha trocada (BCrypt novo) na credencial — nunca em claro.
        verify(credentialRepository).save(argThat(
                c -> NEW_PASSWORD_HASH.equals(c.passwordHash()) && c.updatedAt().equals(NOW)));
        // SEC-10: token consumido de forma ATÔMICA (consumeIfOpen) — sem save
        // redundante da entidade (o UPDATE condicional já marcou o uso).
        verify(resetTokenRepository).consumeIfOpen(TOKEN_HASH, NOW);
        verify(resetTokenRepository, never()).save(any());
        // Sessões ativas revogadas — mesma transação.
        verify(sessionRepository).revokeAllByMerchantId(MERCHANT_ID, NOW);
    }

    @Test
    void resetConcurrentConsumeFailsSecondWithUniformError() {
        // SEC-10 (revisão de segurança 2026-08-11): dois reset com o MESMO token
        // — apenas o primeiro consome; o segundo (consumeIfOpen=false) responde
        // o mesmo 400 (single-use de verdade, sem TOCTOU).
        when(resetTokenRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(validToken()));
        when(resetTokenGenerator.hash(TOKEN)).thenReturn(TOKEN_HASH);
        when(resetTokenRepository.consumeIfOpen(TOKEN_HASH, NOW)).thenReturn(false);

        assertThrows(InvalidResetTokenException.class, () ->
                service.reset(new PasswordResetUseCase.ResetCommand(TOKEN, NEW_PASSWORD)));
        verify(credentialRepository, never()).save(any());
        verify(sessionRepository, never()).revokeAllByMerchantId(any(), any());
    }

    @Test
    void resetWithUnknownTokenThrows() {
        when(resetTokenGenerator.hash(TOKEN)).thenReturn(TOKEN_HASH);
        when(resetTokenRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThrows(InvalidResetTokenException.class, () ->
                service.reset(new PasswordResetUseCase.ResetCommand(TOKEN, NEW_PASSWORD)));
        verify(credentialRepository, never()).save(any());
        verify(sessionRepository, never()).revokeAllByMerchantId(any(), any());
    }

    @Test
    void resetWithExpiredTokenThrows() {
        PasswordResetToken expired = PasswordResetToken.restore(
                UUID.randomUUID(), MERCHANT_ID, TOKEN_HASH, NOW.minusSeconds(3600), NOW, null);
        when(resetTokenGenerator.hash(TOKEN)).thenReturn(TOKEN_HASH);
        when(resetTokenRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(expired));

        assertThrows(InvalidResetTokenException.class, () ->
                service.reset(new PasswordResetUseCase.ResetCommand(TOKEN, NEW_PASSWORD)));
        verify(credentialRepository, never()).save(any());
        // Mutation (BooleanTrueReturnVals L58): token expirado NÃO é válido.
        assertFalse(expired.isValid(NOW));
        // Mutations (NullReturnVals L67/L79): id/createdAt expostos pelos getters
        // do token restaurado (mutante trocaria por null).
        assertNotNull(expired.id());
        assertEquals(NOW.minusSeconds(3600), expired.createdAt());
    }

    @Test
    void resetWithAlreadyUsedTokenThrows() {
        PasswordResetToken used = PasswordResetToken.restore(
                UUID.randomUUID(), MERCHANT_ID, TOKEN_HASH, NOW, NOW.plus(TTL), NOW);
        when(resetTokenGenerator.hash(TOKEN)).thenReturn(TOKEN_HASH);
        when(resetTokenRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(used));

        assertThrows(InvalidResetTokenException.class, () ->
                service.reset(new PasswordResetUseCase.ResetCommand(TOKEN, NEW_PASSWORD)));
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void resetWithShortPasswordThrowsBeforeAnyLookup() {
        assertThrows(IllegalArgumentException.class, () ->
                service.reset(new PasswordResetUseCase.ResetCommand(TOKEN, "123")));
        verify(resetTokenRepository, never()).findByTokenHash(anyString());
    }

    // ---------- Spec 45 — entrega do token ----------

    @Test
    void forgotWithExistingEmailDeliversToken() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant()));
        when(resetTokenGenerator.generate())
                .thenReturn(new ResetTokenGenerator.TokenPair(TOKEN, TOKEN_HASH));
        when(resetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.forgot(new PasswordResetUseCase.ForgotCommand(EMAIL));

        // Spec 45: token entregue ao e-mail da conta (SMTP em prod / log em dev).
        verify(resetTokenDeliverer).deliver(EMAIL, TOKEN, NOW.plus(TTL));
    }

    @Test
    void forgotWithUnknownEmailDeliversDummyToken() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        service.forgot(new PasswordResetUseCase.ForgotCommand(EMAIL));

        // Spec 45 (anti-enumeração por timing): o caminho inexistente também
        // executa uma entrega dummy — mas NÃO persiste token nem apaga nada.
        verify(resetTokenDeliverer).deliver(
                argThat(email -> email.contains("nao-existe")), anyString(), any(Instant.class));
        verify(resetTokenRepository, never()).save(any());
        verify(resetTokenRepository, never()).deleteOpenByMerchantId(any());
    }

    @Test
    void forgotWithDelivererFailureStillReturnsToken() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant()));
        when(resetTokenGenerator.generate())
                .thenReturn(new ResetTokenGenerator.TokenPair(TOKEN, TOKEN_HASH));
        when(resetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // SMTP indisponível → lança; o forgot não relança (resposta segue 204).
        doThrow(new IllegalStateException("smtp down"))
                .when(resetTokenDeliverer).deliver(anyString(), anyString(), any(Instant.class));

        PasswordResetUseCase.ForgotResult result =
                service.forgot(new PasswordResetUseCase.ForgotCommand(EMAIL));

        // O token continua válido e o fluxo não quebra — o lojista pode re-tentar.
        assertTrue(result.emailFound());
        assertEquals(TOKEN, result.resetToken());
    }
}
