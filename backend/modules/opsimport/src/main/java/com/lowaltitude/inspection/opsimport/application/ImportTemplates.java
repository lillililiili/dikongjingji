package com.lowaltitude.inspection.opsimport.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.net.URI;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 31 张导入模板（infra/import-templates/，填表规则 docs/ops/首批资源导入.md §3）的列和每列自己的规则：必填、长度、枚举、
 * 整数、金额（分）、北京时间、https 地址等。表头必须和这里一字不差（ImportTemplatesTest 对照仓库里的模板文件）。
 * 跨列、跨表、要查库的规则在 {@link ImportValidator}。
 */
final class ImportTemplates {

    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    static final long MAX_AMOUNT_MINOR = 10_000_000_000L;

    private ImportTemplates() {
    }

    /** 一列的规则：值已经去掉首尾空白、不是空的；不合格返回给人看的说明，合格返回 null。 */
    interface Rule {
        String check(String value);
    }

    record Column(String name, boolean required, Rule rule) {
    }

    record FileTemplate(String baseName, String title, boolean required, List<Column> columns) {

        String fileName() {
            return baseName + ".csv";
        }

        List<String> header() {
            return columns.stream().map(Column::name).toList();
        }

        Optional<Column> column(String name) {
            return columns.stream().filter(column -> column.name().equals(name)).findFirst();
        }
    }

    record KindTemplate(OpsImportKind kind, List<FileTemplate> files) {
    }

    // ---- 规则 ----

    static Rule text(int max) {
        return value -> {
            if (value.codePointCount(0, value.length()) > max) {
                return "最多 " + max + " 个字";
            }
            if (value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
                return "有看不见的控制字符";
            }
            return null;
        };
    }

    static Rule pattern(String regex, String hint) {
        Pattern compiled = Pattern.compile(regex);
        return value -> compiled.matcher(value).matches() ? null : hint;
    }

