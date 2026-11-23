package com.lowaltitude.inspection.proservice.application;

import java.util.ArrayList;
import java.util.List;

import com.lowaltitude.inspection.search.application.SearchPorts;
import org.springframework.stereotype.Component;

/** 全局搜索的四类专业服务：按航拍、测绘、设备维护、技术支持依次用各自目录的关键词搜索，凑满 limit 条。 */
@Component
public class ProServiceSearchFeed implements SearchPorts.ProServices {

    private final ProServiceReadService service;

    public ProServiceSearchFeed(ProServiceReadService service) {
        this.service = service;
    }

    @Override
    public SearchPorts.Found search(String cityCode, String keyword, int limit) {
        long total = 0;
        List<Object> items = new ArrayList<>();
        for (ProServiceType type : ProServiceType.values()) {
            // 已经凑满时只要个数，读一条即可。
            int size = Math.max(1, limit - items.size());
            ProServicePageView page = service.list(type.name(), cityCode, keyword, null, null, null, 1, size);
            total += page.total();
            page.items().stream().limit(limit - items.size()).forEach(items::add);
        }
        return new SearchPorts.Found(total, items);
    }
}
