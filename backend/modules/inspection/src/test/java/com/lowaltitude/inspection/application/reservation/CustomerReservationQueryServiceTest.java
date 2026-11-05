package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class CustomerReservationQueryServiceTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();

    @Test
    void returnsOnlyTheRequestedCustomersSecondPageAndDecryptsAddress() {
        var store = new InMemoryCustomerReservationQueryStore();
        store.rows = IntStream.rangeClosed(1, 21)
                .mapToObj(index -> stored(
                        CustomerReservationStage.AWAITING_CONFIRMATION,
                        "x:东营市东营区黄河路 " + index + " 号",
                        index == 21 ? PLAN_ID : UUID.randomUUID(),
                        null,
                        34900L))
                .toList();
        store.counts = new CustomerReservationCountSnapshot(21, 3, 21, 4, 5, 6);
        var service = new CustomerReservationQueryService(store, new ReversibleCipher());

        CustomerReservationPage page = service.listOwn(
                CUSTOMER_ID,
                CustomerReservationFilter.AWAITING_CONFIRMATION,
                2,
                20);

        assertThat(store.customerId).isEqualTo(CUSTOMER_ID);
        assertThat(store.filter).isEqualTo(CustomerReservationFilter.AWAITING_CONFIRMATION);
        assertThat(store.limit).isEqualTo(20);
        assertThat(store.offset).isEqualTo(20);
        assertThat(page.items()).singleElement().satisfies(order -> {
            assertThat(order.stage()).isEqualTo(CustomerReservationStage.AWAITING_CONFIRMATION);
            assertThat(order.locationSummary()).isEqualTo("东营市东营区黄河路 21 号");
            assertThat(order.currentPlanId()).isEqualTo(PLAN_ID);
            assertThat(order.quoteAmountMinor()).isEqualTo(34900L);
        });
        assertThat(page.total()).isEqualTo(21);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.stageCounts()).isEqualTo(
                new CustomerReservationStageCounts(3, 21, 4, 5, 6));
    }

    @Test
    void validatesFiltersAndPagingBeforeCallingPersistence() {
        var store = new InMemoryCustomerReservationQueryStore();
        var service = new CustomerReservationQueryService(store, new ReversibleCipher());

        assertThatThrownBy(() -> service.listOwn(null, CustomerReservationFilter.ALL, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listOwn(CUSTOMER_ID, CustomerReservationFilter.ALL, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listOwn(CUSTOMER_ID, CustomerReservationFilter.ALL, 1, 51))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerReservationFilter.parse("CANCELLED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.customerId).isNull();
    }

    private static StoredCustomerReservationSummary stored(
            CustomerReservationStage stage,
            String locationCiphertext,
            UUID currentPlanId,
            UUID serviceOrderId,
            Long quoteAmountMinor
    ) {
        return new StoredCustomerReservationSummary(
                UUID.randomUUID(),
                "IR202607310001",
                UUID.randomUUID(),
                "输电线路巡检",
                "https://cdn.example.com/power-line.jpg",
                "370500",
                locationCiphertext,
                LocalDate.of(2026, 8, 1),
                Instant.parse("2026-07-31T00:00:00Z"),
                stage,
                "SUBMITTED",
                currentPlanId,
                serviceOrderId,
                null,
                quoteAmountMinor,
                0,
                0,
                null,
                null,
                null);
    }
}

final class InMemoryCustomerReservationQueryStore implements CustomerReservationQueryStore {

    UUID customerId;
    CustomerReservationFilter filter;
    int limit;
    int offset;
    List<StoredCustomerReservationSummary> rows = List.of();
    CustomerReservationCountSnapshot counts =
            new CustomerReservationCountSnapshot(0, 0, 0, 0, 0, 0);

    @Override
    public List<StoredCustomerReservationSummary> findOwn(
            UUID customerId,
            CustomerReservationFilter filter,
            int limit,
            int offset
    ) {
        this.customerId = customerId;
        this.filter = filter;
        this.limit = limit;
        this.offset = offset;
        return rows.stream()
                .filter(row -> filter == CustomerReservationFilter.ALL
                        || row.stage().name().equals(filter.name()))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public CustomerReservationCountSnapshot countOwn(UUID customerId) {
        this.customerId = customerId;
        return counts;
    }
}

final class ReversibleCipher implements SensitiveDataCipher {

    @Override
    public String encrypt(String value) {
        return "x:" + value;
    }

    @Override
    public String decrypt(String value) {
        return value.substring(2);
    }
}
