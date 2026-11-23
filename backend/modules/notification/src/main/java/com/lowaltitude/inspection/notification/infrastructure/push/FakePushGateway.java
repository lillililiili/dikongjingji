package com.lowaltitude.inspection.notification.infrastructure.push;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.FakePushDeliveryRow;
import com.lowaltitude.inspection.notification.application.PushGateway;
import com.lowaltitude.inspection.notification.infrastructure.persistence.PushDeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发环境（dev profile，notification.push.provider=fake）的本机模拟推送，不连任何推送服务。
 * 每条“推送”记一行日志（设备标识只打最后 4 位）和一行 notification_push_fake_delivery，
 * 开发控制接口 GET /api/v1/dev/notifications/push-deliveries 读当前账号最近收到的。
 */
public final class FakePushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePushGateway.class);

    private final PushDeviceMapper mapper;
    private final Clock clock;

    public FakePushGateway(PushDeviceMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void send(PushMessage message) {
        mapper.insertFakeDelivery(message.deviceId(), message.accountId(), message.category().name(), message.title(),
                message.body(), message.targetType().name(), message.targetId(), clock.instant());
        log.info("fake push to {} device …{}: [{}] {}", message.platform(), tail(message.clientId()),
                message.category(), message.title());
    }

    /** 当前账号最近收到的模拟推送，新的在前。 */
    public List<FakePushDeliveryRow> recent(UUID accountId, int limit) {
        return mapper.findFakeDeliveries(accountId, limit);
    }

    private static String tail(String clientId) {
        return clientId == null || clientId.length() <= 4 ? "****" : clientId.substring(clientId.length() - 4);
    }
}
