package io.castellan.bpmn.expr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionEvaluatorTest {

    @Test
    void evaluatesNumericComparisons() {
        Map<String, Object> vars = Map.of("amount", 150.0);
        assertThat(ExpressionEvaluator.evaluateBoolean("amount > 100", vars)).isTrue();
        assertThat(ExpressionEvaluator.evaluateBoolean("amount < 100", vars)).isFalse();
        assertThat(ExpressionEvaluator.evaluateBoolean("amount >= 150", vars)).isTrue();
        assertThat(ExpressionEvaluator.evaluateBoolean("amount == 150", vars)).isTrue();
        assertThat(ExpressionEvaluator.evaluateBoolean("amount != 150", vars)).isFalse();
    }

    @Test
    void evaluatesStringEquality() {
        Map<String, Object> vars = Map.of("status", "APPROVED");
        assertThat(ExpressionEvaluator.evaluateBoolean("status == 'APPROVED'", vars)).isTrue();
        assertThat(ExpressionEvaluator.evaluateBoolean("status == \"REJECTED\"", vars)).isFalse();
    }

    @Test
    void evaluatesBooleanLogicWithPrecedenceAndParens() {
        Map<String, Object> vars = Map.of("amount", 5000.0, "status", "REVIEW");
        assertThat(ExpressionEvaluator.evaluateBoolean("amount > 1000 && amount <= 10000", vars)).isTrue();
        assertThat(ExpressionEvaluator.evaluateBoolean("amount > 10000 || status == 'REVIEW'", vars)).isTrue();
        assertThat(ExpressionEvaluator.evaluateBoolean("!(amount > 10000)", vars)).isTrue();
        assertThat(ExpressionEvaluator.evaluateBoolean("(amount > 1000 && amount < 2000) || status == 'REVIEW'", vars)).isTrue();
    }

    @Test
    void evaluatesArithmetic() {
        Map<String, Object> vars = Map.of("amount", 40.0, "fee", 10.0);
        assertThat(ExpressionEvaluator.evaluate("amount + fee", vars)).isEqualTo(50.0);
        assertThat(ExpressionEvaluator.evaluate("amount * 2 - fee", vars)).isEqualTo(70.0);
        assertThat(ExpressionEvaluator.evaluateBoolean("amount + fee > 45", vars)).isTrue();
    }

    @Test
    void stripsDollarBraceWrapper() {
        assertThat(ExpressionEvaluator.evaluateBoolean("${amount > 10}", Map.of("amount", 20.0))).isTrue();
    }

    @Test
    void bareVariableIsTruthyChecked() {
        assertThat(ExpressionEvaluator.evaluateBoolean("approved", Map.of("approved", true))).isTrue();
        assertThat(ExpressionEvaluator.evaluateBoolean("approved", Map.of("approved", false))).isFalse();
    }

    @Test
    void rejectsMalformedExpression() {
        assertThatThrownBy(() -> ExpressionEvaluator.evaluate("amount >", Map.of("amount", 1.0)))
                .isInstanceOf(ExpressionSyntaxException.class);
        assertThatThrownBy(() -> ExpressionEvaluator.evaluate("amount > 1 extra", Map.of("amount", 1.0)))
                .isInstanceOf(ExpressionSyntaxException.class);
    }
}
