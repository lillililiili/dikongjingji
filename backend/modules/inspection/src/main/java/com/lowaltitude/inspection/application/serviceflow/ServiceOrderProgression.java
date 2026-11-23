package com.lowaltitude.inspection.application.serviceflow;

import java.util.List;

/** Derives the service order status from its visits; a single visit behaves as before. */
final class ServiceOrderProgression {

    private ServiceOrderProgression() {
    }

    static ServiceOrderStatus derive(List<StoredServiceVisit> visits) {
        if (visits.stream().allMatch(ServiceOrderProgression::accepted)) {
            return ServiceOrderStatus.ACCEPTED;
        }
        if (visits.stream().allMatch(ServiceOrderProgression::delivered)) {
            return ServiceOrderStatus.DELIVERED;
        }
        return visits.stream().anyMatch(ServiceOrderProgression::started)
                ? ServiceOrderStatus.IN_PROGRESS
                : ServiceOrderStatus.ARRANGING;
    }

    static List<StoredServiceVisit> replace(
            List<StoredServiceVisit> visits,
            StoredServiceVisit updated
    ) {
        return visits.stream()
                .map(visit -> visit.id().equals(updated.id()) ? updated : visit)
                .toList();
    }

    private static boolean accepted(StoredServiceVisit visit) {
        return visit.status() == ServiceVisitStatus.ACCEPTED;
    }

    private static boolean delivered(StoredServiceVisit visit) {
        return visit.status() == ServiceVisitStatus.DELIVERED || accepted(visit);
    }

    /** 退回整改的那次巡检算已开工、未交付（B2）。 */
    private static boolean started(StoredServiceVisit visit) {
        return visit.status() == ServiceVisitStatus.IN_PROGRESS
                || visit.status() == ServiceVisitStatus.REWORK
                || delivered(visit);
    }
}
