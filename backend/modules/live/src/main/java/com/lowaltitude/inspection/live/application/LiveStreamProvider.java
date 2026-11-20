package com.lowaltitude.inspection.live.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 第三方直播服务（推流、转码、播放）的端口。服务商未定（概要设计 §14 D20）；开发环境由启动模块接一个本机模拟实现，
 * 没有可用服务时接一个“不可用”实现（建不了流、回调一律拒绝）。本模块不依赖任何服务商 SDK。
 */
public interface LiveStreamProvider {

    /** 给这个直播间建一路流。服务商不可用时抛 {@link LiveException}（STREAM_UNAVAILABLE）。 */
    StreamCredentials createStream(UUID roomId);

    /**
     * 验证一条回调并读出事件。签名不对、过期、看不懂时抛 {@link LiveException}（CALLBACK_REJECTED）。
     *
     * @param headers 请求头，名字一律小写
     * @param body    原样的请求体
     */
    StreamEvent verifyCallback(Map<String, String> headers, String body);

    /** 平台客服强制结束：让服务商断掉这路流。失败不影响结束本身。 */
    void stopStream(String streamId);

    /**
     * @param streamId 服务商的流 ID，回调按它找直播间
     * @param pushUrl  推流地址，只给主办方
     * @param playUrl  播放地址，只在直播中给观众
     */
    record StreamCredentials(String streamId, String pushUrl, String playUrl) {
    }

    record StreamEvent(String eventId, String streamId, StreamEventType type, Instant occurredAt) {
    }

    enum StreamEventType {
        STARTED,
        INTERRUPTED,
        ENDED
    }
}
