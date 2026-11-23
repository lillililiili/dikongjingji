package com.lowaltitude.inspection.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 全局搜索要读的六个列表（契约 contracts/openapi-search.yaml）。搜索模块不读别的模块的表，
 * 这些端口在 bootstrap 里接到各模块已有的公开列表读取；items 的每一项就是那个列表自己的卡片。
 */
public final class SearchPorts {

    private SearchPorts() {
    }

    /** 一组结果：total 是找到的总数，items 最多 limit 条。 */
    public record Found(long total, List<?> items) {

        public Found {
            items = List.copyOf(items);
        }

        public static Found empty() {
            return new Found(0, List.of());
        }
    }

    /** 每个端口都按同一个约定：cityCode、keyword 已经校验过，limit 为 1–20。 */
    @FunctionalInterface
    public interface Reader {
        Found search(String cityCode, String keyword, int limit);
    }

    /** 商城商品（GET /api/v1/mall/products 的关键词搜索）。 */
    public interface Products extends Reader {
    }

    /** 巡检服务（GET /api/v1/inspection/services 的关键词搜索）。 */
    public interface InspectionServices extends Reader {
    }

    /** 航拍、测绘、设备维护、技术支持四类专业服务（GET /api/v1/pro-services，四类依次）。 */
    public interface ProServices extends Reader {
    }

    /** 活动（GET /api/v1/activities，按名称和场地名匹配）。 */
    public interface Activities extends Reader {
    }

    /** 场馆（GET /api/v1/venues，按名称和地址匹配）。 */
    public interface Venues extends Reader {
    }

    /** 发现内容（GET /api/v1/content/feed 推荐频道，按标题和发布方匹配）。 */
    public interface Contents extends Reader {
    }

    /** 一页列表：给没有关键词参数的列表逐页看。 */
    public record Page<T>(List<T> items, boolean hasMore) {
    }

    @FunctionalInterface
    public interface PageReader<T> {
        Page<T> read(int page, int size);
    }

    /** 逐页看这么多条为止（活动、场馆、内容的本城列表很短，一期够用）。 */
    public static final int SCAN_PAGE_SIZE = 50;
    public static final int SCAN_MAX_ITEMS = 200;

    /**
     * 给没有关键词参数的列表：从第一页起逐页读，最多看 {@link #SCAN_MAX_ITEMS} 条，
     * 留下 matches 的；total 是看过的那部分里的命中数，items 取前 limit 条，顺序和列表一致。
     */
    public static <T> Found scan(PageReader<T> reader, Predicate<T> matches, int limit) {
        List<T> hits = new ArrayList<>();
        long total = 0;
        int seen = 0;
        for (int page = 1; seen < SCAN_MAX_ITEMS; page += 1) {
            Page<T> loaded = reader.read(page, SCAN_PAGE_SIZE);
            for (T item : loaded.items()) {
                if (seen >= SCAN_MAX_ITEMS) {
                    break;
                }
                seen += 1;
                if (matches.test(item)) {
                    total += 1;
                    if (hits.size() < limit) {
                        hits.add(item);
                    }
                }
            }
            if (!loaded.hasMore() || loaded.items().isEmpty()) {
                break;
            }
        }
        return new Found(total, hits);
    }

    /** 包含匹配：去掉全部空白、不分大小写（输入“3分钟”也能找到“3 分钟看懂编程机器人”）；任一字段命中即可。 */
    public static boolean contains(String keyword, String... fields) {
        String needle = normalize(keyword);
        if (needle.isEmpty()) {
            return false;
        }
        for (String field : fields) {
            if (field != null && normalize(field).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
