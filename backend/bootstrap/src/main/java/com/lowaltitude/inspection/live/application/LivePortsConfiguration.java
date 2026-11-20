package com.lowaltitude.inspection.live.application;

import java.util.List;

import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.support.application.SupportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 直播（M20）的端口接到身份模块（机构名、显示名）、客服反馈（举报弹幕）和关键词过滤。
 * 直播提醒的站内信在 notification.application.LiveNotificationsConfiguration 里接（事务提交后写）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class LivePortsConfiguration {

    /** 举报弹幕在客服反馈里的类型：内容有误。 */
    static final String REPORT_FEEDBACK_TYPE = "CONTENT";

    @Bean
    LivePorts.Organizations liveOrganizations(IdentityOrganizationPort organizations) {
        return organizationId -> organizations.findActiveOrganization(organizationId).map(found -> found.name());
    }

    @Bean
    LivePorts.Profiles liveProfiles(IdentityProfilePort profiles) {
        return accountId -> profiles.findProfile(accountId).map(IdentityProfile::displayName);
    }

    @Bean
    LivePorts.Reports liveReports(SupportService support) {
        return reports(support);
    }

    static LivePorts.Reports reports(SupportService support) {
        return (accountId, idempotencyKey, text) -> support.feedback(accountId, idempotencyKey, REPORT_FEEDBACK_TYPE,
                text);
    }

    /** 开发用的小词表（live.comments.blocked-words，逗号分隔）；生产换内容审核服务。 */
    @Bean
    LivePorts.CommentFilter liveCommentFilter(@Value("${live.comments.blocked-words:}") List<String> words) {
        return new KeywordCommentFilter(words);
    }
}
