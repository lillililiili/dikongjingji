package com.lowaltitude.inspection.content.application;

import com.lowaltitude.inspection.content.application.ops.ContentOpsPorts;
import com.lowaltitude.inspection.content.application.ops.ContentOpsService;
import com.lowaltitude.inspection.content.infrastructure.ContentWordListFilter;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentOpsMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 发现页图文内容和专题（H1）、视频（1.2.0）。只读写本模块的表；视频评论要的显示名和举报要记的客服反馈
 * 由启动模块接上（ContentPorts）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ContentMapper.class)
public class ContentApplicationConfiguration {

    @Bean
    ContentReadService contentReadService(ContentMapper mapper) {
        return new ContentReadService(mapper);
    }

    /** 开发用的小词表；接入正式的过滤服务时另给一个 ContentTextFilter 的 bean 即可。 */
    @Bean
    @ConditionalOnMissingBean(ContentTextFilter.class)
    ContentTextFilter contentTextFilter() {
        return new ContentWordListFilter();
    }

    @Bean
    ContentVideoService contentVideoService(
            ContentMapper contentMapper,
            ContentVideoMapper videoMapper,
            ContentPorts.Profiles profiles,
            ContentPorts.Reports reports,
            ContentTextFilter filter
    ) {
        return new ContentVideoService(contentMapper, videoMapper, profiles, reports, filter);
    }

    /** S3-1 运营端“发现内容”：机构、城市、商品由启动模块接上（ContentOpsPortsConfiguration）。 */
    @Bean
    ContentOpsService contentOpsService(
            ContentOpsMapper opsMapper,
            ContentMapper contentMapper,
            ContentOpsPorts.Organizations organizations,
            ContentOpsPorts.Cities cities,
            ContentOpsPorts.Products products
    ) {
        return new ContentOpsService(opsMapper, contentMapper, organizations, cities, products);
    }
}
