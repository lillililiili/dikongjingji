package com.lowaltitude.inspection.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.live.infrastructure.persistence.LiveOpsMapper;
import com.lowaltitude.inspection.live.infrastructure.persistence.LiveOpsMapper.OpsRoomRow;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;

/** 运营端“直播间”（S3-1 第三部分）：强制结束、回看链接的规则、版本号和改动记录。 */
class LiveOpsServiceTest {

    private static final UUID ROOM = UUID.fromString("f4000000-0000-4000-8000-000000000001");
    private static final UUID HOST = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR = UUID.fromString("90000000-0000-4000-8000-0000000000b1");
    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final LiveOpsMapper mapper = mock(LiveOpsMapper.class);
    private final LiveStreamProvider streams = mock(LiveStreamProvider.class);
    private final LiveOpsService service = new LiveOpsService(mapper, streams, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void forceEndRecordsTheReasonAndStopsTheStream() {
        when(mapper.lockRoom(ROOM)).thenReturn(room("LIVE", 4, null));
        when(mapper.endRoom(ROOM, 4, AT)).thenReturn(1);
        when(mapper.insertChange(eq(ROOM), eq("FORCE_END"), eq("强制结束：画面违规"), eq(OPERATOR), eq("平台运营"),
                eq(AT))).thenReturn(1);
        when(mapper.findRoom(ROOM)).thenReturn(room("ENDED", 5, null));

        var ended = service.forceEnd(ROOM, "  画面违规 ", 4, OPERATOR, "平台运营");

        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.version()).isEqualTo(5);
        verify(streams).stopStream("stream-1");
    }

