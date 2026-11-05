package com.lowaltitude.inspection.application.serviceflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a plan total into a 30% down payment plus one node per visit (概要设计 §14 D9).
 * The down payment rounds half up to the fen, and the last visit absorbs the remainder so
 * the nodes always add up to the plan total.
 */
final class PaymentNodePlanner {

    private static final long DOWN_PAYMENT_PERCENT = 30;

    private PaymentNodePlanner() {
    }

    static List<PlanPaymentNode> plan(long totalAmountMinor, int visitCount) {
        // Split at the hundreds so a total near Long.MAX_VALUE cannot overflow.
        long downPayment = totalAmountMinor / 100 * DOWN_PAYMENT_PERCENT
                + (totalAmountMinor % 100 * DOWN_PAYMENT_PERCENT + 50) / 100;
        long remaining = totalAmountMinor - downPayment;
        long perVisit = remaining / visitCount;
        long lastVisit = remaining - perVisit * (visitCount - 1);

        List<PlanPaymentNode> nodes = new ArrayList<>(visitCount + 1);
        nodes.add(new PlanPaymentNode(1, PaymentNodeType.DOWN_PAYMENT, null, downPayment));
        for (int visitNo = 1; visitNo <= visitCount; visitNo++) {
            nodes.add(new PlanPaymentNode(
                    visitNo + 1,
                    PaymentNodeType.PER_VISIT,
                    visitNo,
                    visitNo == visitCount ? lastVisit : perVisit));
        }
        return List.copyOf(nodes);
    }
}
