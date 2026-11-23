package com.lowaltitude.inspection.live.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.lowaltitude.inspection.live.infrastructure.persistence.LiveMapper;

/**
 * LiveMapper 的内存实现，按 LiveMapper 的 SQL 条件和 V001 的约束写（唯一键、按状态的条件更新），给服务的规则测试用。
 * SQL 文本本身由 LiveMapperContractTest 检查。
 */
final class InMemoryLiveMapper implements LiveMapper {

    static final class Room {
        UUID id;
        UUID hostOrgId;
        String hostName;
        RoomFields fields;
        String status = "SCHEDULED";
        OffsetDateTime startedAt;
        OffsetDateTime endedAt;
        String endReason;
        boolean interrupted;
        String streamId;
        String pushUrl;
        String playUrl;
        OffsetDateTime lastEventAt;
        String replayUrl;
        String replayPlatformName;
        Integer currentItemSeq;
        long viewerCount;
        long likeCount;
        long commentCount;
        UUID idempotencyKey;
        String requestHash;
        OffsetDateTime createdAt;
    }

    record Comment(UUID id, UUID roomId, long seq, String side, UUID authorId, String authorName, String body,
                   OffsetDateTime hiddenAt, String hiddenBy, UUID idempotencyKey, String requestHash,
                   OffsetDateTime createdAt) {
    }

    final Map<UUID, Room> rooms = new LinkedHashMap<>();
    final Map<UUID, List<ItemRow>> items = new HashMap<>();
    final Set<String> viewers = new HashSet<>();
    final Map<String, OffsetDateTime> reminders = new LinkedHashMap<>();
    final Set<String> follows = new HashSet<>();
    final Map<String, Integer> likeWindows = new HashMap<>();
    final List<Comment> comments = new ArrayList<>();
    final Set<String> events = new HashSet<>();
    final List<UUID> lockedActors = new ArrayList<>();

    Room room(UUID id) {
        return rooms.get(id);
    }

    @Override
    public Integer lockActor(UUID actorId) {
        lockedActors.add(actorId);
        return 1;
    }

    @Override
    public RoomRow findRoom(UUID roomId) {
        Room room = rooms.get(roomId);
        return room == null ? null : row(room);
    }

    @Override
    public RoomRow lockRoom(UUID roomId) {
        return findRoom(roomId);
    }

    @Override
    public RoomRow lockRoomByStream(String streamId) {
        return rooms.values().stream().filter(room -> room.streamId.equals(streamId)).findFirst().map(this::row)
                .orElse(null);
    }

    @Override
    public RoomRow findRoomByKey(UUID hostOrgId, UUID idempotencyKey) {
        return rooms.values().stream()
                .filter(room -> room.hostOrgId.equals(hostOrgId) && idempotencyKey.equals(room.idempotencyKey))
                .findFirst().map(this::row).orElse(null);
    }

    private Stream<Room> listed(String cityCode, String status, OffsetDateTime staleBefore) {
        return rooms.values().stream()
                .filter(room -> room.fields.cityCode() == null || room.fields.cityCode().equals(cityCode))
                .filter(room -> status == null || room.status.equals(status))
                .filter(room -> !room.status.equals("SCHEDULED") || room.fields.scheduledAt().isAfter(staleBefore))
                .filter(room -> !room.status.equals("ENDED") || room.replayUrl != null)
                .sorted(Comparator.<Room>comparingInt(room -> switch (room.status) {
                            case "LIVE" -> 0;
                            case "SCHEDULED" -> 1;
                            default -> 2;
                        })
                        .thenComparing(room -> switch (room.status) {
                            case "LIVE" -> room.startedAt;
                            case "SCHEDULED" -> room.fields.scheduledAt();
                            default -> null;
                        }, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                        .thenComparing(room -> room.status.equals("ENDED") ? room.endedAt : null,
                                Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder()))
                        .thenComparing(room -> room.id));
    }

    @Override
    public long countListed(String cityCode, String status, OffsetDateTime staleBefore) {
        return listed(cityCode, status, staleBefore).count();
    }

