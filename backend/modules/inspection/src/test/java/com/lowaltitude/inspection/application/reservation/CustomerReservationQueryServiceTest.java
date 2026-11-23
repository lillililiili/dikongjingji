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
    private static final UUID PROVIDER = UUID.randomUUID();

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
            assertThat(order.provider()).isEqualTo(new ReservationProvider(PROVIDER, "云翼科技", false));
        });
        assertThat(page.total()).isEqualTo(21);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.stageCounts()).isEqualTo(
                new CustomerReservationStageCounts(3, 21, 4, 5, 6));
    }

    // S3-2：服务商工作台按令牌里的机构读分给它的预约，形状、阶段和客户订单一样，地址同样解密。
    @Test
    void listsTheReservationsAssignedToTheProviderWithTheSameShape() {
        UUID providerId = UUID.randomUUID();
        var store = new InMemoryCustomerReservationQueryStore();
        store.rows = List.of(
                stored(CustomerReservationStage.AWAITING_PLAN, "x:东营市垦利区 1 号", null, null, null),
                stored(CustomerReservationStage.IN_PROGRESS, "x:东营市河口区 2 号", PLAN_ID, UUID.randomUUID(), 120000L));
        store.counts = new CustomerReservationCountSnapshot(2, 1, 0, 1, 0, 0);
        var service = new CustomerReservationQueryService(store, new ReversibleCipher());

        CustomerReservationPage page = service.listAssigned(providerId, CustomerReservationFilter.IN_PROGRESS, 1, 20);

        assertThat(store.providerId).isEqualTo(providerId);
        assertThat(store.customerId).isNull();
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(order -> {
            assertThat(order.locationSummary()).isEqualTo("东营市河口区 2 号");
            assertThat(order.quoteAmountMinor()).isEqualTo(120000L);
            assertThat(order.currency()).isEqualTo("CNY");
        });
        assertThat(page.stageCounts()).isEqualTo(new CustomerReservationStageCounts(1, 0, 1, 0, 0));

        UUID first = store.rows.getFirst().reservationId();
        assertThat(service.readAssigned(providerId, first).locationSummary()).isEqualTo("东营市垦利区 1 号");
        assertThatThrownBy(() -> service.readAssigned(providerId, UUID.randomUUID()))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.readAssigned(null, first)).isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.listAssigned(null, CustomerReservationFilter.ALL, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listAssigned(providerId, CustomerReservationFilter.ALL, 1, 51))
                .isInstanceOf(IllegalArgumentException.class);
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
                null,
                new ReservationProvider(PROVIDER, "云翼科技", false));
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

    UUID providerId;

    @Override
    public List<StoredCustomerReservationSummary> findAssigned(
            UUID providerId,
            CustomerReservationFilter filter,
            int limit,
            int offset
    ) {
        this.providerId = providerId;
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
    public java.util.Optional<StoredCustomerReservationSummary> findAssigned(UUID providerId, UUID reservationId) {
        this.providerId = providerId;
        return rows.stream().filter(row -> row.reservationId().equals(reservationId)).findFirst();
    }

    @Override
    public CustomerReservationCountSnapshot countAssigned(UUID providerId) {
        this.providerId = providerId;
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
