package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReservationApplicationServiceTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SERVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PROVIDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Clock BOOKING_CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsReservationWithServerOwnedServiceSnapshotAndEncryptedSensitiveFields() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(SERVICE_ID, PROVIDER_ID, "服务端巡检标题", "https://cdn.example/cover.jpg"),
                store, new PlainTestCipher(), BOOKING_CLOCK);

        ReservationView created = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));

        assertThat(created.serviceTitle()).isEqualTo("服务端巡检标题");
        assertThat(created.serviceCoverUrl()).isEqualTo("https://cdn.example/cover.jpg");
        assertThat(created.preferredTimeSlotCode()).isEqualTo("MORNING");
        assertThat(created.reservationNo()).startsWith("IR");
        assertThat(store.lastSaved().contactMobileCiphertext()).doesNotContain("13800138000");
        assertThat(store.lastSaved().addressCiphertext()).doesNotContain("东营市开发区测试路1号");
    }

    @Test
    void rejectsUnknownOrDisabledServiceRatherThanTrustingClientData() {
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(null, PROVIDER_ID, "ignored", null),
                new InMemoryReservationStore(), new PlainTestCipher(), BOOKING_CLOCK);

        assertThatThrownBy(() -> service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID)))
                .isInstanceOf(ReservationValidationException.class)
                .hasMessageContaining("service");
    }

    @Test
    void rejectsInspectionItemThatIsNotConfiguredForTheService() {
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(
                        SERVICE_ID,
                        PROVIDER_ID,
                        "服务端巡检标题",
                        null),
                new InMemoryReservationStore(),
                new PlainTestCipher(),
                BOOKING_CLOCK);
        CreateReservationCommand unsupported = new CreateReservationCommand(
                SERVICE_ID,
                "370500",
                "东营市开发区测试路1号",
                118.5,
                37.4,
                1200L,
                LocalDate.of(2026, 8, 1),
                "MORNING",
                List.of("UNSUPPORTED"),
                List.of(),
                null,
                "张三",
                "13800138000",
                "v1",
                true);

        assertThatThrownBy(() -> service.create(
                        CUSTOMER_ID,
                        IDEMPOTENCY_KEY,
                        unsupported))
                .isInstanceOf(ReservationValidationException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void replaysSameIdempotencyKeyAndRejectsDifferentPayload() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
                store, new PlainTestCipher(), BOOKING_CLOCK);

        ReservationView first = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
        ReservationView replay = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(store.saveCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(UUID.randomUUID())))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void replaysSuccessfulRequestAfterItsBookingDateLeavesTheAllowedWindow() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        InspectionServiceCatalog catalog =
                new InMemoryServiceCatalog(
                        SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null);
        ReservationApplicationService bookingService =
                new ReservationApplicationService(
                        catalog, store, new PlainTestCipher(), BOOKING_CLOCK);
        ReservationView first = bookingService.create(
                CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
        int locksAfterCreate = store.lockCount();
        int countsAfterCreate = store.bookedCountQueryCount();
        ReservationApplicationService replayService =
                new ReservationApplicationService(
                        catalog,
                        store,
                        new PlainTestCipher(),
                        Clock.fixed(
                                Instant.parse("2026-09-01T01:00:00Z"),
                                ZoneOffset.UTC));

        ReservationView replay = replayService.create(
                CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(store.lockCount()).isEqualTo(locksAfterCreate);
        assertThat(store.bookedCountQueryCount()).isEqualTo(countsAfterCreate);
        assertThat(store.saveCount()).isEqualTo(1);
    }

    @Test
    void acceptsNineReservationsAndRejectsTheTenthForOneSlot() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(
                        SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
                store,
                new PlainTestCipher(),
                BOOKING_CLOCK);

        for (int index = 0; index < 9; index++) {
            service.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    command(SERVICE_ID));
        }

        assertThatThrownBy(() -> service.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                command(SERVICE_ID)))
                .isInstanceOf(TimeSlotFullException.class);
    }

    @Test
    void idempotentReplayDoesNotConsumeASecondSlot() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(
                        SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
                store,
                new PlainTestCipher(),
                BOOKING_CLOCK);

        service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
        service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));

        assertThat(store.findBookedTimeSlotCounts(
                SERVICE_ID,
                "370500",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1)))
                .extracting(
                        BookedTimeSlotCount::slotCode,
                        BookedTimeSlotCount::bookedCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MORNING", 1));
    }

    @Test
    void replaysExistingReservationAfterDatabaseIdempotencyUniqueConflict() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        ReservationApplicationService service = new ReservationApplicationService(new InMemoryServiceCatalog(SERVICE_ID, PROVIDER_ID, "服务端标题", null), store, new PlainTestCipher(), BOOKING_CLOCK);
        ReservationView first = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
        ReservationApplicationService racing = new ReservationApplicationService(new InMemoryServiceCatalog(SERVICE_ID, PROVIDER_ID, "服务端标题", null), new ConflictStore(store), new PlainTestCipher(), BOOKING_CLOCK);
        assertThat(racing.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID)).id()).isEqualTo(first.id());
    }

    @Test
    void exposesBookedAndAvailableCountsForEveryFixedTimeSlot() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        store.setBookedTimeSlotCounts(List.of(
                new BookedTimeSlotCount(LocalDate.of(2026, 8, 1), "MORNING", 9),
                new BookedTimeSlotCount(LocalDate.of(2026, 8, 1), "AFTERNOON", 2)));
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
                store,
                new PlainTestCipher(),
                BOOKING_CLOCK);

        List<InspectionServiceDateAvailabilityView> dates = service.availableTimeSlots(
                SERVICE_ID,
                "370500",
                LocalDate.of(2026, 8, 1),
                2);

        assertThat(dates).hasSize(2);
        assertThat(dates.getFirst().slots())
                .extracting(
                        InspectionTimeSlotView::slotCode,
                        InspectionTimeSlotView::remainingCount,
                        InspectionTimeSlotView::availability)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MORNING", 0, "FULL"),
                        org.assertj.core.groups.Tuple.tuple("AFTERNOON", 7, "AVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple("EVENING", 9, "AVAILABLE"));
        assertThat(dates.get(1).serviceDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(dates.get(1).slots())
                .extracting(InspectionTimeSlotView::remainingCount)
                .containsExactly(9, 9, 9);
    }

    @Test
    void marksTodaySlotsTooSoonWithoutPretendingTheyAreFull() {
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(
                        SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
                new InMemoryReservationStore(),
                new PlainTestCipher(),
                Clock.fixed(Instant.parse("2026-08-01T09:00:01Z"), ZoneOffset.UTC));

        List<InspectionTimeSlotView> slots = service.availableTimeSlots(
                        SERVICE_ID, "370500", LocalDate.of(2026, 8, 1), 1)
                .getFirst()
                .slots();

        assertThat(slots)
                .extracting(
                        InspectionTimeSlotView::slotCode,
                        InspectionTimeSlotView::remainingCount,
                        InspectionTimeSlotView::availability)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MORNING", 9, "TOO_SOON"),
                        org.assertj.core.groups.Tuple.tuple("AFTERNOON", 9, "TOO_SOON"),
                        org.assertj.core.groups.Tuple.tuple("EVENING", 9, "TOO_SOON"));
    }

    @Test
    void rejectsCreatingAReservationLessThanOneHourBeforeSlotStart() {
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(
                        SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
                new InMemoryReservationStore(),
                new PlainTestCipher(),
                Clock.fixed(Instant.parse("2026-08-01T09:00:01Z"), ZoneOffset.UTC));
        CreateReservationCommand tooSoon = command(
                SERVICE_ID, LocalDate.of(2026, 8, 1), "EVENING");

        assertThatThrownBy(() -> service.create(
                CUSTOMER_ID, IDEMPOTENCY_KEY, tooSoon))
                .isInstanceOf(TimeSlotTooSoonException.class);
    }

    private static CreateReservationCommand command(UUID serviceId) {
        return command(serviceId, LocalDate.of(2026, 8, 1), "MORNING");
    }

    private static CreateReservationCommand command(
            UUID serviceId,
            LocalDate serviceDate,
            String slotCode
    ) {
        return new CreateReservationCommand(
                serviceId, "370500", "东营市开发区测试路1号", 118.5, 37.4,
                1200L, serviceDate, slotCode, List.of("PIPELINE"), List.of(),
                "现场需要重点查看", "张三", "13800138000", "v1", true);
    }
}

