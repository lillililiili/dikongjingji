package com.lowaltitude.inspection.support.application;

import com.lowaltitude.inspection.support.infrastructure.persistence.SupportMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 客服会话和反馈（G1）。机构、显示名、站内信由启动模块接上（SupportPorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = SupportMapper.class)
public class SupportApplicationConfiguration {

    @Bean
    SupportService supportService(
            SupportMapper mapper,
            SupportPorts.Organizations organizations,
            SupportPorts.Profiles profiles,
            SupportPorts.Notifier notifier
    ) {
        return new SupportService(mapper, organizations, profiles, notifier);
    }

    /** 运营端首页“经营概览”的汇总数（只读）。 */
    @Bean
    SupportOpsStatsService supportOpsStatsService(com.lowaltitude.inspection.support.infrastructure.persistence.SupportOpsStatsMapper mapper) {
        return new SupportOpsStatsService(mapper);
    }
}