    @Override
    public List<RoomRow> findListed(String cityCode, String status, OffsetDateTime staleBefore, int limit, int offset) {
        return listed(cityCode, status, staleBefore).skip(offset).limit(limit).map(this::row).toList();
    }

    @Override
    public long countHostRooms(UUID hostOrgId, String status) {
        return rooms.values().stream().filter(room -> room.hostOrgId.equals(hostOrgId))
                .filter(room -> status == null || room.status.equals(status)).count();
    }

    @Override
    public List<RoomRow> findHostRooms(UUID hostOrgId, String status, int limit, int offset) {
        return rooms.values().stream().filter(room -> room.hostOrgId.equals(hostOrgId))
                .filter(room -> status == null || room.status.equals(status))
                .sorted(Comparator.comparing((Room room) -> room.createdAt).reversed())
                .skip(offset).limit(limit).map(this::row).toList();
    }

    @Override
    public boolean hostExists(UUID hostOrgId) {
        return rooms.values().stream().anyMatch(room -> room.hostOrgId.equals(hostOrgId));
    }

    @Override
    public int insertRoom(UUID roomId, UUID hostOrgId, String hostName, RoomFields draft, String streamId,
                          String pushUrl, String playUrl, UUID idempotencyKey, String requestHash, OffsetDateTime at) {
        if (rooms.containsKey(roomId) || rooms.values().stream().anyMatch(room -> room.streamId.equals(streamId))) {
            throw new IllegalStateException("duplicate room or stream");
        }
        Room room = new Room();
        room.id = roomId;
        room.hostOrgId = hostOrgId;
        room.hostName = hostName;
        room.fields = draft;
        room.streamId = streamId;
        room.pushUrl = pushUrl;
        room.playUrl = playUrl;
        room.idempotencyKey = idempotencyKey;
        room.requestHash = requestHash;
        room.createdAt = at;
        rooms.put(roomId, room);
        return 1;
    }

    @Override
    public int updatePreview(UUID roomId, RoomFields draft, OffsetDateTime at) {
        Room room = rooms.get(roomId);
        if (room == null || !room.status.equals("SCHEDULED")) {
            return 0;
        }
        room.fields = draft;
        return 1;
    }

    @Override
    public int updateReplay(UUID roomId, String replayUrl, String replayPlatformName, OffsetDateTime at) {
        Room room = rooms.get(roomId);
        room.replayUrl = replayUrl;
        room.replayPlatformName = replayPlatformName;
        return 1;
    }

    @Override
    public int updateCurrentItem(UUID roomId, Integer seq, OffsetDateTime at) {
        rooms.get(roomId).currentItemSeq = seq;
        return 1;
    }

    @Override
    public int updateStatus(UUID roomId, String status, OffsetDateTime startedAt, OffsetDateTime endedAt,
                            String endReason, boolean interrupted, OffsetDateTime lastEventAt, OffsetDateTime at) {
        Room room = rooms.get(roomId);
        // V001 的约束。
        if ((status.equals("ENDED")) != (endedAt != null) || (status.equals("ENDED")) != (endReason != null)
                || (status.equals("LIVE") && startedAt == null) || (!status.equals("LIVE") && interrupted)) {
            throw new IllegalStateException("violates a live_room check");
        }
        room.status = status;
        room.startedAt = startedAt;
        room.endedAt = endedAt;
        room.endReason = endReason;
        room.interrupted = interrupted;
        room.lastEventAt = lastEventAt;
        return 1;
    }

    @Override
    public int addViewer(UUID roomId) {
        Room room = rooms.get(roomId);
        if (!room.status.equals("LIVE")) {
            return 0;
        }
        room.viewerCount++;
        return 1;
    }

    @Override
    public long addLikes(UUID roomId, int count) {
        Room room = rooms.get(roomId);
        room.likeCount += count;
        return room.likeCount;
    }

