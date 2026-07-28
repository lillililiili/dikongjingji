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
        InspectionServiceSnapshot snapshot = services.findActive(command.serviceId(), command.cityCode())
                .orElseThrow(() -> new ReservationValidationException("service is unknown or disabled"));
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
                value.noteCiphertext() == null ? null : cipher.decrypt(value.noteCiphertext()),
                cipher.decrypt(value.contactNameCiphertext()),
                cipher.decrypt(value.contactMobileCiphertext()),
                value.items(),
                value.agreementVersion(),
                value.submittedAt(),
                value.version(),
                store.findAttachments(value.id()));
    }

    private static void validate(CreateReservationCommand command) {
        if (command == null
                || command.serviceId() == null
                || !validText(command.cityCode(), 4, 16)
                || !command.cityCode().matches("[A-Z0-9]{4,16}")
                || !validText(command.address(), 2, MAX_ADDRESS_LENGTH)
                || command.preferredServiceDate() == null
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
                value.snapshot().title(), value.snapshot().coverUrl(), value.snapshot().providerId(), value.cityCode(), value.preferredServiceDate());
    }
}

record CreateReservationCommand(UUID serviceId, String cityCode, String address, Double longitude, Double latitude,
                                Long lineLengthMeters, LocalDate preferredServiceDate, List<String> inspectionItemCodes,
                                List<UUID> attachmentFileIds, String note, String contactName, String contactMobile,
                                String agreementVersion, boolean agreementAccepted) { }
record ReservationView(UUID id, String reservationNo, UUID customerId, UUID serviceId, String serviceTitle,
                       String serviceCoverUrl, UUID providerId, String cityCode, LocalDate preferredServiceDate) { }
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
                         LocalDate preferredServiceDate, String noteCiphertext, String contactNameCiphertext,
                         String contactMobileCiphertext, List<InspectionItemSnapshot> items, String agreementVersion,
                         Instant submittedAt, int version) { }
record ReservationDetails(UUID id, String reservationNo, UUID customerId, InspectionServiceSnapshot snapshot,
                          String cityCode, String address, Double longitude, Double latitude, Long lineLengthMeters,
                          LocalDate preferredServiceDate, String note, String contactName, String contactMobile,
                          List<InspectionItemSnapshot> items, String agreementVersion, Instant submittedAt, int version,
                          List<ReservationFileView> attachments) { }
record ReservationFileView(UUID id, String originalName, String contentType, long sizeBytes,
                           com.lowaltitude.inspection.domain.files.FileObjectStatus status,
                           String failureReason) { }
record IdempotencyEntry(UUID actorId, UUID key, String requestDigest, UUID reservationId) { }
interface InspectionServiceCatalog { Optional<InspectionServiceSnapshot> findActive(UUID serviceId, String cityCode); }
interface ReservationStore {
    Optional<IdempotencyEntry> findIdempotency(UUID actorId, UUID key);
    void save(StoredReservation reservation, IdempotencyEntry entry);
    Optional<StoredReservation> findById(UUID id);
    default List<ReservationFileView> findAttachments(UUID reservationId) { return List.of(); }
}
interface SensitiveDataCipher { String encrypt(String value); String decrypt(String value); default String encryptNullable(String value) { return value == null ? null : encrypt(value); } }
