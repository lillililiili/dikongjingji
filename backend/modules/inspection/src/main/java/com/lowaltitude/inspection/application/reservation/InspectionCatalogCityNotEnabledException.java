package com.lowaltitude.inspection.application.reservation;

/** The city has no inspection service a customer can see (contract INS_CATALOG_CITY_NOT_ENABLED). */
public final class InspectionCatalogCityNotEnabledException extends RuntimeException {

    public InspectionCatalogCityNotEnabledException() {
        super("inspection catalog is not enabled in the city");
    }
}
