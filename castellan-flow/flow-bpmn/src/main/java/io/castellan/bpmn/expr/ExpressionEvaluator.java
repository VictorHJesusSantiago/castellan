package io.castellan.bpmn.expr;

import java.util.List;
import java.util.Map;

/**
 * A small, real recursive-descent expression evaluator for BPMN sequence-flow conditions and
 * {@code scriptTask} assignments. This is not a general expression language (no function calls,
 * no collections, no member access) — it is exactly the comparison/boolean-logic/arithmetic
 * subset needed to route gateways and compute a derived variable, spelled out in full below.
 *
 * <p><b>Supported grammar</b> (highest to lowest precedence binds tightest):
 * <pre>
 *   expr       := orExpr
 *   orExpr     := andExpr ( ("||" | "or") andExpr )*
 *   andExpr    := notExpr ( ("&&" | "and") notExpr )*
 *   notExpr    := ("!" | "not") notExpr | comparison
 *   comparison := additive ( ("==" | "!=" | ">" | ">=" | "<" | "<=") additive )?
 *   additive   := multiplicative ( ("+" | "-") multiplicative )*
 *   multiplicative := unary ( ("*" | "/") unary )*
 *   unary      := "-" unary | primary
 *   primary    := NUMBER | STRING | "true" | "false" | IDENT | "(" expr ")"
 * </pre>
 *
 * <p>Literals: decimal numbers ({@code 42}, {@code 3.5}), single- or double-quoted strings
 * ({@code 'APPROVED'}), {@code true}/{@code false}. An identifier resolves against the supplied
 * variable map; a variable that resolves as a bare operand (no comparison operator) is truthy per
 * {@link #truthy(Object)}. The optional BPMN {@code ${...}} wrapper is stripped if present, so
 * both {@code amount > 100} and {@code ${amount > 100}} are accepted.
 *
 * <p>Out of scope, by design: string concatenation via {@code +}, member/array access
 * ({@code order.total}), function calls, ternary expressions. A condition needing any of those is
 * outside this module's expression support.
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {
    }

    public static Object evaluate(String expression, Map<String, Object> variables) {
        String source = strip(expression);
        Parser parser = new Parser(new Lexer(source).tokenize());
        Object result = parser.parseExpression(variables);
        parser.expectEnd();
        return result;
    }

    /** Evaluates and coerces the result to boolean per {@link #truthy(Object)} — the entry point
     * gateways use to decide whether a conditional flow is taken. */
    public static boolean evaluateBoolean(String expression, Map<String, Object> variables) {
        return truthy(evaluate(expression, variables));
    }

    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0;
        if (value instanceof String s) return !s.isEmpty();
        return true;
    }

    private static String strip(String expression) {
        String trimmed = expression.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }


    enum TokenType { NUMBER, STRING, IDENT, OP, EOF }

    record Token(TokenType type, String text) {
    }

    private static final class Lexer {
        private final String src;
        private int pos = 0;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            List<Token> tokens = new java.util.ArrayList<>();
            Token t;
            while ((t = next()).type() != TokenType.EOF) {
                tokens.add(t);
            }
            tokens.add(t);
            return tokens;
        }

        private Token next() {
            skipWhitespace();
            if (pos >= src.length()) {
                return new Token(TokenType.EOF, "");
            }
            char c = src.charAt(pos);
            if (Character.isDigit(c)) {
                int start = pos;
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
                return new Token(TokenType.NUMBER, src.substring(start, pos));
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                pos++;
                int start = pos;
                while (pos < src.length() && src.charAt(pos) != quote) pos++;
                String s = src.substring(start, pos);
                if (pos >= src.length()) {
                    throw new ExpressionSyntaxException("unterminated string literal in: " + src);
                }
                pos++;
                return new Token(TokenType.STRING, s);
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                return new Token(TokenType.IDENT, src.substring(start, pos));
            }
            for (String op : new String[] {"==", "!=", ">=", "<=", "&&", "||"}) {
                if (src.startsWith(op, pos)) {
                    pos += op.length();
                    return new Token(TokenType.OP, op);
                }
            }
            if ("()!<>+-*/".indexOf(c) >= 0) {
                pos++;
                return new Token(TokenType.OP, String.valueOf(c));
            }
            throw new ExpressionSyntaxException("unexpected character '" + c + "' in: " + src);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }


    private static final class Parser {
        private final List<Token> tokens;
        private int index = 0;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Object parseExpression(Map<String, Object> vars) {
            return orExpr(vars);
        }

        void expectEnd() {
            if (peek().type() != TokenType.EOF) {
                throw new ExpressionSyntaxException("unexpected trailing input: " + peek().text());
            }
        }

        private Object orExpr(Map<String, Object> vars) {
            Object left = andExpr(vars);
            while (isOp("||") || isWord("or")) {
                advance();
                Object right = andExpr(vars);
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        private Object andExpr(Map<String, Object> vars) {
            Object left = notExpr(vars);
            while (isOp("&&") || isWord("and")) {
                advance();
                Object right = notExpr(vars);
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        private Object notExpr(Map<String, Object> vars) {
            if (isOp("!") || isWord("not")) {
                advance();
                return !truthy(notExpr(vars));
            }
            return comparison(vars);
        }

        private Object comparison(Map<String, Object> vars) {
            Object left = additive(vars);
            if (isOp("==") || isOp("!=") || isOp(">") || isOp(">=") || isOp("<") || isOp("<=")) {
                String op = advance().text();
                Object right = additive(vars);
                return compare(op, left, right);
            }
            return left;
        }

        private Object additive(Map<String, Object> vars) {
            Object left = multiplicative(vars);
            while (isOp("+") || isOp("-")) {
                String op = advance().text();
                Object right = multiplicative(vars);
                left = op.equals("+") ? asNumber(left) + asNumber(right) : asNumber(left) - asNumber(right);
            }
            return left;
        }

        private Object multiplicative(Map<String, Object> vars) {
            Object left = unary(vars);
            while (isOp("*") || isOp("/")) {
                String op = advance().text();
                Object right = unary(vars);
                left = op.equals("*") ? asNumber(left) * asNumber(right) : asNumber(left) / asNumber(right);
            }
            return left;
        }

        private Object unary(Map<String, Object> vars) {
            if (isOp("-")) {
                advance();
                return -asNumber(unary(vars));
            }
            return primary(vars);
        }

        private Object primary(Map<String, Object> vars) {
            Token t = peek();
            switch (t.type()) {
                case NUMBER -> {
                    advance();
                    return Double.parseDouble(t.text());
                }
                case STRING -> {
                    advance();
                    return t.text();
                }
                case IDENT -> {
                    advance();
                    return switch (t.text()) {
                        case "true" -> Boolean.TRUE;
                        case "false" -> Boolean.FALSE;
                        default -> vars.get(t.text());
                    };
                }
                case OP -> {
                    if (t.text().equals("(")) {
                        advance();
                        Object value = orExpr(vars);
                        if (!isOp(")")) {
                            throw new ExpressionSyntaxException("expected ')'");
                        }
                        advance();
                        return value;
                    }
                    throw new ExpressionSyntaxException("unexpected token: " + t.text());
                }
                default -> throw new ExpressionSyntaxException("unexpected end of expression");
            }
        }

        private Object compare(String op, Object left, Object right) {
            if (left instanceof Number || right instanceof Number) {
                double l = asNumber(left);
                double r = asNumber(right);
                return switch (op) {
                    case "==" -> l == r;
                    case "!=" -> l != r;
                    case ">" -> l > r;
                    case ">=" -> l >= r;
                    case "<" -> l < r;
                    case "<=" -> l <= r;
                    default -> throw new ExpressionSyntaxException("unknown operator: " + op);
                };
            }
            int cmp = String.valueOf(left).compareTo(String.valueOf(right));
            return switch (op) {
                case "==" -> java.util.Objects.equals(left, right);
                case "!=" -> !java.util.Objects.equals(left, right);
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                default -> throw new ExpressionSyntaxException("unknown operator: " + op);
            };
        }

        private double asNumber(Object value) {
            if (value instanceof Number n) return n.doubleValue();
            if (value == null) throw new ExpressionSyntaxException("expected a number but variable was undefined");
            throw new ExpressionSyntaxException("expected a number, got: " + value);
        }

        private boolean isOp(String text) {
            Token t = peek();
            return t.type() == TokenType.OP && t.text().equals(text);
        }

        private boolean isWord(String text) {
            Token t = peek();
            return t.type() == TokenType.IDENT && t.text().equals(text);
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token advance() {
            return tokens.get(index++);
        }
    }
}
