package io.castellan.apm.collector.trace;

import io.castellan.apm.core.SpanData;
import io.castellan.apm.core.SpanKind;
import io.castellan.apm.core.SpanStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAssemblerTest {

    private static SpanData span(String spanId, String parentSpanId, long startMillis) {
        return new SpanData("t1", spanId, parentSpanId, "op-" + spanId, SpanKind.INTERNAL, SpanStatus.OK,
                null, null, startMillis, startMillis + 1, 1_000_000L, Map.of());
    }

    @Test
    void aSingleRootWithNoChildrenAssemblesToOneLeafNode() {
        TraceView view = TraceAssembler.assemble("t1", List.of(span("a", null, 100)));

        assertThat(view.spanCount()).isEqualTo(1);
        assertThat(view.roots()).hasSize(1);
        assertThat(view.roots().get(0).span().spanId()).isEqualTo("a");
        assertThat(view.roots().get(0).children()).isEmpty();
    }

    @Test
    void childrenAreNestedUnderTheirParentAndOrderedByStartTime() {
        TraceView view = TraceAssembler.assemble("t1", List.of(
                span("a", null, 100),
                span("c", "a", 300),
                span("b", "a", 200)));

        SpanNode root = view.roots().get(0);
        assertThat(root.children()).extracting(n -> n.span().spanId()).containsExactly("b", "c");
    }

    @Test
    void aSpanWhoseParentIdIsNotInTheBatchBecomesItsOwnRoot() {
        TraceView view = TraceAssembler.assemble("t1", List.of(span("orphan", "never-exported", 100)));

        assertThat(view.roots()).hasSize(1);
        assertThat(view.roots().get(0).span().spanId()).isEqualTo("orphan");
    }

    @Test
    void multipleGenuineRootsAreAllReturned() {
        TraceView view = TraceAssembler.assemble("t1", List.of(
                span("a", null, 100),
                span("b", null, 200)));

        assertThat(view.roots()).extracting(n -> n.span().spanId()).containsExactly("a", "b");
    }

    @Test
    void aCyclicParentChainReachableFromARealRootTerminatesInsteadOfLoopingForever() {
        SpanData r = span("r", null, 100);
        SpanData x1 = span("x", "r", 200);
        SpanData y = span("y", "x", 300);
        SpanData x2 = span("x", "y", 400);

        TraceView view = TraceAssembler.assemble("t1", List.of(r, x1, y, x2));

        assertThat(view.spanCount()).isEqualTo(4);
        assertThat(view.roots()).hasSize(1);
        assertThat(collectSpanIds(view)).containsExactly("r", "x", "y", "x");
        SpanNode rNode = view.roots().get(0);
        SpanNode x1Node = rNode.children().get(0);
        SpanNode yNode = x1Node.children().get(0);
        SpanNode x2Node = yNode.children().get(0);
        assertThat(x2Node.children()).isEmpty();
    }

    private static List<String> collectSpanIds(TraceView view) {
        List<String> ids = new java.util.ArrayList<>();
        for (SpanNode root : view.roots()) {
            collect(root, ids);
        }
        return ids;
    }

    private static void collect(SpanNode node, List<String> out) {
        out.add(node.span().spanId());
        for (SpanNode child : node.children()) {
            collect(child, out);
        }
    }
}
