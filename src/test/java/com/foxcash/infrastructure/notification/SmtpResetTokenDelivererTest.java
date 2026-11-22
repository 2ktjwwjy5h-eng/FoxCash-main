package com.foxcash.infrastructure.notification;

import com.foxcash.infrastructure.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Testes do adapter SMTP de entrega do token de reset (Spec 45): monta o e-mail
 * com o link de redefinição (token one-time) e envia via {@link JavaMailSender};
 * SMTP não configurado → lança (o chamador loga e segue 204).
 */
class SmtpResetTokenDelivererTest {

    private static final String EMAIL = "loja@example.com";
    private static final String TOKEN = "rst_0123456789abcdef0123456789abcdef";
    private static final Instant EXPIRES = Instant.parse("2026-08-05T12:30:00Z");

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        NotificationProperties.ResetToken resetToken = properties.getResetToken();
        resetToken.setDeliverer("smtp");
        resetToken.setBaseUrl("https://portal.foxcash.app");
        NotificationProperties.Email email = resetToken.getEmail();
        email.setHost("smtp.example.com");
        email.setPort(587);
        email.setUsername("user");
        email.setPassword("pass");
        email.setFrom("no-reply@foxcash.app");
    }

    @Test
    void deliversEmailWithResetLink() {
        SmtpResetTokenDeliverer deliverer = new SmtpResetTokenDeliverer(mailSender, properties);

        deliverer.deliver(EMAIL, TOKEN, EXPIRES);

        org.mockito.ArgumentCaptor<SimpleMailMessage> captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertEquals(EMAIL, sent.getTo()[0]);
        assertEquals("no-reply@foxcash.app", sent.getFrom());
        assertTrue(sent.getText().contains("/reset?token=" + TOKEN));
        assertTrue(sent.getText().contains("redefinição"));
        // Token nunca vai no assunto nem em formato exposto indevidamente.
        assertTrue(!sent.getSubject().contains(TOKEN));
    }

    @Test
    void urlEncodesEmailInResetLink() {
        SmtpResetTokenDeliverer deliverer = new SmtpResetTokenDeliverer(mailSender, properties);

        deliverer.deliver("user+tag@example.com", TOKEN, EXPIRES);

        org.mockito.ArgumentCaptor<SimpleMailMessage> captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        // O e-mail no link é URL-encoded (evita quebra do query string).
        assertTrue(captor.getValue().getText().contains("user%2Btag%40example.com"));
    }

    @Test
    void throwsWhenSmtpNotConfigured() {
        NotificationProperties.ResetToken resetToken = properties.getResetToken();
        resetToken.getEmail().setHost("");
        SmtpResetTokenDeliverer deliverer = new SmtpResetTokenDeliverer(mailSender, properties);

        assertThrows(IllegalStateException.class, () -> deliverer.deliver(EMAIL, TOKEN, EXPIRES));
    }

    @Test
    void propagatesMailSenderFailure() {
        doThrow(new IllegalStateException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        SmtpResetTokenDeliverer deliverer = new SmtpResetTokenDeliverer(mailSender, properties);

        // A falha propaga para o chamador (PasswordResetService loga e segue 204).
        assertThrows(IllegalStateException.class, () -> deliverer.deliver(EMAIL, TOKEN, EXPIRES));
    }
}
