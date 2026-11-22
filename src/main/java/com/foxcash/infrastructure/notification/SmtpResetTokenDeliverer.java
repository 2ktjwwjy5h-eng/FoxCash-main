package com.foxcash.infrastructure.notification;

import com.foxcash.domain.port.out.ResetTokenDeliverer;
import com.foxcash.infrastructure.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Adapter de entrega do token de reset via SMTP (Spec 45) — producao.
 *
 * <p>Selecionado por {@code foxcash.notification.reset-token.deliverer=smtp}.
 * O e-mail contém o link de redefinição ({@code base-url}/reset?token=...) com o
 * token one-time {@code rst_<32 hex>} e instruções; o token **nunca** vai para
 * resposta HTTP nem logs. Credenciais SMTP ausentes **não derrubam o boot**: a
 * falha é lançada na entrega e o chamador (forgot-password) loga e responde 204
 * — o gateway nunca cai por e-mail quebrado (decisão de disponibilidade, Spec 45).
 */
@Component
@ConditionalOnProperty(
        name = "foxcash.notification.reset-token.deliverer",
        havingValue = "smtp")
public class SmtpResetTokenDeliverer implements ResetTokenDeliverer {

    private static final Logger log = LoggerFactory.getLogger(SmtpResetTokenDeliverer.class);

    private final JavaMailSender mailSender;
    private final NotificationProperties.ResetToken config;

    public SmtpResetTokenDeliverer(JavaMailSender mailSender,
                                   NotificationProperties properties) {
        this.mailSender = mailSender;
        this.config = properties.getResetToken();
    }

    @Override
    public void deliver(String email, String resetToken, Instant expiresAt) {
        NotificationProperties.Email smtp = config.getEmail();
        if (smtp.getHost() == null || smtp.getHost().isBlank()) {
            throw new IllegalStateException(
                    "SMTP nao configurado (foxcash.notification.reset-token.email.host)");
        }
        String link = config.getBaseUrl().replaceAll("/+$", "")
                + "/reset?token=" + resetToken + "&email=" + urlEncode(email);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(smtp.getFrom());
        message.setTo(email);
        message.setSubject("FoxCash — redefinição de senha");
        message.setText("Recebemos um pedido de redefinição de senha para sua conta.\n\n"
                + "Acesse o link abaixo (válido até " + expiresAt + "):\n"
                + link + "\n\n"
                + "Se você não solicitou, ignore este e-mail.\n");
        mailSender.send(message);
        log.info("Token de reset enviado por e-mail para {}", email);
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao codificar e-mail no link", e);
        }
    }
}
