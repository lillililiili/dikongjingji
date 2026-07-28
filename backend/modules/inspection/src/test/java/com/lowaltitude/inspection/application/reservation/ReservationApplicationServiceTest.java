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

    @Test
    void createsReservationWithServerOwnedServiceSnapshotAndEncryptedSensitiveFields() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(SERVICE_ID, PROVIDER_ID, "服务端巡检标题", "https://cdn.example/cover.jpg"),
                store, new PlainTestCipher(), Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC));

        ReservationView created = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));

        assertThat(created.serviceTitle()).isEqualTo("服务端巡检标题");
        assertThat(created.serviceCoverUrl()).isEqualTo("https://cdn.example/cover.jpg");
        assertThat(created.reservationNo()).startsWith("IR");
        assertThat(store.lastSaved().contactMobileCiphertext()).doesNotContain("13800138000");
        assertThat(store.lastSaved().addressCiphertext()).doesNotContain("东营市开发区测试路1号");
    }

    @Test
    void rejectsUnknownOrDisabledServiceRatherThanTrustingClientData() {
        ReservationApplicationService service = new ReservationApplicationService(
                new InMemoryServiceCatalog(null, PROVIDER_ID, "ignored", null),
                new InMemoryReservationStore(), new PlainTestCipher(), Clock.systemUTC());

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
                Clock.systemUTC());
        CreateReservationCommand unsupported = new CreateReservationCommand(
                SERVICE_ID,
                "370500",
                "东营市开发区测试路1号",
                118.5,
                37.4,
                1200L,
                LocalDate.of(2026, 8, 1),
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
                store, new PlainTestCipher(), Clock.systemUTC());

        ReservationView first = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
        ReservationView replay = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(store.saveCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(UUID.randomUUID())))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void replaysExistingReservationAfterDatabaseIdempotencyUniqueConflict() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        ReservationApplicationService service = new ReservationApplicationService(new InMemoryServiceCatalog(SERVICE_ID, PROVIDER_ID, "服务端标题", null), store, new PlainTestCipher(), Clock.systemUTC());
        ReservationView first = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
        ReservationApplicationService racing = new ReservationApplicationService(new InMemoryServiceCatalog(SERVICE_ID, PROVIDER_ID, "服务端标题", null), new ConflictStore(store), new PlainTestCipher(), Clock.systemUTC());
        assertThat(racing.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID)).id()).isEqualTo(first.id());
    }

    private static CreateReservationCommand command(UUID serviceId) {
        return new CreateReservationCommand(serviceId, "370500", "东营市开发区测试路1号", 118.5, 37.4,
                1200L, LocalDate.of(2026, 8, 1), List.of("PIPELINE"), List.of(), "现场需要重点查看",
                "张三", "13800138000", "v1", true);
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
    private StoredReservation lastSaved;
    private int saveCount;

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
    StoredReservation lastSaved() { return lastSaved; }
    int saveCount() { return saveCount; }
}

final class PlainTestCipher implements SensitiveDataCipher {
    @Override public String encrypt(String value) { return "encrypted:" + Integer.toHexString(value.hashCode()); }
    @Override public String decrypt(String value) { throw new UnsupportedOperationException(); }
}
final class ConflictStore implements ReservationStore { private final InMemoryReservationStore delegate; ConflictStore(InMemoryReservationStore delegate){this.delegate=delegate;} public Optional<IdempotencyEntry> findIdempotency(UUID actor,UUID key){return delegate.findIdempotency(actor,key);} public void save(StoredReservation value,IdempotencyEntry entry){throw new DuplicateIdempotencyKeyException();} public Optional<StoredReservation> findById(UUID id){return delegate.findById(id);} }
