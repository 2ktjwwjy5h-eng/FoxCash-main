package com.foxcash.domain.pvs;

import com.foxcash.domain.exception.PixCodeParseException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser do Pix Copia e Cola (EMV BR Code) — Spec 120. Domínio puro: sem I/O,
 * sem estado, testável por tabela.
 *
 * <p>Extrai a <b>chave Pix</b> (TLV 26.01) e o <b>valor</b> (campo 54,
 * opcional) de um brcode <b>estático</b> ({@code 000201...}). Só Pix estático:
 * payload dinâmico (URL {@code br.gov.bcb.pix} em 26.25 ou campos 80/81 no
 * nível raiz) e boleto são rejeitados com mensagem clara. Valida CRC-16
 * (CRC16-CCITT-FALSE, polinômio 0x1021, inicial 0xFFFF, sobre o payload até o
 * campo 63 exclusive) e o formato canônico da chave (mesmas regras da Spec
 * 31/47: 11 dígitos → {@code cpf}, 14 → {@code cnpj}, {@code +55...} →
 * {@code phone}, UUID → {@code evp}).
 *
 * <p>Segurança: o {@code emv} NUNCA é logado — as mensagens de erro usam só a
 * chave mascarada (padrão {@code mask()} da Spec 47).
 */
public final class PixCodeParser {

    private static final String PIX_PREFIX = "000201";
    private static final String GUI_PIX = "br.gov.bcb.pix";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    private PixCodeParser() {
    }

    /**
     * Parseia um Pix Copia e Cola estático. Lança {@link PixCodeParseException}
     * com o código canônico do erro ({@code pvs_invalid_code} /
     * {@code pvs_dynamic_pix_not_supported} / {@code pvs_unsupported_key}).
     */
    public static ParsedPix parse(String emv) {
        if (emv == null || emv.isBlank()) {
            throw new PixCodeParseException("pvs_invalid_code", "Pix Copia e Cola inválido (vazio)");
        }
        if (!emv.startsWith(PIX_PREFIX)) {
            throw new PixCodeParseException("pvs_invalid_code",
                    "Pix Copia e Cola inválido (prefixo 000201 ausente)");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        int crcStart = parseTlv(emv, 0, emv.length(), fields);
        if (crcStart < 0 || !validateCrc(emv, crcStart, fields.get("63"))) {
            throw new PixCodeParseException("pvs_invalid_code", "Pix Copia e Cola inválido (CRC-16)");
        }
        // Payload dinâmico (brcode de conta/boleto): campos 80/81 no nível raiz.
        if (fields.containsKey("80") || fields.containsKey("81")) {
            throw new PixCodeParseException("pvs_dynamic_pix_not_supported",
                    "Pix dinâmico não é suportado no Pagamento via Saldo — use um Pix de chave (estático)");
        }
        String merchantAccount = fields.get("26");
        if (merchantAccount == null) {
            throw new PixCodeParseException("pvs_invalid_code",
                    "Pix Copia e Cola inválido (campo 26 ausente)");
        }
        Map<String, String> sub = new LinkedHashMap<>();
        parseTlv(merchantAccount, 0, merchantAccount.length(), sub);
        if (!GUI_PIX.equals(sub.get("00"))) {
            throw new PixCodeParseException("pvs_invalid_code",
                    "Pix Copia e Cola inválido (GUI não é br.gov.bcb.pix)");
        }
        // Pix dinâmico: a chave está numa URL (26.25) em vez de uma chave (26.01).
        if (sub.containsKey("25")) {
            throw new PixCodeParseException("pvs_dynamic_pix_not_supported",
                    "Pix dinâmico não é suportado no Pagamento via Saldo — use um Pix de chave (estático)");
        }
        String key = sub.get("01");
        if (key == null || key.isBlank()) {
            throw new PixCodeParseException("pvs_invalid_code",
                    "Pix Copia e Cola inválido (chave Pix ausente no campo 26.01)");
        }
        return new ParsedPix(key, inferKeyType(key), parseAmount(fields.get("54")));
    }

    /**
     * Varredura TLV (id 2 dígitos + len 2 dígitos decimais + value) preenchendo
     * {@code fields} e devolvendo o índice onde o campo {@code 63} (CRC) começa
     * (ou -1 se ausente). TLV malformado/truncado → {@code pvs_invalid_code}.
     */
    private static int parseTlv(String data, int from, int to, Map<String, String> fields) {
        int i = from;
        int crcStart = -1;
        while (i + 4 <= to) {
            String id = data.substring(i, i + 2);
            int len;
            try {
                len = Integer.parseInt(data.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                throw new PixCodeParseException("pvs_invalid_code",
                        "Pix Copia e Cola inválido (TLV malformado)");
            }
            int valueEnd = i + 4 + len;
            if (valueEnd > to) {
                throw new PixCodeParseException("pvs_invalid_code",
                        "Pix Copia e Cola inválido (TLV truncado)");
            }
            String value = data.substring(i + 4, valueEnd);
            fields.put(id, value);
            if ("63".equals(id)) {
                crcStart = i;
            }
            i = valueEnd;
        }
        if (i < to) {
            throw new PixCodeParseException("pvs_invalid_code",
                    "Pix Copia e Cola inválido (TLV malformado)");
        }
        return crcStart;
    }

    /** CRC16-CCITT-FALSE (polinômio 0x1021, inicial 0xFFFF) sobre o payload até o campo 63 exclusive. */
    private static boolean validateCrc(String emv, int crcStart, String expected) {
        if (expected == null || !expected.matches("[0-9a-fA-F]{4}")) {
            return false;
        }
        String toVerify = emv.substring(0, crcStart);
        int computed = crc16Ccitt(toVerify.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return String.format("%04X", computed).equalsIgnoreCase(expected);
    }

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

    /** Valor do campo 54 (opcional; {@code null} quando ausente). Aceita vírgula decimal. */
    private static BigDecimal parseAmount(String rawAmount) {
        if (rawAmount == null || rawAmount.isBlank()) {
            return null;
        }
        final BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new PixCodeParseException("pvs_invalid_code",
                    "Pix Copia e Cola inválido (valor do campo 54)");
        }
        if (amount.stripTrailingZeros().scale() > 2 || amount.signum() <= 0) {
            throw new PixCodeParseException("pvs_invalid_code",
                    "Pix Copia e Cola inválido (valor do campo 54)");
        }
        return amount;
    }

    /**
     * Tipo canônico da chave (mesmas regras da Spec 31/47): CPF 11 dígitos,
     * CNPJ 14 dígitos, telefone {@code +55...}, chave aleatória (UUID). A chave
     * pode vir com pontuação (ex.: {@code 529.982.247-25}) — normaliza dígitos.
     * Formato desconhecido → {@code pvs_unsupported_key}.
     */
    private static String inferKeyType(String key) {
        String digits = NON_DIGITS.matcher(key).replaceAll("");
        if (digits.length() == 11) {
            return "cpf";
        }
        if (digits.length() == 14) {
            return "cnpj";
        }
        if (key.startsWith("+")) {
            return "phone";
        }
        if (UUID_PATTERN.matcher(key).matches()) {
            return "evp";
        }
        throw new PixCodeParseException("pvs_unsupported_key",
                "Chave Pix em formato não suportado: " + mask(key));
    }

    /** Máscara para mensagens de erro — nunca a chave em claro. */
    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "***";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
