package com.lowaltitude.inspection.search;

import com.lowaltitude.inspection.search.application.SearchKeywordCorrections;
import com.lowaltitude.inspection.search.application.SearchPorts;
import com.lowaltitude.inspection.search.application.SearchReadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局搜索的六个端口接到各模块已有的公开列表（mall、inspection、proservice、activity、experience、content 包里的
 * *SearchFeed）。搜索模块不读它们的表。
 */
@Configuration(proxyBeanMethods = false)
public class SearchPortsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SearchKeywordCorrections searchKeywordCorrections() {
        return SearchKeywordCorrections.defaults();
    }

    @Bean
    SearchReadService searchReadService(
            SearchPorts.Products products,
            SearchPorts.InspectionServices inspectionServices,
            SearchPorts.ProServices proServices,
            SearchPorts.Activities activities,
            SearchPorts.Contents contents,
            SearchPorts.Venues venues,
            SearchKeywordCorrections corrections
    ) {
        return new SearchReadService(products, inspectionServices, proServices, activities, contents, venues,
                corrections);
    }
}