    @Override
    public long nextCommentSeq(UUID roomId) {
        return ++rooms.get(roomId).commentCount;
    }

    @Override
    public List<ItemRow> findItems(UUID roomId) {
        return List.copyOf(items.getOrDefault(roomId, List.of()));
    }

    @Override
    public int deleteItems(UUID roomId) {
        List<ItemRow> removed = items.remove(roomId);
        return removed == null ? 0 : removed.size();
    }

    @Override
    public int insertItem(UUID roomId, int seq, String type, UUID refId, String title) {
        List<ItemRow> list = items.computeIfAbsent(roomId, id -> new ArrayList<>());
        if (seq < 1 || seq > 10 || list.stream().anyMatch(item -> item.seq() == seq
                || (item.itemType().equals(type) && item.refId().equals(refId)))) {
            throw new IllegalStateException("violates a live_room_item key");
        }
        list.add(new ItemRow(seq, type, refId, title));
        return 1;
    }

    @Override
    public int insertViewer(UUID roomId, UUID viewerKey, OffsetDateTime at) {
        return viewers.add(roomId + ":" + viewerKey) ? 1 : 0;
    }

    @Override
    public int insertReminder(UUID roomId, UUID accountId, OffsetDateTime at) {
        String key = roomId + ":" + accountId;
        if (reminders.containsKey(key)) {
            return 0;
        }
        reminders.put(key, null);
        return 1;
    }

    @Override
    public int deleteReminder(UUID roomId, UUID accountId) {
        String key = roomId + ":" + accountId;
        boolean had = reminders.containsKey(key);
        reminders.remove(key);
        return had ? 1 : 0;
    }

    @Override
    public boolean reminderExists(UUID roomId, UUID accountId) {
        return reminders.containsKey(roomId + ":" + accountId);
    }

    @Override
    public List<UUID> claimReminders(UUID roomId, OffsetDateTime at) {
        List<UUID> claimed = new ArrayList<>();
        reminders.forEach((key, notified) -> {
            if (key.startsWith(roomId + ":") && notified == null) {
                claimed.add(UUID.fromString(key.substring(key.indexOf(':') + 1)));
            }
        });
        claimed.forEach(account -> reminders.put(roomId + ":" + account, at));
        return claimed;
    }

    @Override
    public int insertFollow(UUID accountId, UUID hostOrgId, OffsetDateTime at) {
        return follows.add(accountId + ":" + hostOrgId) ? 1 : 0;
    }

    @Override
    public int deleteFollow(UUID accountId, UUID hostOrgId) {
        return follows.remove(accountId + ":" + hostOrgId) ? 1 : 0;
    }

    @Override
    public boolean followExists(UUID accountId, UUID hostOrgId) {
        return follows.contains(accountId + ":" + hostOrgId);
    }

    @Override
    public int insertLikeWindow(UUID roomId, UUID accountId, OffsetDateTime windowStart) {
        return likeWindows.putIfAbsent(roomId + ":" + accountId + ":" + windowStart.toInstant(), 0) == null ? 1 : 0;
    }

    @Override
    public int lockLikeWindow(UUID roomId, UUID accountId, OffsetDateTime windowStart) {
        return likeWindows.get(roomId + ":" + accountId + ":" + windowStart.toInstant());
    }

    @Override
    public int setLikeWindow(UUID roomId, UUID accountId, OffsetDateTime windowStart, int count) {
        if (count < 0 || count > 30) {
            throw new IllegalStateException("violates live_like_window.like_count");
        }
        likeWindows.put(roomId + ":" + accountId + ":" + windowStart.toInstant(), count);
        return 1;
    }

    @Override
    public CommentRow findCommentByKey(UUID authorId, UUID idempotencyKey) {
        return comments.stream()
                .filter(comment -> Objects.equals(comment.authorId(), authorId)
                        && Objects.equals(comment.idempotencyKey(), idempotencyKey))
                .findFirst().map(InMemoryLiveMapper::row).orElse(null);
    }

