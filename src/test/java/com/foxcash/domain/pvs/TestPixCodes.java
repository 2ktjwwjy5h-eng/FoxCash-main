package com.foxcash.domain.pvs;

import java.nio.charset.StandardCharsets;

/**
 * Helpers de teste para construir Pix Copia e Cola estáticos válidos (Spec 120) —
 * compartilhado entre os testes do parser/serviço/controller. O CRC-16 é
 * CRC16-CCITT-FALSE (0x1021, init 0xFFFF) — mesmo algoritmo do {@link PixCodeParser}.
 */
public final class TestPixCodes {

    private static final String PIX_PREFIX = "000201";
    private static final String GUI_PIX = "br.gov.bcb.pix";

    private TestPixCodes() {
    }

    /** Pix estático com a chave informada e, opcionalmente, valor no campo 54. */
    public static String staticEmv(String key, String amount) {
        return withCrc(PIX_PREFIX + tlv("26", tlv("00", GUI_PIX) + tlv("01", key))
                + root() + (amount == null ? "" : tlv("54", amount)));
    }

    /** Pix estático SEM o campo 63 (CRC ausente) — usado nos testes de rejeição. */
    public static String staticEmvNoCrc(String key, String amount) {
        return PIX_PREFIX + tlv("26", tlv("00", GUI_PIX) + tlv("01", key))
                + root() + (amount == null ? "" : tlv("54", amount));
    }

    private static String tlv(String id, String value) {
        return id + String.format("%02d", value.length()) + value;
    }

    private static String root() {
        return tlv("52", "0000") + tlv("53", "986") + tlv("58", "BR")
                + tlv("59", "TESTE") + tlv("60", "BRASILIA");
    }

    private static String withCrc(String body) {
        int crc = crc16Ccitt(body.getBytes(StandardCharsets.US_ASCII));
        return body + tlv("63", String.format("%04X", crc));
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
}
