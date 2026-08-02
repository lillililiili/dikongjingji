package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InspectionReservationCancellationOpenApiContractTest {

    @Test
    void contractDeclaresCancellationHeadersPermissionAndConflicts() throws Exception {
        String contract = Files.readString(Path.of(
                "..", "..", "contracts", "openapi-inspection-service-flow.yaml"));
        String cancelOperation = section(
                contract,
                "  /api/v1/inspection/reservations/{reservationId}/cancel:",
                "\n  /api/");
        String ifMatchParameter = section(
                contract,
                "    IfMatch:",
                "\n  responses:");

        assertThat(cancelOperation)
                .contains("operationId: cancelInspectionReservation")
                .contains("x-required-permission: inspection.reservation.cancel.own")
                .contains("#/components/parameters/IdempotencyKey")
                .contains("#/components/parameters/IfMatch")
                .contains("\"400\":")
                .contains("\"403\":")
                .contains("\"409\":")
                .contains("notCancellable:")
                .contains("INS_RESERVATION_NOT_CANCELLABLE")
                .contains("idempotencyKeyReused:")
                .contains("INS_IDEMPOTENCY_KEY_REUSED")
                .contains("\"412\":")
                .contains("#/components/responses/PreconditionFailed");

        assertThat(ifMatchParameter)
                .contains("pattern: '^\"(0|[1-9][0-9]*)\"$'")
                .contains("example: '\"7\"'");
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertThat(start).isNotNegative();
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
