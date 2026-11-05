package seatmap.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import seatmap.error.ValidationException;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({"45,4500,45.00", "45.5,4550,45.50", "45.05,4505,45.05", "0.99,99,0.99", "0,0,0.00"})
    void parsesAndFormats(String text, long cents, String formatted) {
        Money money = Money.parse(text);
        assertEquals(cents, money.cents());
        assertEquals(formatted, money.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".5", "5.", "-1", "1.234", "1,50", "abc", "1e3", " 5"})
    void rejectsMalformedAmounts(String text) {
        assertThrows(ValidationException.class, () -> Money.parse(text));
    }

    @Test
    void arithmetic() {
        Money price = Money.parse("19.99");
        assertEquals(Money.parse("59.97"), price.times(3));
        assertEquals(Money.parse("20.99"), price.plus(Money.parse("1")));
        assertEquals(Money.ZERO, price.minusFloored(Money.parse("50")));
        assertEquals(Money.parse("9.99"), price.minusFloored(Money.parse("10")));
        assertEquals(price, price.min(Money.parse("20")));
        assertTrue(Money.ZERO.isZero());
        assertTrue(price.compareTo(Money.ZERO) > 0);
        assertThrows(ValidationException.class, () -> Money.ofCents(-1));
        assertThrows(ValidationException.class, () -> price.times(-1));
    }

    @Test
    void percentRoundsHalfUp() {
        assertEquals(Money.parse("3.00"), Money.parse("19.99").percent(Percent.of(15)));  // 2.9985
        assertEquals(Money.ofCents(1), Money.ofCents(5).percent(Percent.of(10)));         // 0.5 -> 1
        assertEquals(Money.ofCents(0), Money.ofCents(4).percent(Percent.of(10)));         // 0.4 -> 0
        assertEquals(Money.parse("12.50"), Money.parse("100").percent(Percent.parse("12.5%")));
    }

    @Test
    void percentParsingAndFormatting() {
        assertEquals(1500, Percent.parse("15%").basisPoints());
        assertEquals(1250, Percent.parse("12.5%").basisPoints());
        assertEquals("12.5%", Percent.parse("12.50%").toString());
        assertEquals("0.05%", Percent.parse("0.05%").toString());
        assertEquals("100%", Percent.of(100).toString());
        assertThrows(ValidationException.class, () -> Percent.parse("15"));
        assertThrows(ValidationException.class, () -> Percent.parse("101%"));
        assertThrows(ValidationException.class, () -> Percent.parse("-5%"));
        assertThrows(ValidationException.class, () -> Percent.parse("%"));
    }
}
