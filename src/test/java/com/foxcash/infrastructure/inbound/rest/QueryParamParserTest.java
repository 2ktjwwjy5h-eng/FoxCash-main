package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.billing.BillingPeriod;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link QueryParamParser} (Specs 14/15): parsing de parâmetros de
 * consulta REST — valores inválidos viram {@link IllegalArgumentException}
 * (→ 400 {@code invalid_argument}, mesmo contrato do AdminController).
 */
class QueryParamParserTest {

    @Test
    void clampPageNeverNegative() {
        assertThat(QueryParamParser.clampPage(-5)).isZero();
        assertThat(QueryParamParser.clampPage(0)).isZero();
        assertThat(QueryParamParser.clampPage(3)).isEqualTo(3);
    }

    @Test
    void clampSizeBoundsToOneToMax() {
        assertThat(QueryParamParser.clampSize(0)).isEqualTo(1);
        assertThat(QueryParamParser.clampSize(-10)).isEqualTo(1);
        assertThat(QueryParamParser.clampSize(50)).isEqualTo(50);
        assertThat(QueryParamParser.clampSize(500)).isEqualTo(100);
    }

    @Test
    void parseEnumNormalizesCaseAndTrim() {
        assertThat(QueryParamParser.parseEnum(DayOfWeek.class, " monday ", "day"))
                .isEqualTo(DayOfWeek.MONDAY);
        assertThat(QueryParamParser.parseEnum(DayOfWeek.class, "FRIDAY", "day"))
                .isEqualTo(DayOfWeek.FRIDAY);
    }

    @Test
    void parseEnumNullOrBlankReturnsNull() {
        assertThat(QueryParamParser.parseEnum(DayOfWeek.class, null, "day")).isNull();
        assertThat(QueryParamParser.parseEnum(DayOfWeek.class, "   ", "day")).isNull();
    }

    @Test
    void parseEnumInvalidThrowsWithParamName() {
        assertThatThrownBy(() -> QueryParamParser.parseEnum(DayOfWeek.class, "funday", "day"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'day'");
    }

    @Test
    void parseInstantParsesIso8601() {
        Instant expected = Instant.parse("2026-08-05T10:00:00Z");

        assertThat(QueryParamParser.parseInstant("2026-08-05T10:00:00Z", "from")).isEqualTo(expected);
        assertThat(QueryParamParser.parseInstant(null, "from")).isNull();
        assertThat(QueryParamParser.parseInstant("   ", "from")).isNull();
    }

    @Test
    void parseInstantInvalidThrows() {
        assertThatThrownBy(() -> QueryParamParser.parseInstant("ontem", "from"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'from'");
    }

    @Test
    void parsePeriodParsesYearMonth() {
        assertThat(QueryParamParser.parsePeriod("2026-08"))
                .isEqualTo(BillingPeriod.of(YearMonth.of(2026, 8)));
        assertThat(QueryParamParser.parsePeriod(null)).isNull();
    }

    @Test
    void parsePeriodInvalidThrows() {
        assertThatThrownBy(() -> QueryParamParser.parsePeriod("2026-13"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'month'");
        assertThatThrownBy(() -> QueryParamParser.parsePeriod("agosto"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'month'");
    }

    @Test
    void parseUuidParsesValid() {
        UUID uuid = UUID.randomUUID();

        assertThat(QueryParamParser.parseUuid(uuid.toString(), "id")).isEqualTo(uuid);
        assertThat(QueryParamParser.parseUuid(null, "id")).isNull();
    }

    @Test
    void parseUuidInvalidThrows() {
        assertThatThrownBy(() -> QueryParamParser.parseUuid("nao-e-uuid", "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'id'");
    }

    // ---- Spec 71 (F1): isValidIp — mesmo critério do Operator Bot (Spec 67) ----

    @Test
    void isValidIpAcceptsIpv4WithOctetsInRange() {
        assertThat(QueryParamParser.isValidIp("192.168.1.10")).isTrue();
        assertThat(QueryParamParser.isValidIp("0.0.0.0")).isTrue();
        assertThat(QueryParamParser.isValidIp("255.255.255.255")).isTrue();
    }

    @Test
    void isValidIpRejectsOutOfRangeOctets() {
        assertThat(QueryParamParser.isValidIp("256.1.1.1")).isFalse();
        assertThat(QueryParamParser.isValidIp("1.2.3.999")).isFalse();
    }

    @Test
    void isValidIpAcceptsIpv6() {
        assertThat(QueryParamParser.isValidIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334")).isTrue();
        assertThat(QueryParamParser.isValidIp("::1")).isTrue();
    }

    @Test
    void isValidIpRejectsGarbageAndBlank() {
        assertThat(QueryParamParser.isValidIp(null)).isFalse();
        assertThat(QueryParamParser.isValidIp("   ")).isFalse();
        assertThat(QueryParamParser.isValidIp("nao-e-ip")).isFalse();
        assertThat(QueryParamParser.isValidIp("192.168.1")).isFalse();
        assertThat(QueryParamParser.isValidIp("abc")).isFalse();
    }
}
