package com.lowaltitude.inspection.search;

import com.lowaltitude.inspection.search.application.SearchReadService;
import com.lowaltitude.inspection.search.application.SearchResultView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 全局搜索（契约 contracts/openapi-search.yaml），公开只读。 */
@RestController
public class SearchController {

    private final SearchReadService service;

    public SearchController(SearchReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/search")
    public SearchResultView search(
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Integer size
    ) {
        return service.search(cityCode, keyword, scope, size);
    }
}
