package com.lowaltitude.inspection.live.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.live.infrastructure.persistence.LiveOpsMapper;
import com.lowaltitude.inspection.live.infrastructure.persistence.LiveOpsMapper.OpsRoomRow;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsError;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“直播间”（S3-1 第三部分，契约 contracts/openapi-ops-live.yaml 1.0.0）：只给平台运营，角色由控制器检查。
 * <ul>
 *   <li>看全部直播间（不分主办机构、不分城市），按关键词、状态、城市、主办机构筛。</li>
 *   <li>强制结束：写原因，带版本号；已结束的不能再结束。服务商那边的流尽量断掉，断不掉不影响结束（直播间不再给播放地址）。
 *       平台客服原有的 /api/v1/platform/live/rooms/{id}/end 不变。</li>
 *   <li>回看链接：只给已结束的设或去掉，带版本号；地址只收受控 https，平台名称 1–20 字，两项一起给或一起不给。</li>
 *   <li>每次改动在 live_ops_change 留一条（操作人显示名快照、时间、说明）。</li>
 * </ul>
 * 开通直播主办（LIVE_HOST）不在这里：运营在“机构与资质”里给账号改角色。
 */
public class LiveOpsService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_KEYWORD = 20;
    public static final int MAX_NOTE = 200;
    public static final int MAX_PLATFORM_NAME = LiveService.MAX_PLATFORM_NAME;
    public static final int CHANGE_LIMIT = 50;

    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");

    private final LiveOpsMapper mapper;
    private final LiveStreamProvider streams;
    private final Clock clock;

    public LiveOpsService(LiveOpsMapper mapper, LiveStreamProvider streams) {
        this(mapper, streams, Clock.systemUTC());
    }

    LiveOpsService(LiveOpsMapper mapper, LiveStreamProvider streams, Clock clock) {
        this.mapper = mapper;
        this.streams = streams;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OpsLiveRoomPageView rooms(String keyword, String status, String cityCode, UUID hostOrganizationId,
                                     int page, int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        String filterKeyword = OpsText.keyword(keyword, MAX_KEYWORD);
        LiveRoomStatus filterStatus = OpsText.optionalEnum(LiveRoomStatus.class, status, "status");
        String filterCity = cityCode == null || cityCode.isBlank() ? null : city(cityCode);
        String statusName = filterStatus == null ? null : filterStatus.name();
        long total = mapper.countRooms(filterKeyword, statusName, filterCity, hostOrganizationId);
        List<OpsLiveRoomView> items = mapper.findRooms(filterKeyword, statusName, filterCity, hostOrganizationId, size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(LiveOpsService::view)
                .toList();
        return new OpsLiveRoomPageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public OpsLiveRoomView room(UUID roomId) {
        return view(existing(mapper.findRoom(requireId(roomId))));
    }

    @Transactional
    public OpsLiveRoomView forceEnd(UUID roomId, String note, Integer version, UUID operatorId, String operatorName) {
        String reason = OpsText.required(note, MAX_NOTE, "note");
        int expected = version(version);
        String operator = operator(operatorId, operatorName);
        OpsRoomRow room = existing(mapper.lockRoom(requireId(roomId)));
        if (room.version() != expected) {
            throw new OpsException(Error.VERSION_CONFLICT);
        }
        if (LiveRoomStatus.ENDED.name().equals(room.status())) {
            throw new OpsException(Error.ROOM_ENDED);
        }
        OffsetDateTime at = now();
        if (mapper.endRoom(room.id(), expected, at) != 1) {
            throw new OpsException(Error.VERSION_CONFLICT);
        }
        record(room.id(), "FORCE_END", "强制结束：" + reason, operatorId, operator, at);
        try {
            streams.stopStream(room.streamId());
        } catch (RuntimeException ignored) {
            // 断流失败不影响结束：直播间已经不给播放地址，服务商那边的流会在超时后自己断。
        }
        return view(mapper.findRoom(room.id()));
    }

    @Transactional
    public OpsLiveRoomView setReplay(UUID roomId, String replayUrl, String platformName, Integer version,
                                     UUID operatorId, String operatorName) {
        String url = OpsText.httpsUrl(replayUrl, "replayUrl");
        String name = OpsText.optional(platformName, MAX_PLATFORM_NAME, "replayPlatformName");
        if ((url == null) != (name == null)) {
            throw new IllegalArgumentException("replay url and platform go together");
        }
        int expected = version(version);
        String operator = operator(operatorId, operatorName);
        OpsRoomRow room = existing(mapper.lockRoom(requireId(roomId)));
        if (room.version() != expected) {
            throw new OpsException(Error.VERSION_CONFLICT);
        }
        if (!LiveRoomStatus.ENDED.name().equals(room.status())) {
            throw new OpsException(Error.ROOM_NOT_ENDED);
        }
        OffsetDateTime at = now();
        if (mapper.updateReplay(room.id(), url, name, expected, at) != 1) {
            throw new OpsException(Error.VERSION_CONFLICT);
        }
        if (url == null) {
            record(room.id(), "CLEAR_REPLAY", "去掉回看", operatorId, operator, at);
        } else {
            record(room.id(), "SET_REPLAY", "回看：" + name + " " + url, operatorId, operator, at);
        }
        return view(mapper.findRoom(room.id()));
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> changes(UUID roomId) {
        existing(mapper.findRoom(requireId(roomId)));
        return mapper.findChanges(roomId, CHANGE_LIMIT).stream()
                .map(row -> new OpsChangeView(row.id(), "LIVE_ROOM", row.roomId(), row.action(), row.summary(),
                        row.operatorName(), row.changedAt()))
                .toList();
    }

    /** 这些主办机构的直播间数（全部、直播中、预告）；没有直播间的给 0。 */
    @Transactional(readOnly = true)
    public Map<UUID, HostRoomCounts> roomCounts(Collection<UUID> hostOrganizationIds) {
        List<UUID> ids = hostOrganizationIds == null ? List.of()
                : hostOrganizationIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, HostRoomCounts> counts = new HashMap<>();
        ids.forEach(id -> counts.put(id, new HostRoomCounts(0, 0, 0)));
        if (!ids.isEmpty()) {
            mapper.countByHost(ids).forEach(row -> counts.put(row.hostOrgId(),
                    new HostRoomCounts(row.roomCount(), row.liveCount(), row.scheduledCount())));
        }
        return Map.copyOf(counts);
    }

    private void record(UUID roomId, String action, String summary, UUID operatorId, String operatorName,
                        OffsetDateTime at) {
        if (mapper.insertChange(roomId, action, OpsText.clip(summary, 600), operatorId, operatorName, at) != 1) {
            throw new IllegalStateException("live ops change was not recorded");
        }
    }

    private static OpsLiveRoomView view(OpsRoomRow row) {
        return new OpsLiveRoomView(row.id(), row.hostOrgId(), row.hostName(), row.title(), row.subtitle(),
                row.coverUrl(), row.cityCode(), row.scheduledAt(), row.speakerName(), row.status(), row.startedAt(),
                row.endedAt(), row.endReason(), row.streamInterrupted(), row.replayUrl(), row.replayPlatformName(),
                row.viewerCount(), row.likeCount(), row.commentCount(), row.version(), row.createdAt());
    }

    private static OpsRoomRow existing(OpsRoomRow row) {
        if (row == null) {
            throw new OpsException(Error.NOT_FOUND);
        }
        return row;
    }

    private static UUID requireId(UUID roomId) {
        if (roomId == null) {
            throw new OpsException(Error.NOT_FOUND);
        }
        return roomId;
    }

    private static int version(Integer version) {
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
        return version;
    }

    private static String operator(UUID operatorId, String operatorName) {
        if (operatorId == null) {
            throw new IllegalArgumentException("operator is required");
        }
        return OpsText.operatorName(operatorName);
    }

    private static String city(String code) {
        if (!CITY_CODE.matcher(code.strip()).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return code.strip();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** 一个主办机构的直播间数。 */
    public record HostRoomCounts(long roomCount, long liveCount, long scheduledCount) {
    }

    /**
     * 运营端看到的直播间（契约 OpsLiveRoom）。status：SCHEDULED 预告、LIVE 直播中、ENDED 已结束；
     * endReason：STREAM 服务商回调结束、PLATFORM 平台结束，没结束为 null。不含推流、播放地址。
     */
    public record OpsLiveRoomView(
            UUID roomId,
            UUID hostOrganizationId,
            String hostName,
            String title,
            String subtitle,
            String coverUrl,
            String cityCode,
            OffsetDateTime scheduledAt,
            String speakerName,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            String endReason,
            boolean streamInterrupted,
            String replayUrl,
            String replayPlatformName,
            long viewerCount,
            long likeCount,
            long commentCount,
            int version,
            OffsetDateTime createdAt
    ) {
    }

    public record OpsLiveRoomPageView(int page, int size, long total, boolean hasMore, List<OpsLiveRoomView> items) {
    }

    /** 契约 OpsLiveErrorCode。 */
    public enum Error implements OpsError {
        NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
        VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试"),
        ROOM_ENDED(409, "OPS_LIVE_ROOM_ENDED", "直播间已经结束"),
        ROOM_NOT_ENDED(409, "OPS_LIVE_ROOM_NOT_ENDED", "直播结束后才能设回看");

        private final int status;
        private final String code;
        private final String message;

        Error(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        @Override
        public int httpStatus() {
            return status;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
