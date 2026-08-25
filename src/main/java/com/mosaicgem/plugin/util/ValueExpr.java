package com.mosaicgem.plugin.util;

import java.util.Map;

/**
 * 轻量数值表达式求值器（无外部依赖）。
 *
 * <p>语法：数字、变量（取自宝石 random 值）、四则运算、括号、幂（^）、取模（%）、
 * 一元负号，以及函数 ROUND / FLOOR / CEIL / ABS / MIN / MAX / SQRT / LOG。
 * 例：{@code crit * 100}、{@code ROUND(power * 2, 1)}、{@code (power + crit) / 2}。
 */
public final class ValueExpr {

    private final String source;
    private final Map<String, String> variables;
    private int pos;

    private ValueExpr(String source, Map<String, String> variables) {
        this.source = source == null ? "" : source;
        this.variables = variables;
    }

    public static double eval(String expression, Map<String, String> variables) {
        ValueExpr parser = new ValueExpr(expression, variables);
        double value = parser.parseExpression();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected token at " + parser.pos + ": " + expression);
        }
        return value;
    }

    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            skipWhitespace();
            if (peek('+')) {
                pos++;
                value += parseTerm();
            } else if (peek('-')) {
                pos++;
                value -= parseTerm();
            } else {
                return value;
            }
        }
    }

    private double parseTerm() {
        double value = parseFactor();
        while (true) {
            skipWhitespace();
            if (peek('*')) {
                pos++;
                value *= parseFactor();
            } else if (peek('/')) {
                pos++;
                value /= parseFactor();
            } else if (peek('%')) {
                pos++;
                value %= parseFactor();
            } else {
                return value;
            }
        }
    }

    private double parseFactor() {
        skipWhitespace();
        if (peek('-')) {
            pos++;
            return -parseFactor();
        }
        if (peek('+')) {
            pos++;
            return parseFactor();
        }
        return parsePower();
    }

    private double parsePower() {
        double base = parseAtom();
        skipWhitespace();
        if (peek('^')) {
            pos++;
            return Math.pow(base, parseFactor());
        }
        return base;
    }

    private double parseAtom() {
        skipWhitespace();
        if (peek('(')) {
            pos++;
            double value = parseExpression();
            skipWhitespace();
            if (!peek(')')) {
                throw new IllegalArgumentException("Missing ')' at " + pos);
            }
            pos++;
            return value;
        }
        if (peekDigit() || (pos < source.length() && source.charAt(pos) == '.')) {
            return parseNumber();
        }
        String name = parseIdent();
        skipWhitespace();
        if (peek('(')) {
            pos++;
            return parseFunction(name);
        }
        return variable(name);
    }

    private String parseIdent() {
        int start = pos;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw new IllegalArgumentException("Expected identifier at " + pos);
        }
        return source.substring(start, pos);
    }

    private double parseNumber() {
        int start = pos;
        boolean dot = false;
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isDigit(c)) {
                pos++;
            } else if (c == '.' && !dot) {
                dot = true;
                pos++;
            } else {
                break;
            }
        }
        return Double.parseDouble(source.substring(start, pos));
    }

    private double variable(String name) {
        if (variables != null) {
            String text = variables.get(name);
            if (text != null) {
                try {
                    return Double.parseDouble(text.trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        throw new IllegalArgumentException("Unknown variable: " + name);
    }

    private double parseFunction(String name) {
        double[] args = new double[3];
        int count = 0;
        skipWhitespace();
        if (!peek(')')) {
            while (true) {
                if (count >= args.length) {
                    throw new IllegalArgumentException("Too many arguments for '" + name + "'");
                }
                args[count++] = parseExpression();
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                if (peek(')')) {
                    pos++;
                    break;
                }
                throw new IllegalArgumentException("Expected ',' or ')' at " + pos);
            }
        } else {
            pos++;
        }
        // 取已解析的前 3 个（函数各自校验数量）
        return applyFunction(name, count, args);
    }

    private double applyFunction(String name, int count, double[] args) {
        switch (name.toUpperCase()) {
            case "ROUND":
                requireArgs(name, count, 1, 2);
                double scale = count == 2 ? (int) args[1] : 0;
                double factor = Math.pow(10, scale);
                return Math.round(args[0] * factor) / factor;
            case "FLOOR":
                requireArgs(name, count, 1, 1);
                return Math.floor(args[0]);
            case "CEIL":
            case "CEILING":
                requireArgs(name, count, 1, 1);
                return Math.ceil(args[0]);
            case "ABS":
                requireArgs(name, count, 1, 1);
                return Math.abs(args[0]);
            case "MIN":
                requireArgs(name, count, 2, 2);
                return Math.min(args[0], args[1]);
            case "MAX":
                requireArgs(name, count, 2, 2);
                return Math.max(args[0], args[1]);
            case "SQRT":
                requireArgs(name, count, 1, 1);
                return Math.sqrt(args[0]);
            case "LOG":
                requireArgs(name, count, 1, 1);
                return Math.log(args[0]);
            default:
                throw new IllegalArgumentException("Unknown function: " + name);
        }
    }

    private void requireArgs(String name, int count, int min, int max) {
        if (count < min || count > max) {
            throw new IllegalArgumentException("Bad argument count for '" + name + "': " + count);
        }
    }

    private boolean peek(char c) {
        return pos < source.length() && source.charAt(pos) == c;
    }

    private boolean peekDigit() {
        return pos < source.length() && Character.isDigit(source.charAt(pos));
    }

    private boolean end() {
        return pos >= source.length();
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }
}
