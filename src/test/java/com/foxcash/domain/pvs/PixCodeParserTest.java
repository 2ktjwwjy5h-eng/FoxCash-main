package com.foxcash.domain.pvs;

import com.foxcash.domain.exception.PixCodeParseException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link PixCodeParser} (Spec 120) — Pix Copia e Cola estático:
 * chave CPF/CNPJ/phone/EVP (com e sem valor no campo 54), CRC válido/inválido,
 * Pix dinâmico (26.25 / 80), TLV malformado e chave desconhecida.
 */
class PixCodeParserTest {

    /**
     * Vetor canônico de um Pix estático (chave CPF 52998224725, valor 10.00) —
     * estrutura padrão do BR Code; o CRC-16 (B756) foi cross-verificado com uma
     * implementação independente (CRC16-CCITT-FALSE: check-value 0x29B1 para
     * "123456789").
     */
    private static final String BACEN_VECTOR =
            "00020126330014br.gov.bcb.pix011152998224725520400005303986540510.005802BR5906FULANO"
                    + "6008BRASILIA62070503***6304B756";

    @Test
    void parsesCanonicalVector() {
        ParsedPix parsed = PixCodeParser.parse(BACEN_VECTOR);

        assertThat(parsed.key()).isEqualTo("52998224725");
        assertThat(parsed.keyType()).isEqualTo("cpf");
        assertThat(parsed.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void parsesStaticCpfKeyWithoutAmount() {
        ParsedPix parsed = PixCodeParser.parse(staticEmv("52998224725", null));

        assertThat(parsed.key()).isEqualTo("52998224725");
        assertThat(parsed.keyType()).isEqualTo("cpf");
        assertThat(parsed.amount()).isNull();
    }

    @Test
    void parsesStaticCnpjKeyWithAmount() {
        ParsedPix parsed = PixCodeParser.parse(staticEmv("12.345.678/0001-95", "150.00"));

        assertThat(parsed.key()).isEqualTo("12.345.678/0001-95");
        assertThat(parsed.keyType()).isEqualTo("cnpj");
        assertThat(parsed.amount()).isEqualByComparingTo("150.00");
    }

    @Test
    void parsesPhoneKey() {
        ParsedPix parsed = PixCodeParser.parse(staticEmv("+5511999999999", null));

        assertThat(parsed.keyType()).isEqualTo("phone");
    }

    @Test
    void parsesEvpKey() {
        ParsedPix parsed = PixCodeParser.parse(staticEmv(UUID.randomUUID().toString(), "0.50"));

        assertThat(parsed.keyType()).isEqualTo("evp");
        assertThat(parsed.amount()).isEqualByComparingTo("0.50");
    }

    @Test
    void parsesAmountWithComma() {
        ParsedPix parsed = PixCodeParser.parse(staticEmv("52998224725", "10,50"));

        assertThat(parsed.amount()).isEqualByComparingTo("10.50");
    }

    @Test
    void rejectsWrongPrefix() {
        assertThatThrownBy(() -> PixCodeParser.parse("000202" + staticEmv("52998224725", null).substring(6)))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> PixCodeParser.parse(null))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
        assertThatThrownBy(() -> PixCodeParser.parse("  "))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
    }

    @Test
    void rejectsInvalidCrc() {
        String bad = staticEmv("52998224725", null);
        // Corrompe o último dígito do CRC (o brcode continua com o mesmo shape).
        String corrupted = bad.substring(0, bad.length() - 1) + "0";

        assertThatThrownBy(() -> PixCodeParser.parse(corrupted))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
    }

    @Test
    void rejectsMissingCrc() {
        assertThatThrownBy(() -> PixCodeParser.parse(staticEmvNoCrc("52998224725", null)))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
    }

    @Test
    void rejectsDynamicPixByUrlInMerchantAccount() {
        // 26.25 = URL br.gov.bcb.pix (chave dinâmica).
        String mi = tlv("26", tlv("00", "br.gov.bcb.pix")
                + tlv("01", "123e4567-e12b-12d1-a456-426655440000")
                + tlv("25", "pix.example.com/123"));
        String emv = withCrc(PIX_PREFIX + mi + root());

        assertThatThrownBy(() -> PixCodeParser.parse(emv))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code())
                        .isEqualTo("pvs_dynamic_pix_not_supported"));
    }

    @Test
    void rejectsDynamicPixByUrlOnly() {
        // 26.25 sem chave estática (26.01) → dinâmico.
        String mi = tlv("26", tlv("00", "br.gov.bcb.pix") + tlv("25", "pix.example.com/123"));
        String emv = withCrc(PIX_PREFIX + mi + root());

        assertThatThrownBy(() -> PixCodeParser.parse(emv))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code())
                        .isEqualTo("pvs_dynamic_pix_not_supported"));
    }

    @Test
    void rejectsDynamicPixByRootField80() {
        String mi = tlv("26", tlv("00", "br.gov.bcb.pix") + tlv("01", "52998224725"));
        String emv = withCrc(PIX_PREFIX + mi + root() + tlv("80", "br.gov.bcb.pix"));

        assertThatThrownBy(() -> PixCodeParser.parse(emv))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code())
                        .isEqualTo("pvs_dynamic_pix_not_supported"));
    }

    @Test
    void rejectsMalformedTlv() {
        assertThatThrownBy(() -> PixCodeParser.parse("00020126580"))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
    }

    @Test
    void rejectsUnsupportedKeyFormat() {
        String emv = withCrc(PIX_PREFIX + tlv("26", tlv("00", "br.gov.bcb.pix") + tlv("01", "not-a-key")) + root());

        assertThatThrownBy(() -> PixCodeParser.parse(emv))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_unsupported_key"));
    }

    @Test
    void rejectsMissingKeyInMerchantAccount() {
        String mi = tlv("26", tlv("00", "br.gov.bcb.pix"));
        String emv = withCrc(PIX_PREFIX + mi + root());

        assertThatThrownBy(() -> PixCodeParser.parse(emv))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
    }

    @Test
    void rejectsNonPixGui() {
        String mi = tlv("26", tlv("00", "br.com.outro") + tlv("01", "52998224725"));
        String emv = withCrc(PIX_PREFIX + mi + root());

        assertThatThrownBy(() -> PixCodeParser.parse(emv))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimals() {
        String emv = withCrc(PIX_PREFIX + tlv("26", tlv("00", "br.gov.bcb.pix") + tlv("01", "52998224725"))
                + root() + tlv("54", "10.009"));

        assertThatThrownBy(() -> PixCodeParser.parse(emv))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code()).isEqualTo("pvs_invalid_code"));
    }

    // ---------- helpers ----------

    private static final String PIX_PREFIX = "000201";
    private static final String GUI_PIX = "br.gov.bcb.pix";

    private static String tlv(String id, String value) {
        return id + String.format("%02d", value.length()) + value;
    }

    /** Campos raiz estáticos mínimos (sem 26): categoria, moeda, país. */
    private static String root() {
        return tlv("52", "0000") + tlv("53", "986") + tlv("58", "BR")
                + tlv("59", "TESTE") + tlv("60", "BRASILIA");
    }

    private static String staticEmv(String key, String amount) {
        String body = PIX_PREFIX
                + tlv("26", tlv("00", GUI_PIX) + tlv("01", key))
                + root()
                + (amount == null ? "" : tlv("54", amount));
        return withCrc(body);
    }

    private static String staticEmvNoCrc(String key, String amount) {
        return PIX_PREFIX
                + tlv("26", tlv("00", GUI_PIX) + tlv("01", key))
                + root()
                + (amount == null ? "" : tlv("54", amount));
    }

    private static String withCrc(String body) {
        int crc = crc16Ccitt(body.getBytes(StandardCharsets.US_ASCII));
        return body + tlv("63", String.format("%04X", crc));
    }

    /** CRC16-CCITT-FALSE — mesmo algoritmo do parser (spec Bacen: 0x1021, init 0xFFFF). */
    private static int crc16Ccitt(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }
}
