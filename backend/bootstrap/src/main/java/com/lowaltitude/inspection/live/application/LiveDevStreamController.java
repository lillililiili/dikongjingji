package com.lowaltitude.inspection.live.application;

import java.util.Locale;
import java.util.UUID;

import com.lowaltitude.inspection.live.application.LiveException.Error;
import com.lowaltitude.inspection.live.application.LiveStreamProvider.StreamEventType;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅开发环境：替模拟直播服务“推流”——模拟开播（STARTED）、断流（INTERRUPTED）、结束（ENDED）。
 * 请求由模拟服务签名后走和真实回调同一条验签与处理路径（LiveService.receiveStreamCallback）。
 * 只有这个直播间的主办方（直播主办角色、同一机构）或平台客服能调；没有用模拟服务时一律 404。
 * 不在契约里：生产环境没有这个接口。用法见 infra/scripts/dev-live-host.cjs。
 */
@RestController
@Profile("dev")
public final class LiveDevStreamController {

    private final LiveService service;
    private final LiveStreamProvider streams;

    public LiveDevStreamController(LiveService service, LiveStreamProvider streams) {
        this.service = service;
        this.streams = streams;
    }

    @PostMapping("/api/v1/dev/live/rooms/{roomId}/stream-events")
    public DevStreamResult simulate(Authentication authentication, @PathVariable UUID roomId,
                                    @RequestBody DevStreamRequest request) {
        if (!(streams instanceof FakeLiveStreamProvider fake)) {
            throw new LiveException(Error.NOT_FOUND);
        }
        LiveSubjects.Subject subject = LiveSubjects.of(authentication);
        LiveStreamTarget target = service.streamTarget(roomId);
        boolean host = subject.roles().contains(LiveSubjects.LIVE_HOST)
                && target.hostOrgId().equals(subject.organizationId());
        if (!host && !subject.roles().contains(LiveSubjects.PLATFORM_SUPPORT)) {
            throw new LiveException(Error.NOT_FOUND);
        }
        StreamEventType type;
        try {
            type = StreamEventType.valueOf(request.type() == null ? "" : request.type().trim()
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("type is invalid", unknown);
        }
        String body = fake.eventBody(target.streamId(), type);
        service.receiveStreamCallback(fake.sign(body), body);
        return new DevStreamResult(roomId, type.name());
    }

    /** @param type STARTED / INTERRUPTED / ENDED */
    record DevStreamRequest(String type) {
    }

    record DevStreamResult(UUID roomId, String sent) {
    }
}