    static Rule oneOf(String... values) {
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(values));
        return value -> allowed.contains(value) ? null : "只能填 " + String.join(" / ", allowed);
    }

    /** 用 | 分隔的一组值，每个都在 values 里、不重复，最多 max 个。 */
    static Rule listOf(int max, String... values) {
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(values));
        return value -> {
            List<String> parts = split(value);
            if (parts.isEmpty() || parts.size() > max) {
                return "用 | 分隔，1–" + max + " 个";
            }
            if (new LinkedHashSet<>(parts).size() != parts.size()) {
                return "有重复的值";
            }
            for (String part : parts) {
                if (!allowed.contains(part)) {
                    return "只能填 " + String.join(" / ", allowed) + "（用 | 分隔）";
                }
            }
            return null;
        };
    }

    /** 用 | 分隔的一组短文字（卖点标签），最多 max 个、每个最多 maxLength 字、不重复。 */
    static Rule textList(int max, int maxLength) {
        return value -> {
            List<String> parts = split(value);
            if (parts.isEmpty() || parts.size() > max) {
                return "用 | 分隔，1–" + max + " 个";
            }
            if (new LinkedHashSet<>(parts).size() != parts.size()) {
                return "有重复的标签";
            }
            for (String part : parts) {
                if (part.isEmpty() || part.codePointCount(0, part.length()) > maxLength) {
                    return "每个 1–" + maxLength + " 个字";
                }
            }
            return null;
        };
    }

    static Rule integer(long min, long max) {
        return value -> {
            if (!value.matches("^-?[0-9]{1,12}$")) {
                return "要填整数";
            }
            long number = Long.parseLong(value);
            return number < min || number > max ? "要在 " + min + "–" + max + " 之间" : null;
        };
    }

    /** 金额：整数，单位分，不能是负数，不能带小数点和“元”。 */
    static Rule money() {
        return value -> {
            if (!value.matches("^[0-9]{1,11}$")) {
                return value.contains(".") || value.contains("元")
                        ? "金额填整数分（19.90 元填 1990），不要带小数点和“元”"
                        : "金额填不小于 0 的整数，单位分";
            }
            return Long.parseLong(value) > MAX_AMOUNT_MINOR ? "金额太大" : null;
        };
    }

    static Rule bool() {
        return oneOf("true", "false");
    }

    static Rule dateTime() {
        return value -> {
            try {
                LocalDateTime.parse(value, DATE_TIME);
                return null;
            } catch (DateTimeParseException invalid) {
                return "时间写成北京时间 2026-12-06 09:00";
            }
        };
    }

    static Rule date() {
        return value -> {
            try {
                LocalDate.parse(value, DATE);
                return null;
            } catch (DateTimeParseException invalid) {
                return "日期写成 2026-12-06";
            }
        };
    }

    /** 受控的 https 地址：带主机名，≤500 字。 */
    static Rule https() {
        return value -> {
            if (value.length() > 500) {
                return "地址最多 500 个字符";
            }
            try {
                URI uri = URI.create(value);
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank()) {
                    return null;
                }
            } catch (IllegalArgumentException invalid) {
                // 下面统一说明。
            }
            return "只填受控的 https 地址；没有授权图就空着";
        };
    }

    static Rule decimal(double min, double max, String what) {
        return value -> {
            try {
                BigDecimal number = new BigDecimal(value);
                if (number.compareTo(BigDecimal.valueOf(min)) < 0 || number.compareTo(BigDecimal.valueOf(max)) > 0) {
                    return what + "要在 " + min + "–" + max + " 之间（WGS84 十进制度）";
                }
                return null;
            } catch (NumberFormatException invalid) {
                return what + "要填十进制度数，例如 118.674";
            }
        };
    }

    static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\|", -1)).map(String::strip).toList();
    }

    // ---- 常用列 ----

    static final Rule ORG_CODE = pattern("^[A-Z0-9-]{1,64}$", "大写字母、数字和 -，最多 64 位");
    static final Rule REF = pattern("^[A-Za-z0-9_.-]{1,64}$", "编号用字母、数字、_ . -，最多 64 位");
    static final Rule CITY = pattern("^[0-9]{6}$", "城市编码是 6 位数字（东营 370500）");
    static final Rule SORT = integer(0, 9999);
    static final Rule SEQUENCE = integer(1, 9999);
    static final Rule LONGITUDE = decimal(-180, 180, "经度");
    static final Rule LATITUDE = decimal(-90, 90, "纬度");

    static Column req(String name, Rule rule) {
        return new Column(name, true, rule);
    }

    static Column opt(String name, Rule rule) {
        return new Column(name, false, rule);
    }

    static FileTemplate file(String baseName, String title, boolean required, Column... columns) {
        return new FileTemplate(baseName, title, required, List.of(columns));
    }

    // ---- 31 张表 ----

    static final FileTemplate ORGANIZATIONS = file("01-organizations", "经营主体", true,
            req("organization_code", ORG_CODE),
            req("organization_name", text(160)),
            req("organization_type", oneOf("PROVIDER")),
            req("business_roles", listOf(5, "MERCHANT", "ORGANIZER", "VENUE_OPERATOR", "INSPECTION_PROVIDER",
                    "SERVICE_PROVIDER")),
            req("credit_code", pattern("^[0-9A-HJ-NPQRTUWXY]{18}$",
                    "统一社会信用代码是 18 位数字或大写字母（不含 I、O、Z、S、V）")),
            opt("qualification_reviewed_on", date()),
            req("status", oneOf("ACTIVE", "DISABLED")));

    static final FileTemplate OPERATORS = file("01b-organization-operators", "经营主体的操作员账号", false,
            req("organization_code", ORG_CODE),
            req("login_name", pattern("^[a-z][a-z0-9_-]{3,31}$",
                    "登录名小写字母开头，4–32 位小写字母、数字、_ 或 -")),
            req("display_name", text(40)),
            req("role_code", oneOf("PROVIDER_OPERATOR", "LIVE_HOST")));

    static final FileTemplate MERCHANT_ACCOUNTS = file("02-merchant-payment-accounts", "商家收款账户", true,
            req("organization_code", ORG_CODE),
            req("channel", oneOf("WECHAT")),
            req("sub_mchid", pattern("^[0-9]{8,32}$", "二级商户号是 8–32 位数字")),
            req("active", bool()));

    static final FileTemplate PRODUCTS = file("03-mall-products", "商品", true,
            req("product_ref", REF),
            req("category", oneOf("DRONE", "ROBOT", "PARTS", "RENTAL", "COURSE")),
            req("name", text(160)),
            opt("summary", text(300)),
            opt("cover_url", https()),
            req("sale_mode", oneOf("BUY", "RENT", "INQUIRY")),
            opt("experience_badge", oneOf("ONLINE_EXPERIENCE", "VENUE_DISPLAY")),
            opt("selling_points", textList(5, 12)),
            opt("sort_order", SORT),
            req("status", oneOf("DRAFT", "PUBLISHED", "OFFLINE")));

    static final FileTemplate PRODUCT_SPECS = file("03b-mall-product-specs", "商品参数", false,
            req("product_ref", REF),
            req("label", text(40)),
            req("value", text(200)),
            opt("sort_order", SORT));

    static final FileTemplate PACK_ITEMS = file("03c-mall-pack-items", "包装清单", false,
            req("product_ref", REF),
            req("name", text(80)),
            req("quantity", integer(1, 999)),
            opt("sort_order", SORT));

    static final FileTemplate SKUS = file("03d-mall-skus", "规格", true,
            req("product_ref", REF),
            req("sku_ref", REF),
            req("name", text(80)),
            opt("sort_order", SORT));

    static final FileTemplate OFFERS = file("03e-mall-offers", "在售供给", false,
            req("sku_ref", REF),
            req("merchant_organization_code", ORG_CODE),
            req("merchant_kind", oneOf("FACTORY_DIRECT", "AUTHORIZED_DEALER", "LOCAL_STORE")),
            opt("city_code", CITY),
            opt("price_amount_minor", money()),
            req("price_unit", oneOf("ITEM", "DAY", "HALF_DAY")),
            opt("stock_quantity", integer(0, 999_999)),
            req("express_available", bool()),
            req("shipping_fee_minor", money()),
            opt("pickup_point", text(120)),
            opt("delivery_note", text(200)),
            opt("shipping_note", text(200)),
            opt("fulfilled_from", text(120)),
            req("status", oneOf("ACTIVE", "DISABLED")));

    static final FileTemplate ACTIVITIES = file("04-activities", "活动", true,
            req("activity_ref", REF),
            req("organizer_organization_code", ORG_CODE),
            opt("co_organizer_organization_code", ORG_CODE),
            req("type", oneOf("CONTEST", "TRAINING", "STUDY_TOUR", "OPEN_DAY")),
            req("title", text(160)),
            opt("subtitle", text(200)),
            opt("cover_url", https()),
            req("city_code", CITY),
            req("venue_name", text(120)),
            req("venue_address", text(240)),
            opt("venue_longitude", LONGITUDE),
            opt("venue_latitude", LATITUDE),
            opt("schedule_note", text(120)),
            req("price_mode", oneOf("FREE", "PER_PERSON", "INQUIRY")),
            opt("price_amount_minor", money()),
            opt("min_age", integer(0, 99)),
            opt("max_age", integer(0, 99)),
            opt("eligibility_note", text(300)),
            opt("seat_note", text(300)),
            opt("refund_note", text(500)),
            req("status", oneOf("DRAFT", "PUBLISHED", "OFFLINE")));

    static final FileTemplate ACTIVITY_SESSIONS = file("04b-activity-sessions", "活动场次", true,
            req("activity_ref", REF),
            opt("label", text(60)),
            req("starts_at", dateTime()),
            opt("ends_at", dateTime()),
            opt("check_in_opens_at", dateTime()),
            req("enrollment_closes_at", dateTime()),
            req("capacity", integer(1, 100_000)),
            req("status", oneOf("ACTIVE", "CANCELLED")));

    static final FileTemplate VENUES = file("05-venues", "线下场馆", true,
            req("venue_ref", REF),
            req("operator_organization_code", ORG_CODE),
            req("name", text(120)),
            opt("cover_url", https()),
            req("city_code", CITY),
            req("address", text(240)),
            opt("longitude", LONGITUDE),
            opt("latitude", LATITUDE),
            opt("phone", pattern("^[0-9+\\- ]{5,32}$", "对外咨询电话，数字、+、- 和空格，5–32 位")),
            opt("opening_note", text(120)),
            opt("sort_order", SORT),
            req("status", oneOf("DRAFT", "PUBLISHED", "OFFLINE")));

    static final FileTemplate VENUE_INFO = file("05b-venue-info-items", "到馆须知等条目", false,
            req("venue_ref", REF),
            req("label", text(40)),
            req("content", text(200)),
            opt("sort_order", SORT));

    static final FileTemplate VENUE_PROJECTS = file("05c-venue-projects", "可预约项目", false,
            req("venue_ref", REF),
            req("project_ref", REF),
            req("name", text(120)),
            req("duration_minutes", integer(1, 1440)),
            opt("min_age", integer(0, 99)),
            req("capacity", integer(1, 100_000)),
            opt("limit_note", text(200)),
            req("price_amount_minor", money()),
            opt("sort_order", SORT),
            req("status", oneOf("ACTIVE", "DISABLED")));

    static final FileTemplate VENUE_SESSIONS = file("05d-venue-sessions", "项目场次", false,
            req("project_ref", REF),
            req("starts_at", dateTime()),
            req("ends_at", dateTime()),
            req("capacity", integer(1, 100_000)),
            req("status", oneOf("ACTIVE", "CANCELLED")));

    static final FileTemplate VENUE_DEVICES = file("05e-venue-devices", "馆内设备", false,
            req("venue_ref", REF),
            req("category", oneOf("ROBOT", "DRONE", "PARTS")),
            req("name", text(120)),
            opt("summary", text(200)),
            opt("image_url", https()),
            req("trial_mode", oneOf("ACCOMPANIED_TRIAL", "DISPLAY_ONLY")),
            opt("product_ref", REF),
            opt("sort_order", SORT),
            req("status", oneOf("ACTIVE", "DISABLED")));

    static final FileTemplate EXPERIENCE_ITEMS = file("05f-experience-items", "线上体验项目", false,
            req("type", oneOf("SIMULATION", "RECORDED_DEMO", "SOFTWARE_TRIAL", "COURSE_TRIAL")),
            req("title", text(120)),
            opt("summary", text(200)),
            opt("cover_url", https()),
            req("login_required", bool()),
            opt("product_ref", REF),
            opt("sort_order", SORT),
            req("status", oneOf("DRAFT", "PUBLISHED", "OFFLINE")));

    static final FileTemplate PROVIDER_PROFILES = file("06-inspection-provider-profiles", "巡检服务商资料", false,
            req("provider_organization_code", ORG_CODE),
            opt("logo_url", https()),
            opt("certification_label", text(80)),
            opt("team_description", text(240)));

    static final FileTemplate INSPECTION_SERVICES = file("06b-inspection-services", "巡检服务", false,
            req("service_ref", REF),
            req("provider_organization_code", ORG_CODE),
            opt("service_no", text(64)),
            req("title", text(160)),
            req("category_code", pattern("^[A-Z][A-Z_]{1,31}$", "巡检场景编码，大写字母和 _（POWER、OIL_GAS 等）")),
            opt("summary", text(500)),
            opt("cover_url", https()),
            req("city_code", CITY),
            req("price_mode", oneOf("STARTING_PRICE", "PER_DISTANCE", "PER_VISIT", "PROJECT_QUOTE")),
            opt("starting_amount_minor", money()),
            opt("unit_code", pattern("^[A-Z][A-Z_]{0,23}$", "计价单位编码，大写字母和 _（KM、VISIT）")),
            opt("unit_label", text(20)),
            opt("price_note", text(240)),
            req("availability", oneOf("AVAILABLE", "RESERVABLE", "UNAVAILABLE")),
            req("primary_action", oneOf("RESERVE", "REQUEST_QUOTE", "CONSULT", "NONE")),
            opt("cancellation_note", text(500)),
            req("includes_report", bool()),
            req("status", oneOf("ACTIVE", "DISABLED")));

    static final FileTemplate INSPECTION_CITIES = file("06c-inspection-service-cities", "巡检服务的可服务城市", false,
            req("service_ref", REF),
            req("city_code", CITY),
            req("center_longitude", LONGITUDE),
            req("center_latitude", LATITUDE));

    static final FileTemplate INSPECTION_METHODS = file("06d-inspection-service-methods", "巡检作业方式", false,
            req("service_ref", REF),
            req("method_code", oneOf("UAV", "ROBOT")));

    static final FileTemplate INSPECTION_DETAIL_ITEMS = file("06e-inspection-service-detail-items",
            "巡检详情的能力、流程、交付物、保障、价格因素", false,
            req("service_ref", REF),
            req("section_type", oneOf("CAPABILITY", "PROCESS", "DELIVERABLE", "GUARANTEE", "PRICE_FACTOR")),
            req("item_code", pattern("^[A-Z][A-Z0-9_]{0,63}$", "大写字母、数字和 _")),
            req("title", text(120)),
            opt("description", text(240)),
            opt("image_url", https()),
            opt("display_order", SORT));

    static final FileTemplate INSPECTION_BOOKING_ITEMS = file("06f-inspection-booking-items", "巡检预约可选项目", false,
            req("service_ref", REF),
            req("item_code", pattern("^[A-Za-z0-9_]{1,64}$", "字母、数字和 _")),
            req("item_label", text(80)),
            opt("description", text(240)),
            opt("display_order", SORT));

    static final FileTemplate PRO_SERVICES = file("06g-pro-services", "航拍、测绘、设备维护、技术支持服务", false,
            req("service_ref", REF),
            req("provider_organization_code", ORG_CODE),
            req("type", oneOf("AERIAL", "SURVEY", "MAINTENANCE", "TECH_SUPPORT")),
            req("category_code", oneOf("ENGINEERING", "ENERGY", "TOURISM", "EVENT", "MODELING", "PARK", "TERRAIN",
                    "CORRIDOR", "GENERAL", "DRONE", "ROBOT", "ROBOT_DOG", "FLIGHT", "SOFTWARE")),
            req("title", text(40)),
            req("summary", text(80)),
            req("price_mode", oneOf("PRICE_FROM", "PROJECT")),
            opt("price_amount_minor", money()),
            opt("price_unit", text(20)),
            opt("price_note", text(40)),
            opt("cover_url", https()),
            opt("sort_order", SORT),
            req("status", oneOf("PUBLISHED", "UNPUBLISHED")));

    static final FileTemplate PRO_SERVICE_CITIES = file("06h-pro-service-cities", "专业服务的可服务城市", false,
            req("service_ref", REF),
            req("city_code", CITY));

    static final FileTemplate PRO_SERVICE_ITEMS = file("06i-pro-service-items", "专业服务详情条目", false,
            req("service_ref", REF),
            req("kind", oneOf("TAG", "FACET", "CAPABILITY", "PRICE_ROW", "SCOPE", "OUTPUT", "SERVICE_MODE", "TERM",
                    "STEP", "GUARANTEE")),
            opt("code", oneOf("VIDEO_FOOTAGE", "PHOTOS", "ORTHOPHOTO", "MODEL_3D", "ON_SITE", "MAIL_IN", "REMOTE",
                    "STATIONED")),
            req("label", text(40)),
            opt("detail", text(120)),
            opt("sort_order", SORT));

    static final FileTemplate ARTICLES = file("07-articles", "案例、知识图文和视频", true,
            req("article_ref", REF),
            req("kind", oneOf("CASE", "KNOWLEDGE", "VIDEO")),
            req("title", text(60)),
            req("author_kind", oneOf("PLATFORM", "PROVIDER", "MERCHANT")),
            opt("author_organization_code", ORG_CODE),
            req("author_name", text(160)),
            req("sponsored", bool()),
            opt("city_code", CITY),
            opt("cover_url", https()),
            opt("video_url", https()),
            opt("video_duration_seconds", integer(1, 3600)),
            opt("video_orientation", oneOf("PORTRAIT", "LANDSCAPE")),
            opt("video_caption", text(200)),
            opt("video_tags", text(120)),
            req("status", oneOf("DRAFT", "PUBLISHED", "OFFLINE")),
            opt("published_at", dateTime()));

    static final FileTemplate ARTICLE_BLOCKS = file("07b-article-blocks", "图文正文", false,
            req("article_ref", REF),
            opt("block_ref", REF),
            req("sort_order", SEQUENCE),
            req("block_type", oneOf("PARAGRAPH", "HEADING", "TIP", "NOTICE", "FACTS", "STEPS", "LIST")),
            opt("text", text(500)));

    static final FileTemplate ARTICLE_BLOCK_ITEMS = file("07c-article-block-items", "要点、步骤、列表的子条目", false,
            req("block_ref", REF),
            req("sort_order", SEQUENCE),
            req("title", text(60)),
            opt("text", text(200)));

    static final FileTemplate ARTICLE_REFS = file("07d-article-refs", "“内容中提到”的对象", false,
            req("article_ref", REF),
            req("sort_order", SEQUENCE),
            req("ref_type", oneOf("INSPECTION_SERVICE", "PRO_SERVICE", "PRODUCT", "ACTIVITY", "VENUE")),
            req("ref_key", text(64)),
            req("title", text(60)));

    static final FileTemplate TOPICS = file("07e-topics", "专题", false,
            req("topic_ref", REF),
            req("title", text(40)),
            opt("subtitle", text(60)),
            req("publisher_kind", oneOf("PLATFORM", "PROVIDER", "MERCHANT")),
            opt("publisher_organization_code", ORG_CODE),
            req("publisher_name", text(160)),
            req("city_code", CITY),
            req("starts_on", date()),
            req("ends_on", date()),
            opt("cover_url", https()),
            req("status", oneOf("DRAFT", "PUBLISHED", "OFFLINE")));

    static final FileTemplate TOPIC_ITEMS = file("07f-topic-items", "专题里的活动、场馆、图文", false,
            req("topic_ref", REF),
            req("sort_order", SEQUENCE),
            req("item_type", oneOf("ACTIVITY", "VENUE", "ARTICLE")),
            req("item_key", text(64)));

    static final Map<OpsImportKind, KindTemplate> KINDS = kinds();

    private static Map<OpsImportKind, KindTemplate> kinds() {
        Map<OpsImportKind, KindTemplate> kinds = new EnumMap<>(OpsImportKind.class);
        kinds.put(OpsImportKind.ORGANIZATIONS, new KindTemplate(OpsImportKind.ORGANIZATIONS,
                List.of(ORGANIZATIONS, OPERATORS)));
        kinds.put(OpsImportKind.MERCHANT_ACCOUNTS, new KindTemplate(OpsImportKind.MERCHANT_ACCOUNTS,
                List.of(MERCHANT_ACCOUNTS)));
        kinds.put(OpsImportKind.MALL, new KindTemplate(OpsImportKind.MALL,
                List.of(PRODUCTS, PRODUCT_SPECS, PACK_ITEMS, SKUS, OFFERS)));
        kinds.put(OpsImportKind.ACTIVITIES, new KindTemplate(OpsImportKind.ACTIVITIES,
                List.of(ACTIVITIES, ACTIVITY_SESSIONS)));
        kinds.put(OpsImportKind.VENUES, new KindTemplate(OpsImportKind.VENUES,
                List.of(VENUES, VENUE_INFO, VENUE_PROJECTS, VENUE_SESSIONS, VENUE_DEVICES, EXPERIENCE_ITEMS)));
        kinds.put(OpsImportKind.SERVICES, new KindTemplate(OpsImportKind.SERVICES,
                List.of(PROVIDER_PROFILES, INSPECTION_SERVICES, INSPECTION_CITIES, INSPECTION_METHODS,
                        INSPECTION_DETAIL_ITEMS, INSPECTION_BOOKING_ITEMS, PRO_SERVICES, PRO_SERVICE_CITIES,
                        PRO_SERVICE_ITEMS)));
        kinds.put(OpsImportKind.CONTENT, new KindTemplate(OpsImportKind.CONTENT,
                List.of(ARTICLES, ARTICLE_BLOCKS, ARTICLE_BLOCK_ITEMS, ARTICLE_REFS, TOPICS, TOPIC_ITEMS)));
        return Map.copyOf(kinds);
    }

    /**
     * 上传的文件名对应哪张模板：模板文件名本身，或后面加 -序号（03-mall-products-2.csv）。对不上返回空。
     */
    static Optional<FileTemplate> templateFor(KindTemplate kind, String fileName) {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".csv")) {
            return Optional.empty();
        }
        String base = name.substring(0, name.length() - 4).replaceFirst("-[0-9]{1,3}$", "");
        return kind.files().stream().filter(template -> template.baseName().equals(base)).findFirst();
    }
}
