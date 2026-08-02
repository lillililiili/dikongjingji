package com.lowaltitude.inspection.application.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

/** Creates reservations from server-owned service snapshots; never logs request content. */
public class ReservationApplicationService {

    private static final int MAX_ADDRESS_LENGTH = 240;
    private static final int MAX_CONTACT_NAME_LENGTH = 40;
    private static final int MAX_NOTE_LENGTH = 1_000;
    private static final int MAX_ITEM_COUNT = 20;
    private static final int MAX_ATTACHMENT_COUNT = 9;
    private static final long MAX_LINE_LENGTH_METERS = 10_000_000L;

    private final InspectionServiceCatalog services;
    private final ReservationStore store;
    private final SensitiveDataCipher cipher;
    private final Clock clock;

    public ReservationApplicationService(InspectionServiceCatalog services, ReservationStore store,
                                         SensitiveDataCipher cipher, Clock clock) {
        this.services = services;
        this.store = store;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Transactional
    public ReservationView create(
            UUID customerId,
            UUID idempotencyKey,
            CreateReservationCommand command
    ) {
        if (customerId == null || idempotencyKey == null) {
            throw new ReservationValidationException("authenticated customer and idempotency key are required");
        }
        validate(command);
        String digest = digest(command);
        Optional<IdempotencyEntry> prior = store.findIdempotency(customerId, idempotencyKey);
        if (prior.isPresent()) {
            if (!prior.get().requestDigest().equals(digest)) {
                throw new IdempotencyConflictException();
            }
            return toView(store.findById(prior.get().reservationId()).orElseThrow());
        }
        InspectionTimeSlotPolicy.validateDateRange(
                command.preferredServiceDate(), 1, clock);
        InspectionTimeSlotDefinition selectedSlot = InspectionTimeSlotPolicy.slot(
                command.preferredTimeSlotCode());
        InspectionServiceSnapshot snapshot = services.findActive(command.serviceId(), command.cityCode())
                .orElseThrow(() -> new ReservationValidationException("service is unknown or disabled"));
        if (!InspectionTimeSlotPolicy.satisfiesLeadTime(
                command.preferredServiceDate(), selectedSlot, clock)) {
            throw new TimeSlotTooSoonException();
        }
        store.lockActiveService(command.serviceId(), command.cityCode());
        Optional<IdempotencyEntry> concurrentPrior =
                store.findIdempotency(customerId, idempotencyKey);
        if (concurrentPrior.isPresent()) {
            if (!concurrentPrior.get().requestDigest().equals(digest)) {
                throw new IdempotencyConflictException();
            }
            return toView(store.findById(concurrentPrior.get().reservationId())
                    .orElseThrow());
        }
        int booked = store.findBookedTimeSlotCounts(
                        command.serviceId(),
                        command.cityCode(),
                        command.preferredServiceDate(),
                        command.preferredServiceDate()).stream()
                .filter(count -> count.slotCode().equals(
                        command.preferredTimeSlotCode()))
                .mapToInt(BookedTimeSlotCount::bookedCount)
                .findFirst()
                .orElse(0);
        if (booked >= InspectionTimeSlotPolicy.CAPACITY) {
            throw new TimeSlotFullException();
        }
        List<InspectionItemSnapshot> selectedItems =
                selectSupportedItems(snapshot, command.inspectionItemCodes());
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        StoredReservation reservation = new StoredReservation(
                id,
                "IR" + now.toEpochMilli() + id.toString().substring(0, 8),
                customerId,
                snapshot,
                command.cityCode(),
                cipher.encrypt(command.address().trim()),
                command.longitude(),
                command.latitude(),
                command.lineLengthMeters(),
                command.preferredServiceDate(),
                command.preferredTimeSlotCode(),
                cipher.encryptNullable(normalizeNullable(command.note())),
                cipher.encrypt(command.contactName().trim()),
                cipher.encrypt(command.contactMobile().trim()),
                selectedItems,
                command.agreementVersion().trim(),
                now,
                0);
        try {
            store.save(reservation, new IdempotencyEntry(customerId, idempotencyKey, digest, id));
        } catch (DuplicateIdempotencyKeyException persistenceConflict) {
            Optional<IdempotencyEntry> concurrent = store.findIdempotency(customerId, idempotencyKey);
            if (concurrent.isEmpty()) {
                throw persistenceConflict;
            }
            if (!concurrent.get().requestDigest().equals(digest)) {
                throw new IdempotencyConflictException();
            }
            return toView(store.findById(concurrent.get().reservationId())
                    .orElseThrow(() -> persistenceConflict));
        }
        return toView(reservation);
    }

    public ReservationDetails read(
            com.lowaltitude.inspection.identity.domain.CurrentSubject subject,
            UUID id
    ) {
        StoredReservation value = store.findById(id).orElseThrow(ReservationNotFoundException::new);
        boolean own = subject.subjectId().equals(value.customerId())
                && subject.permissions().contains("inspection.reservation.read.own");
        boolean assigned = subject.roles().contains("PROVIDER_OPERATOR")
                && subject.permissions().contains("inspection.reservation.read.assigned")
                && value.snapshot().providerId().equals(subject.organizationId());
        boolean platform = subject.roles().contains("PLATFORM_OPERATOR")
                && subject.permissions().contains("inspection.reservation.read.all");
        if (!own && !assigned && !platform) {
            throw new ReservationNotFoundException();
        }
        ReservationFlowState flowState = store.findFlowState(id)
                .orElse(new ReservationFlowState("SUBMITTED", null, value.version()));
        return new ReservationDetails(
                value.id(),
                value.reservationNo(),
                value.customerId(),
                value.snapshot(),
                value.cityCode(),
                cipher.decrypt(value.addressCiphertext()),
                value.longitude(),
                value.latitude(),
                value.lineLengthMeters(),
                value.preferredServiceDate(),
                value.preferredTimeSlotCode(),
                value.noteCiphertext() == null ? null : cipher.decrypt(value.noteCiphertext()),
                cipher.decrypt(value.contactNameCiphertext()),
                cipher.decrypt(value.contactMobileCiphertext()),
                value.items(),
                value.agreementVersion(),
                value.submittedAt(),
                flowState.status(),
                flowState.currentPlanId(),
                flowState.version(),
                store.findAttachments(value.id()));
    }

    @Transactional
    public ReservationDetails cancel(
            com.lowaltitude.inspection.identity.domain.CurrentSubject subject,
            UUID reservationId,
            UUID idempotencyKey,
            int expectedVersion,
            String requestId
    ) {
        if (!subject.roles().contains("CUSTOMER")
                || !subject.permissions().contains("inspection.reservation.cancel.own")) {
            throw new ReservationAccessDeniedException(
                    "customer reservation cancellation permission is required");
        }
        StoredReservation reservation = store.findById(reservationId)
                .orElseThrow(ReservationNotFoundException::new);
        if (!reservation.customerId().equals(subject.subjectId())) {
            throw new ReservationNotFoundException();
        }
        String requestDigest = cancellationDigest(reservationId);
        Optional<CancellationIdempotencyEntry> prior =
                store.findCancellationIdempotency(subject.subjectId(), idempotencyKey);
        if (prior.isPresent()) {
            if (!prior.get().requestDigest().equals(requestDigest)) {
                throw new IdempotencyConflictException();
            }
            return read(subject, prior.get().reservationId());
        }
        ReservationFlowState state = store.findFlowState(reservationId)
                .orElse(new ReservationFlowState("SUBMITTED", null, reservation.version()));
        if (state.version() != expectedVersion) {
            throw new ReservationVersionConflictException();
        }
        if ("CANCELLED".equals(state.status())) {
            return read(subject, reservationId);
        }
        if (!"SUBMITTED".equals(state.status())
                && !"PLAN_PUBLISHED".equals(state.status())) {
            throw new ReservationCancellationNotAllowedException();
        }
        CancellationWriteResult result = store.cancel(new CancellationCommand(
                reservationId,
                reservation.customerId(),
                state.status(),
                state.currentPlanId(),
                expectedVersion,
                new CancellationIdempotencyEntry(
                        subject.subjectId(), idempotencyKey, requestDigest, reservationId),
                subject.subjectId(),
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim(),
                clock.instant()));
        if (result == CancellationWriteResult.VERSION_CONFLICT) {
            throw new ReservationVersionConflictException();
        }
        if (result == CancellationWriteResult.STATE_CONFLICT) {
            throw new ReservationCancellationNotAllowedException();
        }
        if (result == CancellationWriteResult.IDEMPOTENCY_EXISTS) {
            CancellationIdempotencyEntry replay =
                    store.findCancellationIdempotency(subject.subjectId(), idempotencyKey)
                            .orElseThrow(IdempotencyConflictException::new);
            if (!replay.requestDigest().equals(requestDigest)) {
                throw new IdempotencyConflictException();
            }
        }
        return read(subject, reservationId);
    }

    public List<InspectionServiceDateAvailabilityView> availableTimeSlots(
            UUID serviceId,
            String cityCode,
            LocalDate startDate,
            int days
    ) {
        if (serviceId == null
                || cityCode == null
                || !cityCode.matches("[A-Z0-9]{4,16}")) {
            throw new IllegalArgumentException("time slot query is invalid");
        }
        InspectionTimeSlotPolicy.validateDateRange(startDate, days, clock);
        services.findActive(serviceId, cityCode)
                .orElseThrow(() -> new ReservationValidationException(
                        "service is unknown or disabled"));
        LocalDate endDate = startDate.plusDays(days - 1L);
        Map<String, Integer> counts = store.findBookedTimeSlotCounts(
                        serviceId, cityCode, startDate, endDate).stream()
                .collect(Collectors.toMap(
                        value -> value.serviceDate() + ":" + value.slotCode(),
                        BookedTimeSlotCount::bookedCount));

        return startDate.datesUntil(endDate.plusDays(1))
                .map(date -> new InspectionServiceDateAvailabilityView(
                        date,
                        InspectionTimeSlotPolicy.slots().stream()
                                .map(slot -> {
                                    int booked = counts.getOrDefault(
                                            date + ":" + slot.code(), 0);
                                    int remaining = Math.max(
                                            0,
                                            InspectionTimeSlotPolicy.CAPACITY - booked);
                                    boolean leadTimeSatisfied = InspectionTimeSlotPolicy.satisfiesLeadTime(
                                            date, slot, clock);
                                    String availability = !leadTimeSatisfied
                                            ? "TOO_SOON"
                                            : remaining == 0 ? "FULL" : "AVAILABLE";
                                    return new InspectionTimeSlotView(
                                            slot.code(),
                                            slot.startTime(),
                                            slot.endTime(),
                                            remaining,
                                            availability);
                                })
                                .toList()))
                .toList();
    }

    private static void validate(CreateReservationCommand command) {
        if (command == null
                || command.serviceId() == null
                || !validText(command.cityCode(), 4, 16)
                || !command.cityCode().matches("[A-Z0-9]{4,16}")
                || !validText(command.address(), 2, MAX_ADDRESS_LENGTH)
                || command.preferredServiceDate() == null
                || command.preferredTimeSlotCode() == null
                || !validItems(command.inspectionItemCodes())
                || !validUniqueIds(command.attachmentFileIds(), MAX_ATTACHMENT_COUNT)
                || !validText(command.contactName(), 1, MAX_CONTACT_NAME_LENGTH)
                || command.contactMobile() == null
                || !command.contactMobile().trim().matches("^1[3-9][0-9]{9}$")
                || !validCoordinates(command.longitude(), command.latitude())
                || command.lineLengthMeters() != null
                        && (command.lineLengthMeters() < 0
                                || command.lineLengthMeters() > MAX_LINE_LENGTH_METERS)
                || command.note() != null && command.note().length() > MAX_NOTE_LENGTH
                || !validText(command.agreementVersion(), 1, 32)
                || !command.agreementAccepted()) {
            throw new ReservationValidationException("reservation request is invalid");
        }
    }

    private static String digest(CreateReservationCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, command.serviceId());
            updateDigest(digest, command.cityCode());
            updateDigest(digest, command.address());
            updateDigest(digest, command.longitude());
            updateDigest(digest, command.latitude());
            updateDigest(digest, command.lineLengthMeters());
            updateDigest(digest, command.preferredServiceDate());
            updateDigest(digest, command.preferredTimeSlotCode());
            updateDigest(digest, command.inspectionItemCodes());
            updateDigest(digest, command.attachmentFileIds());
            updateDigest(digest, command.note());
            updateDigest(digest, command.contactName());
            updateDigest(digest, command.contactMobile());
            updateDigest(digest, command.agreementVersion());
            updateDigest(digest, command.agreementAccepted());
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String cancellationDigest(UUID reservationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(reservationId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void updateDigest(MessageDigest digest, Object value) {
        if (value instanceof Collection<?> collection) {
            updateDigest(digest, collection.size());
            collection.forEach(item -> updateDigest(digest, item));
            return;
        }
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static boolean validItems(List<String> items) {
        return items != null
                && !items.isEmpty()
                && items.size() <= MAX_ITEM_COUNT
                && items.stream().allMatch(item -> validText(item, 1, 64))
                && new HashSet<>(items).size() == items.size();
    }

    private static boolean validUniqueIds(List<UUID> ids, int maxSize) {
        return ids != null
                && ids.size() <= maxSize
                && ids.stream().allMatch(java.util.Objects::nonNull)
                && new HashSet<>(ids).size() == ids.size();
    }

    private static boolean validCoordinates(Double longitude, Double latitude) {
        if ((longitude == null) != (latitude == null)) {
            return false;
        }
        return longitude == null
                || longitude >= -180
                        && longitude <= 180
                        && latitude >= -90
                        && latitude <= 90;
    }

    private static boolean validText(String value, int minLength, int maxLength) {
        if (value == null) {
            return false;
        }
        int length = value.trim().length();
        return length >= minLength && length <= maxLength;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static List<InspectionItemSnapshot> selectSupportedItems(
            InspectionServiceSnapshot service,
            List<String> selectedCodes
    ) {
        Map<String, InspectionItemSnapshot> supported = new LinkedHashMap<>();
        service.inspectionItems().forEach(item -> supported.put(item.code(), item));
        List<InspectionItemSnapshot> selected = selectedCodes.stream()
                .map(supported::get)
                .toList();
        if (selected.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ReservationValidationException(
                    "one or more inspection items are not supported by this service");
        }
        return selected;
    }

    private static ReservationView toView(StoredReservation value) {
        return new ReservationView(value.id(), value.reservationNo(), value.customerId(), value.snapshot().serviceId(),
                value.snapshot().title(), value.snapshot().coverUrl(), value.snapshot().providerId(), value.cityCode(), value.preferredServiceDate(), value.preferredTimeSlotCode());
    }
}

record CreateReservationCommand(UUID serviceId, String cityCode, String address, Double longitude, Double latitude,
                                Long lineLengthMeters, LocalDate preferredServiceDate, String preferredTimeSlotCode, List<String> inspectionItemCodes,
                                List<UUID> attachmentFileIds, String note, String contactName, String contactMobile,
                                String agreementVersion, boolean agreementAccepted) { }
record ReservationView(UUID id, String reservationNo, UUID customerId, UUID serviceId, String serviceTitle,
                       String serviceCoverUrl, UUID providerId, String cityCode, LocalDate preferredServiceDate, String preferredTimeSlotCode) { }
record InspectionServiceSnapshot(
        UUID serviceId,
        UUID providerId,
        String title,
        String coverUrl,
        String cityCode,
        List<InspectionItemSnapshot> inspectionItems
) { }
record InspectionItemSnapshot(String code, String label) { }
record StoredReservation(UUID id, String reservationNo, UUID customerId, InspectionServiceSnapshot snapshot, String cityCode,
                         String addressCiphertext, Double longitude, Double latitude, Long lineLengthMeters,
                         LocalDate preferredServiceDate, String preferredTimeSlotCode, String noteCiphertext, String contactNameCiphertext,
                         String contactMobileCiphertext, List<InspectionItemSnapshot> items, String agreementVersion,
                         Instant submittedAt, int version) { }
record ReservationDetails(UUID id, String reservationNo, UUID customerId, InspectionServiceSnapshot snapshot,
                          String cityCode, String address, Double longitude, Double latitude, Long lineLengthMeters,
                          LocalDate preferredServiceDate, String preferredTimeSlotCode, String note, String contactName, String contactMobile,
                          List<InspectionItemSnapshot> items, String agreementVersion, Instant submittedAt,
                          String status, UUID currentPlanId, int version,
                          List<ReservationFileView> attachments) { }
record ReservationFileView(UUID id, String originalName, String contentType, long sizeBytes,
                           com.lowaltitude.inspection.domain.files.FileObjectStatus status,
                           String failureReason) { }
record IdempotencyEntry(UUID actorId, UUID key, String requestDigest, UUID reservationId) { }
record CancellationIdempotencyEntry(
        UUID actorId,
        UUID key,
        String requestDigest,
        UUID reservationId
) { }
record CancellationCommand(
        UUID reservationId,
        UUID customerId,
        String expectedStatus,
        UUID currentPlanId,
        int expectedVersion,
        CancellationIdempotencyEntry idempotency,
        UUID actorId,
        String requestId,
        Instant cancelledAt
) { }
enum CancellationWriteResult {
    SAVED,
    IDEMPOTENCY_EXISTS,
    VERSION_CONFLICT,
    STATE_CONFLICT
}
interface InspectionServiceCatalog { Optional<InspectionServiceSnapshot> findActive(UUID serviceId, String cityCode); }
interface ReservationStore {
    Optional<IdempotencyEntry> findIdempotency(UUID actorId, UUID key);
    void save(StoredReservation reservation, IdempotencyEntry entry);
    Optional<StoredReservation> findById(UUID id);
    void lockActiveService(UUID serviceId, String cityCode);
    List<BookedTimeSlotCount> findBookedTimeSlotCounts(
            UUID serviceId, String cityCode, LocalDate startDate, LocalDate endDate);
    default Optional<ReservationFlowState> findFlowState(UUID reservationId) {
        return Optional.empty();
    }
    default List<ReservationFileView> findAttachments(UUID reservationId) { return List.of(); }
    default Optional<CancellationIdempotencyEntry> findCancellationIdempotency(
            UUID actorId, UUID key) {
        return Optional.empty();
    }
    default CancellationWriteResult cancel(CancellationCommand command) {
        throw new UnsupportedOperationException("reservation cancellation is not implemented");
    }
}
record BookedTimeSlotCount(LocalDate serviceDate, String slotCode, int bookedCount) { }
record ReservationFlowState(String status, UUID currentPlanId, int version) { }
interface SensitiveDataCipher { String encrypt(String value); String decrypt(String value); default String encryptNullable(String value) { return value == null ? null : encrypt(value); } }
