package com.foxcash.infrastructure.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Criptografia em repouso (AES-256/GCM) para dados sensiveis (CPF/CNPJ).
 *
 * <p>Formato armazenado: {@code v1:<base64(iv(12) + ciphertext + tag(16))>}.
 * IV aleatorio a cada escrita => ciphertext nao-deterministico (mais seguro).
 * A chave e derivada via SHA-256 de {@code foxcash.crypto.secret}
 * (FOXCASH_CRYPTO_SECRET em producao).
 *
 * <p>Busca por igualdade no ciphertext NAO e possivel (IV aleatorio); para
 * lookup por documento usa-se a coluna auxiliar {@code document_hash}
 * (ver {@link DocumentHasher}).
 */
@Component
@Converter
public class CryptoConverter implements AttributeConverter<String, String> {

    public static final String VERSION = "v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] key;
    private final ThreadLocal<Cipher> encryptCipher = ThreadLocal.withInitial(this::newCipher);
    private final ThreadLocal<Cipher> decryptCipher = ThreadLocal.withInitial(this::newCipher);

    public CryptoConverter(CryptoProperties properties) {
        if (properties == null || properties.getSecret() == null || properties.getSecret().isBlank()) {
            throw new IllegalStateException("foxcash.crypto.secret e obrigatorio (FOXCASH_CRYPTO_SECRET em producao)");
        }
        if (properties.getSecret().contains("${")) {
            // O Binder do Spring deixa placeholder nao-resolvido como string literal
            // (ex.: "${FOXCASH_CRYPTO_SECRET}") em vez de falhar. Rejeitar explicitamente:
            // em producao a chave PRECISA vir da env var (fail-fast, sem chave publica).
            throw new IllegalStateException(
                    "foxcash.crypto.secret nao foi resolvido (FOXCASH_CRYPTO_SECRET ausente): " + properties.getSecret());
        }
        this.key = sha256(properties.getSecret());
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = encryptCipher.get();
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return VERSION + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criptografar dado sensivel", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!dbData.startsWith(VERSION)) {
            throw new IllegalStateException("Formato de dado criptografado invalido (esperado prefixo '" + VERSION + "')");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(dbData.substring(VERSION.length()));
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = decryptCipher.get();
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao descriptografar dado sensivel", e);
        }
    }

    private Cipher newCipher() {
        try {
            return Cipher.getInstance(ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("Nao foi possivel criar Cipher AES/GCM", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Nao foi possivel derivar chave AES", e);
        }
    }
}