    @Test
    void forceEndStillEndsWhenTheProviderCannotStopTheStream() {
        when(mapper.lockRoom(ROOM)).thenReturn(room("SCHEDULED", 0, null));
        when(mapper.endRoom(ROOM, 0, AT)).thenReturn(1);
        when(mapper.insertChange(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.findRoom(ROOM)).thenReturn(room("ENDED", 1, null));
        doThrow(new IllegalStateException("provider down")).when(streams).stopStream("stream-1");

        assertThat(service.forceEnd(ROOM, "主办方要求取消", 0, OPERATOR, "平台运营").status()).isEqualTo("ENDED");
    }

    @Test
    void forceEndRejectsStaleVersionsEndedRoomsAndEmptyReasons() {
        when(mapper.lockRoom(ROOM)).thenReturn(room("LIVE", 4, null));
        assertThatThrownBy(() -> service.forceEnd(ROOM, "x", 3, OPERATOR, "平台运营"))
                .extracting(failure -> ((OpsException) failure).error()).isEqualTo(LiveOpsService.Error.VERSION_CONFLICT);
        when(mapper.lockRoom(ROOM)).thenReturn(room("ENDED", 4, null));
        assertThatThrownBy(() -> service.forceEnd(ROOM, "x", 4, OPERATOR, "平台运营"))
                .extracting(failure -> ((OpsException) failure).error()).isEqualTo(LiveOpsService.Error.ROOM_ENDED);
        assertThatThrownBy(() -> service.forceEnd(ROOM, " ", 4, OPERATOR, "平台运营"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.forceEnd(ROOM, "x".repeat(201), 4, OPERATOR, "平台运营"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.forceEnd(ROOM, "x", null, OPERATOR, "平台运营"))
                .isInstanceOf(IllegalArgumentException.class);
        when(mapper.lockRoom(ROOM)).thenReturn(null);
        assertThatThrownBy(() -> service.forceEnd(ROOM, "x", 4, OPERATOR, "平台运营"))
                .extracting(failure -> ((OpsException) failure).error()).isEqualTo(LiveOpsService.Error.NOT_FOUND);
        verify(mapper, never()).endRoom(any(), anyInt(), any());
        verify(streams, never()).stopStream(any());
    }

    @Test
    void replayIsOnlyForEndedRoomsAndTakesBothFieldsOrNeither() {
        when(mapper.lockRoom(ROOM)).thenReturn(room("ENDED", 2, null));
        when(mapper.updateReplay(ROOM, "https://replay.example.com/r/1", "示例平台", 2, AT)).thenReturn(1);
        when(mapper.updateReplay(ROOM, null, null, 2, AT)).thenReturn(1);
        when(mapper.insertChange(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.findRoom(ROOM)).thenReturn(room("ENDED", 3, "https://replay.example.com/r/1"));

        assertThat(service.setReplay(ROOM, " https://replay.example.com/r/1 ", "示例平台", 2, OPERATOR, "平台运营")
                .replayUrl()).isEqualTo("https://replay.example.com/r/1");
        verify(mapper).insertChange(ROOM, "SET_REPLAY", "回看：示例平台 https://replay.example.com/r/1", OPERATOR, "平台运营",
                AT);
        service.setReplay(ROOM, null, "", 2, OPERATOR, "平台运营");
        verify(mapper).insertChange(ROOM, "CLEAR_REPLAY", "去掉回看", OPERATOR, "平台运营", AT);

        for (var bad : List.of(new String[] {"http://replay.example.com/r/1", "示例平台"},
                new String[] {"https://replay.example.com/r/1", null}, new String[] {null, "示例平台"},
                new String[] {"https://replay.example.com/r/1", "名".repeat(21)})) {
            assertThatThrownBy(() -> service.setReplay(ROOM, bad[0], bad[1], 2, OPERATOR, "平台运营"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        when(mapper.lockRoom(ROOM)).thenReturn(room("LIVE", 2, null));
        assertThatThrownBy(() -> service.setReplay(ROOM, "https://replay.example.com/r/1", "示例平台", 2, OPERATOR,
                "平台运营")).extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(LiveOpsService.Error.ROOM_NOT_ENDED);
    }

    @Test
    void listsChangesAndCountsRoomsPerHost() {
        when(mapper.findRoom(ROOM)).thenReturn(room("ENDED", 3, null));
        when(mapper.findChanges(ROOM, LiveOpsService.CHANGE_LIMIT)).thenReturn(List.of(new LiveOpsMapper.ChangeRow(
                UUID.randomUUID(), ROOM, "FORCE_END", "强制结束：画面违规", "平台运营", AT)));
        assertThat(service.changes(ROOM)).singleElement().satisfies(change -> {
            assertThat(change.subjectType()).isEqualTo("LIVE_ROOM");
            assertThat(change.subjectId()).isEqualTo(ROOM);
            assertThat(change.operatorName()).isEqualTo("平台运营");
        });

        UUID other = UUID.fromString("c1000000-0000-4000-8000-000000000002");
        when(mapper.countByHost(List.of(HOST, other))).thenReturn(List.of(new LiveOpsMapper.HostCountRow(HOST, 3, 1, 1)));
        assertThat(service.roomCounts(List.of(HOST, other, HOST))).isEqualTo(Map.of(
                HOST, new LiveOpsService.HostRoomCounts(3, 1, 1), other, new LiveOpsService.HostRoomCounts(0, 0, 0)));
        assertThat(service.roomCounts(List.of())).isEmpty();
    }

    @Test
    void listFiltersAreValidated() {
        when(mapper.countRooms("黄河口", "LIVE", "370500", HOST)).thenReturn(21L);
        when(mapper.findRooms("黄河口", "LIVE", "370500", HOST, 20, 0)).thenReturn(List.of(room("LIVE", 0, null)));
        var page = service.rooms(" 黄河口% ", "live", "370500", HOST, 1, 20);
        assertThat(page.total()).isEqualTo(21);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).singleElement().extracting(LiveOpsService.OpsLiveRoomView::hostName)
                .isEqualTo("东营示例主办");
        assertThatThrownBy(() -> service.rooms(null, "PAUSED", null, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rooms(null, null, "dongying", null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rooms(null, null, null, null, 1, 51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OpsRoomRow room(String status, int version, String replayUrl) {
        boolean ended = "ENDED".equals(status);
        return new OpsRoomRow(ROOM, HOST, "东营示例主办", "黄河口飞行讲解", null, null, "370500", AT.minusHours(1),
                "讲解员", status, "SCHEDULED".equals(status) ? null : AT.minusMinutes(50), ended ? AT : null,
                ended ? "PLATFORM" : null, false, "stream-1", replayUrl, replayUrl == null ? null : "示例平台", 12, 30, 4,
                version, AT.minusDays(1));
    }
}
