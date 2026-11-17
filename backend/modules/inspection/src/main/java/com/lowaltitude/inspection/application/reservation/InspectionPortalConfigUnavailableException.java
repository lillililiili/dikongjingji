package com.lowaltitude.inspection.application.reservation;

/** The portal has no active headline to show (contract ConfigUnavailable, 503). */
public final class InspectionPortalConfigUnavailableException extends RuntimeException {

    public InspectionPortalConfigUnavailableException() {
        super("inspection portal configuration is unavailable");
    }
}