    @Override
    public CommentRow findRoomComment(UUID roomId, UUID commentId) {
        return comments.stream().filter(comment -> comment.id().equals(commentId) && comment.roomId().equals(roomId))
                .findFirst().map(InMemoryLiveMapper::row).orElse(null);
    }

    @Override
    public OffsetDateTime lastViewerCommentAt(UUID authorId) {
        return comments.stream().filter(comment -> authorId.equals(comment.authorId()) && comment.side().equals("VIEWER"))
                .map(Comment::createdAt).max(Comparator.naturalOrder()).orElse(null);
    }

    @Override
    public UUID insertComment(UUID roomId, long seq, String side, UUID authorId, String authorName, String body,
                              UUID idempotencyKey, String requestHash, OffsetDateTime at) {
        if (comments.stream().anyMatch(comment -> comment.roomId().equals(roomId) && comment.seq() == seq)
                || body.codePointCount(0, body.length()) > 50) {
            throw new IllegalStateException("violates a live_comment check");
        }
        UUID id = UUID.randomUUID();
        comments.add(new Comment(id, roomId, seq, side, authorId, authorName, body, null, null, idempotencyKey,
                requestHash, at));
        return id;
    }

    @Override
    public List<CommentRow> findComments(UUID roomId, Long since, int limit) {
        return comments.stream()
                .filter(comment -> comment.roomId().equals(roomId) && comment.hiddenAt() == null)
                .filter(comment -> since == null || comment.seq() > since)
                .sorted(Comparator.comparingLong(Comment::seq).reversed())
                .limit(limit).map(InMemoryLiveMapper::row).toList();
    }

    @Override
    public List<Long> findHiddenSeqs(UUID roomId, long fromSeq) {
        return comments.stream()
                .filter(comment -> comment.roomId().equals(roomId) && comment.hiddenAt() != null
                        && comment.seq() > fromSeq)
                .map(Comment::seq).sorted().toList();
    }

    @Override
    public int hideComment(UUID roomId, UUID commentId, String hiddenBy, OffsetDateTime at) {
        for (int index = 0; index < comments.size(); index++) {
            Comment comment = comments.get(index);
            if (comment.id().equals(commentId) && comment.roomId().equals(roomId) && comment.hiddenAt() == null) {
                comments.set(index, new Comment(comment.id(), comment.roomId(), comment.seq(), comment.side(),
                        comment.authorId(), comment.authorName(), comment.body(), at, hiddenBy,
                        comment.idempotencyKey(), comment.requestHash(), comment.createdAt()));
                return 1;
            }
        }
        return 0;
    }

    @Override
    public int insertStreamEvent(String eventId, UUID roomId, String type, OffsetDateTime occurredAt,
                                 OffsetDateTime at) {
        return events.add(eventId) ? 1 : 0;
    }

    private RoomRow row(Room room) {
        ItemRow current = room.currentItemSeq == null ? null : items.getOrDefault(room.id, List.of()).stream()
                .filter(item -> item.seq() == room.currentItemSeq).findFirst().orElse(null);
        RoomFields fields = room.fields;
        return new RoomRow(room.id, room.hostOrgId, room.hostName, fields.title(), fields.subtitle(),
                fields.coverUrl(), fields.cityCode(), fields.scheduledAt(), fields.speakerName(),
                fields.speakerTitle(), fields.topics(), room.status, room.startedAt, room.endedAt, room.interrupted,
                room.streamId, room.pushUrl, room.playUrl, room.lastEventAt, room.replayUrl, room.replayPlatformName,
                room.currentItemSeq, room.viewerCount, room.likeCount, room.commentCount, room.requestHash,
                room.createdAt, current == null ? null : current.itemType(), current == null ? null : current.refId(),
                current == null ? null : current.title());
    }

    private static CommentRow row(Comment comment) {
        return new CommentRow(comment.id(), comment.roomId(), comment.seq(), comment.side(), comment.authorName(),
                comment.body(), comment.hiddenAt(), comment.requestHash(), comment.createdAt());
    }
}
