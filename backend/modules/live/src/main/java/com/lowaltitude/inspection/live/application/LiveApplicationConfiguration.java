package com.lowaltitude.inspection.live.application;

import com.lowaltitude.inspection.live.infrastructure.persistence.LiveMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 平台内直播（M20）。直播服务、机构、显示名、站内信、客服反馈、关键词过滤由启动模块接上（LivePorts、LiveStreamProvider）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = LiveMapper.class)
public class LiveApplicationConfiguration {

    @Bean
    LiveService liveService(
            LiveMapper mapper,
            LiveStreamProvider streams,
            LivePorts.Organizations organizations,
            LivePorts.Profiles profiles,
            LivePorts.Reminders reminders,
            LivePorts.Reports reports,
            LivePorts.CommentFilter filter
    ) {
        return new LiveService(mapper, streams, organizations, profiles, reminders, reports, filter);
    }

    /** 运营端“直播间”（S3-1 第三部分）。 */
    @Bean
    LiveOpsService liveOpsService(com.lowaltitude.inspection.live.infrastructure.persistence.LiveOpsMapper mapper,
                                  LiveStreamProvider streams) {
        return new LiveOpsService(mapper, streams);
    }
}