final class InMemoryServiceCatalog implements InspectionServiceCatalog {
    private final UUID activeId;
    private final UUID providerId;
    private final String title;
    private final String coverUrl;

    InMemoryServiceCatalog(UUID activeId, UUID providerId, String title, String coverUrl) {
        this.activeId = activeId;
        this.providerId = providerId;
        this.title = title;
        this.coverUrl = coverUrl;
    }

    @Override
    public Optional<InspectionServiceSnapshot> findActive(UUID serviceId, String cityCode) {
        return activeId != null && activeId.equals(serviceId)
                ? Optional.of(new InspectionServiceSnapshot(
                        activeId,
                        providerId,
                        title,
                        coverUrl,
                        cityCode,
                        List.of(new InspectionItemSnapshot("PIPELINE", "管线巡检"))))
                : Optional.empty();
    }
}

final class InMemoryReservationStore implements ReservationStore {
    private final Map<String, StoredReservation> reservations = new java.util.HashMap<>();
    private final Map<String, IdempotencyEntry> idempotency = new java.util.HashMap<>();
    private List<BookedTimeSlotCount> bookedTimeSlotCounts = List.of();
    private StoredReservation lastSaved;
    private int saveCount;
    private int lockCount;
    private int bookedCountQueryCount;

    @Override public Optional<IdempotencyEntry> findIdempotency(UUID actorId, UUID key) {
        return Optional.ofNullable(idempotency.get(actorId + ":" + key));
    }
    @Override public void save(StoredReservation reservation, IdempotencyEntry entry) {
        reservations.put(reservation.id().toString(), reservation);
        idempotency.put(entry.actorId() + ":" + entry.key(), entry);
        lastSaved = reservation;
        saveCount++;
    }
    @Override public Optional<StoredReservation> findById(UUID id) { return Optional.ofNullable(reservations.get(id.toString())); }
    @Override public void lockActiveService(UUID serviceId, String cityCode) {
        lockCount++;
        if (serviceId == null || cityCode == null) {
            throw new ReservationValidationException("service is invalid");
        }
    }
    @Override public List<BookedTimeSlotCount> findBookedTimeSlotCounts(
            UUID serviceId, String cityCode, LocalDate startDate, LocalDate endDate) {
        bookedCountQueryCount++;
        if (!bookedTimeSlotCounts.isEmpty()) {
            return bookedTimeSlotCounts;
        }
        return reservations.values().stream()
                .filter(value -> value.snapshot().serviceId().equals(serviceId))
                .filter(value -> value.cityCode().equals(cityCode))
                .filter(value -> !value.preferredServiceDate().isBefore(startDate))
                .filter(value -> !value.preferredServiceDate().isAfter(endDate))
                .collect(java.util.stream.Collectors.groupingBy(
                        value -> value.preferredServiceDate() + ":" + value.preferredTimeSlotCode(),
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(entry -> {
                    String[] key = entry.getKey().split(":", 2);
                    return new BookedTimeSlotCount(
                            LocalDate.parse(key[0]), key[1], entry.getValue().intValue());
                })
                .toList();
    }
    StoredReservation lastSaved() { return lastSaved; }
    int saveCount() { return saveCount; }
    int lockCount() { return lockCount; }
    int bookedCountQueryCount() { return bookedCountQueryCount; }
    void setBookedTimeSlotCounts(List<BookedTimeSlotCount> bookedTimeSlotCounts) {
        this.bookedTimeSlotCounts = List.copyOf(bookedTimeSlotCounts);
    }
}

final class PlainTestCipher implements SensitiveDataCipher {
    @Override public String encrypt(String value) { return "encrypted:" + Integer.toHexString(value.hashCode()); }
    @Override public String decrypt(String value) { throw new UnsupportedOperationException(); }
}
final class ConflictStore implements ReservationStore { private final InMemoryReservationStore delegate; ConflictStore(InMemoryReservationStore delegate){this.delegate=delegate;} public Optional<IdempotencyEntry> findIdempotency(UUID actor,UUID key){return delegate.findIdempotency(actor,key);} public void save(StoredReservation value,IdempotencyEntry entry){throw new DuplicateIdempotencyKeyException();} public Optional<StoredReservation> findById(UUID id){return delegate.findById(id);} public void lockActiveService(UUID serviceId, String cityCode){delegate.lockActiveService(serviceId, cityCode);} public List<BookedTimeSlotCount> findBookedTimeSlotCounts(UUID serviceId, String cityCode, LocalDate startDate, LocalDate endDate){return delegate.findBookedTimeSlotCounts(serviceId, cityCode, startDate, endDate);} }
