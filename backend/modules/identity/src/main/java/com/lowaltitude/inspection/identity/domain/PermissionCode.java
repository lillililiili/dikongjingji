package com.lowaltitude.inspection.identity.domain;

public final class PermissionCode {

    public static final String INSPECTION_RESERVATION_CREATE = "inspection.reservation.create";
    public static final String INSPECTION_RESERVATION_READ_OWN = "inspection.reservation.read.own";
    public static final String INSPECTION_RESERVATION_READ_ASSIGNED = "inspection.reservation.read.assigned";
    public static final String INSPECTION_RESERVATION_READ_ALL = "inspection.reservation.read.all";
    public static final String INSPECTION_PLAN_PUBLISH_ASSIGNED = "inspection.plan.publish.assigned";
    public static final String INSPECTION_PLAN_READ_OWN = "inspection.plan.read.own";
    public static final String INSPECTION_PLAN_READ_ALL = "inspection.plan.read.all";
    public static final String INSPECTION_PLAN_CONFIRM_OWN = "inspection.plan.confirm.own";
    public static final String INSPECTION_ORDER_READ_OWN = "inspection.order.read.own";
    public static final String INSPECTION_ORDER_READ_ASSIGNED = "inspection.order.read.assigned";
    public static final String INSPECTION_ORDER_READ_ALL = "inspection.order.read.all";
    public static final String INSPECTION_ORDER_ARRANGE_ASSIGNED = "inspection.order.arrange.assigned";
    public static final String INSPECTION_ORDER_PROGRESS_ASSIGNED = "inspection.order.progress.assigned";
    public static final String INSPECTION_DELIVERY_PUBLISH_ASSIGNED = "inspection.delivery.publish.assigned";
    public static final String INSPECTION_DELIVERY_DOWNLOAD_OWN = "inspection.delivery.download.own";
    public static final String INSPECTION_DELIVERY_DOWNLOAD_ALL = "inspection.delivery.download.all";
    public static final String INSPECTION_ACCEPTANCE_CREATE_OWN = "inspection.acceptance.create.own";
    public static final String INSPECTION_AUDIT_READ = "inspection.audit.read";

    private PermissionCode() {
    }
}
