package com.lowaltitude.inspection.application.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PaymentNodePlannerTest {

    @Test
    void splitsTheConfirmedQuarterlyContractIntoDownPaymentAndSixVisits() {
        List<PlanPaymentNode> nodes = PaymentNodePlanner.plan(1_860_000L, 6);

        assertThat(nodes).hasSize(7);
        assertThat(nodes.get(0)).isEqualTo(
                new PlanPaymentNode(1, PaymentNodeType.DOWN_PAYMENT, null, 558_000L));
        assertThat(nodes.subList(1, 7)).allSatisfy(node -> {
            assertThat(node.nodeType()).isEqualTo(PaymentNodeType.PER_VISIT);
            assertThat(node.amountMinor()).isEqualTo(217_000L);
        });
        assertThat(nodes).extracting(PlanPaymentNode::visitNo)
                .containsExactly(null, 1, 2, 3, 4, 5, 6);
    }

    @Test
    void singleVisitPaysThirtyPercentThenTheRest() {
        assertThat(PaymentNodePlanner.plan(100_000L, 1)).extracting(PlanPaymentNode::amountMinor)
                .containsExactly(30_000L, 70_000L);
    }

    @Test
    void lastVisitAbsorbsTheRemainderSoNodesAlwaysAddUpToTheTotal() {
        List<PlanPaymentNode> nodes = PaymentNodePlanner.plan(100_001L, 3);

        assertThat(nodes).extracting(PlanPaymentNode::amountMinor)
                .containsExactly(30_000L, 23_333L, 23_333L, 23_335L);
        assertThat(total(nodes)).isEqualTo(100_001L);
    }

    @Test
    void totalsThatOverflowWhenMultipliedStillSplitExactly() {
        long total = 9_000_000_000_000_000_000L;

        List<PlanPaymentNode> nodes = PaymentNodePlanner.plan(total, 6);

        assertThat(nodes.get(0).amountMinor()).isEqualTo(2_700_000_000_000_000_000L);
        assertThat(total(nodes)).isEqualTo(total);
    }

    @Test
    void downPaymentRoundsHalfUpToTheFen() {
        assertThat(PaymentNodePlanner.plan(5L, 1)).extracting(PlanPaymentNode::amountMinor)
                .containsExactly(2L, 3L);
        assertThat(PaymentNodePlanner.plan(1L, 1)).extracting(PlanPaymentNode::amountMinor)
                .containsExactly(0L, 1L);
    }

    @Test
    void smallTotalsAndManyVisitsStillAddUp() {
        for (long total : new long[] {0L, 1L, 7L, 99L, 1_000_003L}) {
            for (int visits : new int[] {1, 2, 6, 52}) {
                List<PlanPaymentNode> nodes = PaymentNodePlanner.plan(total, visits);
                assertThat(nodes).hasSize(visits + 1);
                assertThat(total(nodes)).isEqualTo(total);
                assertThat(nodes).allSatisfy(node ->
                        assertThat(node.amountMinor()).isNotNegative());
            }
        }
    }

    private static long total(List<PlanPaymentNode> nodes) {
        return nodes.stream().mapToLong(PlanPaymentNode::amountMinor).sum();
    }
}
