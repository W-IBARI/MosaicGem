package com.mosaicgem.plugin.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValueExprTest {

    @Test
    void arithmetic() {
        assertEquals(30.0, ValueExpr.eval("10 * 3", Map.of()), 1e-9);
        assertEquals(7.5, ValueExpr.eval("10 / 4 * 3", Map.of()), 1e-9);
        assertEquals(9.0, ValueExpr.eval("(1 + 2) * 3", Map.of()), 1e-9);
        assertEquals(3.0, ValueExpr.eval("10 % 7", Map.of()), 1e-9);
        assertEquals(8.0, ValueExpr.eval("2 ^ 3", Map.of()), 1e-9);
        assertEquals(-4.0, ValueExpr.eval("-2 ^ 2", Map.of()), 1e-9);
    }

    @Test
    void variables() {
        Map<String, String> values = Map.of("crit", "0.15", "power", "9.53");
        assertEquals(15.0, ValueExpr.eval("crit * 100", values), 1e-9);
        assertEquals(28.59, ValueExpr.eval("power * 3", values), 1e-9);
        assertEquals(9.68, ValueExpr.eval("power + crit", values), 1e-9);
    }

    @Test
    void functions() {
        Map<String, String> values = Map.of("crit", "1.23456");
        assertEquals(1.2, ValueExpr.eval("ROUND(crit, 1)", values), 1e-9);
        assertEquals(1.0, ValueExpr.eval("FLOOR(crit)", values), 1e-9);
        assertEquals(2.0, ValueExpr.eval("CEIL(crit)", values), 1e-9);
        assertEquals(3.0, ValueExpr.eval("MAX(1, 3)", values), 1e-9);
        assertEquals(1.0, ValueExpr.eval("MIN(1, 3)", values), 1e-9);
        assertEquals(123.456, ValueExpr.eval("ABS(crit * -100)", values), 1e-9);
    }

    @Test
    void unknownVariableFails() {
        assertThrows(IllegalArgumentException.class, () -> ValueExpr.eval("missing * 2", Map.of()));
    }
}
