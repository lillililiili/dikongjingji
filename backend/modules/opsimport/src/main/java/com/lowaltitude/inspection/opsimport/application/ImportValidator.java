package com.lowaltitude.inspection.opsimport.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.opsimport.application.ImportTables.Row;
import com.lowaltitude.inspection.opsimport.application.ImportTemplates.Column;
import com.lowaltitude.inspection.opsimport.application.ImportTemplates.FileTemplate;
import com.lowaltitude.inspection.opsimport.application.ImportTemplates.KindTemplate;
import com.lowaltitude.inspection.opsimport.application.OpsImportPorts.Organization;

/**
 * 预检：把一批文件按模板读出来，逐行校验（docs/ops/首批资源导入.md §2、§3 的“怎么填”和数据库约束），再按每类的规则做跨列、
 * 跨表和查库的检查。所有问题都记下来（文件、行、列、说明），不在第一个问题处停下。
 * 只读：查库经 {@link OpsImportPorts.Lookups} 和导入的编号对照表（{@link RefLookup}），不写任何东西。
 */
final class ImportValidator {

    static final int MAX_ROWS_PER_FILE = 5000;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** 以前导入过的编号（商品）。 */
    interface RefLookup {
        Map<String, UUID> products(Collection<String> references);
    }

    record Upload(String fileName, byte[] content) {
    }

    record Issue(String fileName, int line, String column, String message) {
    }

    record ReviewItem(String fileName, int line, String reference, String label, String value) {
    }

    record FileStat(String fileName, int rowCount) {
    }

    record Result(ImportTables tables, List<Issue> issues, List<ReviewItem> reviewItems, List<FileStat> files,
                  int rowCount, Map<String, Organization> organizations) {
    }

    private final OpsImportPorts.Lookups lookups;
    private final RefLookup refs;
    private final LocalDate today;

    private final List<Issue> issues = new ArrayList<>();
    private final List<ReviewItem> reviewItems = new ArrayList<>();
    private final Set<String> badCells = new HashSet<>();
    private final Map<String, Boolean> cities = new HashMap<>();
    private Map<String, Organization> organizations = Map.of();

    ImportValidator(OpsImportPorts.Lookups lookups, RefLookup refs, LocalDate today) {
        this.lookups = lookups;
        this.refs = refs;
        this.today = today;
    }

    Result validate(OpsImportKind kind, List<Upload> uploads) {
        KindTemplate template = ImportTemplates.KINDS.get(kind);
        ImportTables tables = new ImportTables();
        List<FileStat> stats = new ArrayList<>();
        Set<FileTemplate> uploaded = new HashSet<>();
        int rowCount = 0;
        for (Upload upload : uploads) {
            Optional<FileTemplate> file = ImportTemplates.templateFor(template, upload.fileName());
            if (file.isEmpty()) {
                fileIssue(upload.fileName(), "文件名对不上“" + kind.title() + "”的模板（" + String.join("、",
                        template.files().stream().map(FileTemplate::fileName).toList()) + "，后面可以加 -序号）");
                stats.add(new FileStat(upload.fileName(), 0));
                continue;
            }
            uploaded.add(file.get());
            int rows = read(file.get(), upload, tables);
            stats.add(new FileStat(upload.fileName(), rows));
            rowCount += rows;
        }
        for (FileTemplate file : template.files()) {
            // 传了但读不出来（表头不对等）的已经报过，不再说“必须带”。
            if (file.required() && !uploaded.contains(file)) {
                fileIssue(file.fileName(), "这一类必须带“" + file.title() + "”这张表");
            }
        }
        switch (kind) {
            case ORGANIZATIONS -> organizations(tables);
            case MERCHANT_ACCOUNTS -> merchantAccounts(tables);
            case MALL -> mall(tables);
            case ACTIVITIES -> activities(tables);
            case VENUES -> venues(tables);
            case SERVICES -> services(tables);
            case CONTENT -> content(tables);
        }
        return new Result(tables, List.copyOf(issues), List.copyOf(reviewItems), List.copyOf(stats), rowCount,
                organizations);
    }

    // ---- 读文件、按列校验 ----

    private int read(FileTemplate file, Upload upload, ImportTables tables) {
        List<CsvReader.Line> lines;
        try {
            lines = CsvReader.read(upload.content());
        } catch (CsvReader.CsvException invalid) {
            fileIssue(upload.fileName(), invalid.getMessage());
            return 0;
        }
        int index = 0;
        while (index < lines.size() && lines.get(index).blank()) {
            index++;
        }
        if (index == lines.size()) {
            fileIssue(upload.fileName(), "文件是空的，第一行要是表头");
            return 0;
        }
        List<String> header = lines.get(index).cells().stream().map(String::strip).toList();
        if (!header.equals(file.header())) {
            issues.add(new Issue(upload.fileName(), lines.get(index).number(), null,
                    "表头和模板不一样，第一行要一字不差：" + String.join(",", file.header())));
            return 0;
        }
        tables.declare(file);
        int count = 0;
        for (CsvReader.Line line : lines.subList(index + 1, lines.size())) {
            if (line.blank()) {
                continue;
            }
            count++;
            if (count > MAX_ROWS_PER_FILE) {
                issues.add(new Issue(upload.fileName(), line.number(), null,
                        "一个文件最多 " + MAX_ROWS_PER_FILE + " 行，多了分成几个文件（文件名后面加 -序号）"));
                break;
            }
            if (line.cells().size() != header.size()) {
                issues.add(new Issue(upload.fileName(), line.number(), null,
                        "这一行有 " + line.cells().size() + " 格，表头有 " + header.size() + " 列"));
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                values.put(header.get(i), line.cells().get(i).strip());
            }
            Row row = new Row(upload.fileName(), line.number(), values);
            for (Column column : file.columns()) {
                String value = row.get(column.name());
                if (value.isEmpty()) {
                    if (column.required()) {
                        cell(row, column.name(), "必填");
                    }
                    continue;
                }
                String problem = column.rule().check(value);
                if (problem != null) {
                    cell(row, column.name(), problem);
                }
            }
            tables.add(file, row);
        }
        return count;
    }

    // ---- 1 经营主体 ----

    private void organizations(ImportTables tables) {
        List<Row> rows = tables.rows(ImportTemplates.ORGANIZATIONS);
        List<Row> operators = tables.rows(ImportTemplates.OPERATORS);
        loadOrganizations(column(rows, "organization_code"), column(operators, "organization_code"));
        Map<String, Row> defined = unique(rows, "organization_code", "经营主体编码");
        for (Row row : rows) {
            String code = row.get("organization_code");
            Organization existing = organizations.get(code);
            if (ok(row, "organization_code") && existing != null && !"PROVIDER".equals(existing.organizationType())) {
                cell(row, "organization_code", "这个编码在库里已经是别的类型的机构，不能用");
            }
            if (ok(row, "qualification_reviewed_on")
                    && LocalDate.parse(row.get("qualification_reviewed_on"), ImportTemplates.DATE).isAfter(today)) {
                cell(row, "qualification_reviewed_on", "审核日期不能晚于今天");
            }
            if (row.has("credit_code")) {
                reviewItems.add(new ReviewItem(row.fileName(), row.line(), code, "统一社会信用代码",
                        row.get("credit_code")));
            }
            if (row.has("business_roles")) {
                reviewItems.add(new ReviewItem(row.fileName(), row.line(), code, "平台角色",
                        String.join("、", ImportTemplates.split(row.get("business_roles")).stream()
                                .map(ImportValidator::businessRoleLabel).toList())));
            }
        }

        Map<String, Row> firstOfLogin = new LinkedHashMap<>();
        Set<String> loginRoles = new HashSet<>();
        for (Row row : operators) {
            String code = row.get("organization_code");
            if (ok(row, "organization_code") && !defined.containsKey(code)) {
                requireProvider(row, "organization_code");
            }
            String login = row.get("login_name");
            if (!ok(row, "login_name")) {
                continue;
            }
            Row first = firstOfLogin.putIfAbsent(login, row);
            if (first != null) {
                if (!first.get("organization_code").equals(code)) {
                    cell(row, "login_name", "同一个登录名不能给两家机构（第 " + first.line() + " 行已经用过）");
                } else if (!first.get("display_name").equals(row.get("display_name"))) {
                    cell(row, "display_name", "同一个登录名的显示名要一样（第 " + first.line() + " 行）");
                }
            }
            if (!loginRoles.add(login + "|" + row.get("role_code"))) {
                cell(row, "role_code", "这个账号的这个角色已经有一行了");
            }
        }
        Map<String, Optional<UUID>> owners = firstOfLogin.isEmpty() ? Map.of()
                : lookups.loginOwners(firstOfLogin.keySet());
        for (Map.Entry<String, Row> entry : firstOfLogin.entrySet()) {
            Optional<UUID> owner = owners.get(entry.getKey());
            if (owner == null) {
                continue;
            }
            Organization organization = organizations.get(entry.getValue().get("organization_code"));
            if (organization == null || owner.isEmpty() || !owner.get().equals(organization.id())) {
                cell(entry.getValue(), "login_name", "登录名已被其他机构的账号占用，换一个");
            }
        }
    }

    // ---- 2 收款账户 ----

    private void merchantAccounts(ImportTables tables) {
        List<Row> rows = tables.rows(ImportTemplates.MERCHANT_ACCOUNTS);
        loadOrganizations(column(rows, "organization_code"));
        unique(rows, "organization_code", "经营主体编码");
        for (Row row : rows) {
            requireProvider(row, "organization_code");
            if (ok(row, "sub_mchid") && lookups.subMchidRejected(row.get("sub_mchid"))) {
                cell(row, "sub_mchid", "这是开发用的占位号段（1900000xxx），正式环境要填真实的二级商户号");
            }
        }
    }

    // ---- 3 商品与供给 ----

    private void mall(ImportTables tables) {
        List<Row> products = tables.rows(ImportTemplates.PRODUCTS);
        List<Row> specs = tables.rows(ImportTemplates.PRODUCT_SPECS);
        List<Row> packs = tables.rows(ImportTemplates.PACK_ITEMS);
        List<Row> skus = tables.rows(ImportTemplates.SKUS);
        List<Row> offers = tables.rows(ImportTemplates.OFFERS);
        loadOrganizations(column(offers, "merchant_organization_code"));
        Map<String, Row> productByRef = unique(products, "product_ref", "商品编号");
        references(specs, "product_ref", productByRef.keySet(), ImportTemplates.PRODUCTS);
        references(packs, "product_ref", productByRef.keySet(), ImportTemplates.PRODUCTS);
        references(skus, "product_ref", productByRef.keySet(), ImportTemplates.PRODUCTS);
        Map<String, Row> skuByRef = unique(skus, "sku_ref", "规格编号");
        references(offers, "sku_ref", skuByRef.keySet(), ImportTemplates.SKUS);

        Map<String, Integer> skuCount = new HashMap<>();
        for (Row sku : skus) {
            skuCount.merge(sku.get("product_ref"), 1, Integer::sum);
        }
        Map<String, Integer> activeOffers = new HashMap<>();
        Set<String> offerKeys = new HashSet<>();
        for (Row offer : offers) {
            Row sku = skuByRef.get(offer.get("sku_ref"));
            Row product = sku == null ? null : productByRef.get(sku.get("product_ref"));
            requireProvider(offer, "merchant_organization_code");
            if (ok(offer, "city_code")) {
                requireCity(offer, "city_code");
            }
            String key = offer.get("sku_ref") + "|" + offer.get("merchant_organization_code") + "|"
                    + (offer.has("city_code") ? offer.get("city_code") : "*");
            if (!offerKeys.add(key)) {
                cell(offer, "city_code", "同一个规格、商家、城市只能有一行供给");
            }
            if (!offer.bool("express_available") && ok(offer, "express_available") && !offer.has("pickup_point")) {
                cell(offer, "pickup_point", "不能快递时要填自提点");
            }
            if (product == null || !ok(product, "sale_mode")) {
                continue;
            }
            String saleMode = product.get("sale_mode");
            String unit = offer.get("price_unit");
            boolean unitFits = "RENT".equals(saleMode) ? unit.equals("DAY") || unit.equals("HALF_DAY")
                    : unit.equals("ITEM");
            if (ok(offer, "price_unit") && !unitFits) {
                cell(offer, "price_unit", "RENT".equals(saleMode) ? "租赁商品按 DAY 或 HALF_DAY 计价"
                        : "购买和询价商品按 ITEM 计价");
            }
            boolean active = "ACTIVE".equals(offer.get("status"));
            if ("INQUIRY".equals(saleMode) && offer.has("price_amount_minor")) {
                cell(offer, "price_amount_minor", "询价商品不标价，空着");
            }
            if (!"INQUIRY".equals(saleMode) && active && !offer.has("price_amount_minor")) {
                cell(offer, "price_amount_minor", "购买和租赁的供给启用（ACTIVE）时要有价格");
            }
            if (active) {
                activeOffers.merge(sku.get("product_ref"), 1, Integer::sum);
            }
            Organization merchant = organizations.get(offer.get("merchant_organization_code"));
            if ("BUY".equals(saleMode) && active && offer.has("price_amount_minor")
                    && "PUBLISHED".equals(product.get("status")) && merchant != null
                    && !lookups.merchantCollecting(merchant.id())) {
                cell(offer, "merchant_organization_code",
                        "商家没在收款账户表（02）里登记有效的收款账户，标价商品上架后不能下单；先导入收款账户，或先填草稿");
            }
        }
        for (Row product : products) {
            String ref = product.get("product_ref");
            if (ok(product, "product_ref") && skuCount.getOrDefault(ref, 0) == 0) {
                cell(product, "product_ref", "一个商品至少要有一个规格（03d 表）");
            }
            if ("PUBLISHED".equals(product.get("status"))) {
                if (!product.has("summary")) {
                    cell(product, "summary", "上架（PUBLISHED）要有一句话介绍");
                }
                if (activeOffers.getOrDefault(ref, 0) == 0) {
                    cell(product, "status", "上架（PUBLISHED）要至少有一条启用的供给（03e 表）；没核对完先填 DRAFT");
                }
            }
        }
    }

    // ---- 4 活动与场次（这一版只预检） ----

    private void activities(ImportTables tables) {
        List<Row> activities = tables.rows(ImportTemplates.ACTIVITIES);
        List<Row> sessions = tables.rows(ImportTemplates.ACTIVITY_SESSIONS);
        loadOrganizations(column(activities, "organizer_organization_code"),
                column(activities, "co_organizer_organization_code"));
        Map<String, Row> byRef = unique(activities, "activity_ref", "活动编号");
        references(sessions, "activity_ref", byRef.keySet(), ImportTemplates.ACTIVITIES);
        Map<String, Integer> sessionCount = new HashMap<>();
        for (Row session : sessions) {
            sessionCount.merge(session.get("activity_ref"), 1, Integer::sum);
            LocalDateTime starts = dateTime(session, "starts_at");
            LocalDateTime ends = dateTime(session, "ends_at");
            LocalDateTime closes = dateTime(session, "enrollment_closes_at");
            LocalDateTime checkIn = dateTime(session, "check_in_opens_at");
            if (starts != null && ends != null && !ends.isAfter(starts)) {
                cell(session, "ends_at", "结束要晚于开始");
            }
            if (starts != null && closes != null && closes.isAfter(starts)) {
                cell(session, "enrollment_closes_at", "报名截止不能晚于开始");
            }
            if (starts != null && checkIn != null && ends != null && checkIn.isAfter(ends)) {
                cell(session, "check_in_opens_at", "开始核销的时间不能晚于结束");
            }
        }
        for (Row activity : activities) {
            requireProvider(activity, "organizer_organization_code");
            if (activity.has("co_organizer_organization_code")) {
                requireProvider(activity, "co_organizer_organization_code");
            }
            requireCity(activity, "city_code");
            pair(activity, "venue_longitude", "venue_latitude");
            String mode = activity.get("price_mode");
            if ("PER_PERSON".equals(mode) && !activity.has("price_amount_minor")) {
                cell(activity, "price_amount_minor", "按人收费（PER_PERSON）要填价格");
            }
            if (!"PER_PERSON".equals(mode) && ok(activity, "price_mode") && activity.has("price_amount_minor")) {
                cell(activity, "price_amount_minor", "只有按人收费（PER_PERSON）填价格");
            }
            if (ok(activity, "min_age") && ok(activity, "max_age")
                    && activity.integer("max_age") < activity.integer("min_age")) {
                cell(activity, "max_age", "不能小于 min_age");
            }
            Organization organizer = organizations.get(activity.get("organizer_organization_code"));
            if ("PER_PERSON".equals(mode) && "PUBLISHED".equals(activity.get("status")) && organizer != null
                    && !lookups.merchantCollecting(organizer.id())) {
                cell(activity, "organizer_organization_code", "收费活动上架前主办机构要登记有效的收款账户（02 表）");
            }
            if ("PUBLISHED".equals(activity.get("status")) && ok(activity, "activity_ref")
                    && sessionCount.getOrDefault(activity.get("activity_ref"), 0) == 0) {
                cell(activity, "status", "上架的活动至少要有一个场次（04b 表）");
            }
        }
    }

    // ---- 5 场馆与项目（这一版只预检） ----

    private void venues(ImportTables tables) {
        List<Row> venues = tables.rows(ImportTemplates.VENUES);
        List<Row> info = tables.rows(ImportTemplates.VENUE_INFO);
        List<Row> projects = tables.rows(ImportTemplates.VENUE_PROJECTS);
        List<Row> sessions = tables.rows(ImportTemplates.VENUE_SESSIONS);
        List<Row> devices = tables.rows(ImportTemplates.VENUE_DEVICES);
        List<Row> experiences = tables.rows(ImportTemplates.EXPERIENCE_ITEMS);
        loadOrganizations(column(venues, "operator_organization_code"));
        Map<String, Row> byRef = unique(venues, "venue_ref", "场馆编号");
        references(info, "venue_ref", byRef.keySet(), ImportTemplates.VENUES);
        references(projects, "venue_ref", byRef.keySet(), ImportTemplates.VENUES);
        references(devices, "venue_ref", byRef.keySet(), ImportTemplates.VENUES);
        Map<String, Row> projectByRef = unique(projects, "project_ref", "项目编号");
        references(sessions, "project_ref", projectByRef.keySet(), ImportTemplates.VENUE_PROJECTS);
        for (Row venue : venues) {
            requireProvider(venue, "operator_organization_code");
            requireCity(venue, "city_code");
            pair(venue, "longitude", "latitude");
        }
        for (Row project : projects) {
            Row venue = byRef.get(project.get("venue_ref"));
            Organization operator = venue == null ? null : organizations.get(venue.get("operator_organization_code"));
            if (ok(project, "price_amount_minor") && project.amount("price_amount_minor") > 0
                    && "ACTIVE".equals(project.get("status")) && venue != null
                    && "PUBLISHED".equals(venue.get("status")) && operator != null
                    && !lookups.merchantCollecting(operator.id())) {
                cell(project, "price_amount_minor", "收费项目上架前场馆运营方要登记有效的收款账户（02 表）");
            }
        }
        for (Row session : sessions) {
            LocalDateTime starts = dateTime(session, "starts_at");
            LocalDateTime ends = dateTime(session, "ends_at");
            if (starts != null && ends != null && !ends.isAfter(starts)) {
                cell(session, "ends_at", "结束要晚于开始");
            }
        }
        productRefs(devices, "product_ref");
        productRefs(experiences, "product_ref");
    }

    // ---- 6 巡检和专业服务（这一版只预检） ----

    private void services(ImportTables tables) {
        List<Row> profiles = tables.rows(ImportTemplates.PROVIDER_PROFILES);
        List<Row> inspections = tables.rows(ImportTemplates.INSPECTION_SERVICES);
        List<Row> cities = tables.rows(ImportTemplates.INSPECTION_CITIES);
        List<Row> methods = tables.rows(ImportTemplates.INSPECTION_METHODS);
        List<Row> details = tables.rows(ImportTemplates.INSPECTION_DETAIL_ITEMS);
        List<Row> bookings = tables.rows(ImportTemplates.INSPECTION_BOOKING_ITEMS);
        List<Row> pro = tables.rows(ImportTemplates.PRO_SERVICES);
        List<Row> proCities = tables.rows(ImportTemplates.PRO_SERVICE_CITIES);
        List<Row> proItems = tables.rows(ImportTemplates.PRO_SERVICE_ITEMS);
        loadOrganizations(column(profiles, "provider_organization_code"),
                column(inspections, "provider_organization_code"), column(pro, "provider_organization_code"));
        unique(profiles, "provider_organization_code", "服务商");
        profiles.forEach(row -> requireProvider(row, "provider_organization_code"));

        Map<String, Row> inspectionByRef = unique(inspections, "service_ref", "巡检服务编号");
        references(cities, "service_ref", inspectionByRef.keySet(), ImportTemplates.INSPECTION_SERVICES);
        references(methods, "service_ref", inspectionByRef.keySet(), ImportTemplates.INSPECTION_SERVICES);
        references(details, "service_ref", inspectionByRef.keySet(), ImportTemplates.INSPECTION_SERVICES);
        references(bookings, "service_ref", inspectionByRef.keySet(), ImportTemplates.INSPECTION_SERVICES);
        for (Row service : inspections) {
            requireProvider(service, "provider_organization_code");
            requireCity(service, "city_code");
            boolean quote = "PROJECT_QUOTE".equals(service.get("price_mode"));
            if (quote && service.has("starting_amount_minor")) {
                cell(service, "starting_amount_minor", "按项目报价（PROJECT_QUOTE）不填价格");
            }
            if (!quote && ok(service, "price_mode") && !service.has("starting_amount_minor")) {
                cell(service, "starting_amount_minor", "除按项目报价外都要填价格");
            }
        }
        cities.forEach(row -> requireCity(row, "city_code"));
        uniqueBy(cities, row -> row.get("service_ref") + "|" + row.get("city_code"), "city_code",
                "同一个服务的这个城市已经有一行");
        uniqueBy(methods, row -> row.get("service_ref") + "|" + row.get("method_code"), "method_code",
                "同一个服务的这个作业方式已经有一行");
        uniqueBy(details, row -> row.get("service_ref") + "|" + row.get("section_type") + "|" + row.get("item_code"),
                "item_code", "同一个服务同一块里 item_code 重复");
        uniqueBy(bookings, row -> row.get("service_ref") + "|" + row.get("item_code"), "item_code",
                "同一个服务里 item_code 重复");

        Map<String, Row> proByRef = unique(pro, "service_ref", "专业服务编号");
        references(proCities, "service_ref", proByRef.keySet(), ImportTemplates.PRO_SERVICES);
        references(proItems, "service_ref", proByRef.keySet(), ImportTemplates.PRO_SERVICES);
        Map<String, Set<String>> categories = Map.of(
                "AERIAL", Set.of("ENGINEERING", "ENERGY", "TOURISM", "EVENT"),
                "SURVEY", Set.of("MODELING", "PARK", "TERRAIN", "CORRIDOR"),
                "MAINTENANCE", Set.of("GENERAL", "DRONE", "ROBOT", "ROBOT_DOG"),
                "TECH_SUPPORT", Set.of("GENERAL", "FLIGHT", "SOFTWARE", "EVENT"));
        for (Row service : pro) {
            requireProvider(service, "provider_organization_code");
            Set<String> allowed = categories.get(service.get("type"));
            if (allowed != null && ok(service, "category_code") && !allowed.contains(service.get("category_code"))) {
                cell(service, "category_code", "这个类型只能用 " + String.join(" / ", allowed.stream().sorted().toList()));
            }
            if ("PRICE_FROM".equals(service.get("price_mode"))) {
                for (String column : List.of("price_amount_minor", "price_unit", "price_note")) {
                    if (!service.has(column)) {
                        cell(service, column, "起价（PRICE_FROM）要填");
                    }
                }
            } else if ("PROJECT".equals(service.get("price_mode")) && service.has("price_amount_minor")) {
                cell(service, "price_amount_minor", "按项目报价（PROJECT）不填价格");
            }
        }
        proCities.forEach(row -> requireCity(row, "city_code"));
        Set<String> needsDetail = Set.of("PRICE_ROW", "OUTPUT", "SERVICE_MODE", "TERM");
        for (Row item : proItems) {
            String kind = item.get("kind");
            if ("FACET".equals(kind) && !item.has("code")) {
                cell(item, "code", "FACET 要填 code");
            }
            if (!"FACET".equals(kind) && ok(item, "kind") && item.has("code")) {
                cell(item, "code", "只有 FACET 填 code");
            }
            if (needsDetail.contains(kind) && !item.has("detail")) {
                cell(item, "detail", kind + " 要填 detail");
            }
            if (!needsDetail.contains(kind) && ok(item, "kind") && item.has("detail")) {
                cell(item, "detail", "只有 PRICE_ROW、OUTPUT、SERVICE_MODE、TERM 填 detail");
            }
        }
    }

    // ---- 7 发现内容（这一版只预检） ----

    private void content(ImportTables tables) {
        List<Row> articles = tables.rows(ImportTemplates.ARTICLES);
        List<Row> blocks = tables.rows(ImportTemplates.ARTICLE_BLOCKS);
        List<Row> blockItems = tables.rows(ImportTemplates.ARTICLE_BLOCK_ITEMS);
        List<Row> articleRefs = tables.rows(ImportTemplates.ARTICLE_REFS);
        List<Row> topics = tables.rows(ImportTemplates.TOPICS);
        List<Row> topicItems = tables.rows(ImportTemplates.TOPIC_ITEMS);
        loadOrganizations(column(articles, "author_organization_code"),
                column(topics, "publisher_organization_code"));
        Map<String, Row> articleByRef = unique(articles, "article_ref", "内容编号");
        for (Row article : articles) {
            String kind = article.get("kind");
            String author = article.get("author_kind");
            if ("CASE".equals(kind) && "PLATFORM".equals(author)) {
                cell(article, "author_kind", "案例只能是服务商或商家发的");
            }
            publisher(article, "author_kind", "author_organization_code");
            if ("MERCHANT".equals(author) && ok(article, "sponsored") && !article.bool("sponsored")) {
                cell(article, "sponsored", "商家内容必须是 true（标“广告”）");
            }
            if (!"KNOWLEDGE".equals(kind) && ok(article, "kind") && !article.has("city_code")) {
                cell(article, "city_code", "知识以外要填城市");
            }
            if (article.has("city_code")) {
                requireCity(article, "city_code");
            }
            boolean video = "VIDEO".equals(kind);
            for (String column : List.of("video_url", "video_duration_seconds", "video_orientation", "video_caption",
                    "video_tags")) {
                if (!video && ok(article, "kind") && article.has(column)) {
                    cell(article, column, "只有视频填这一列");
                }
            }
            if (video) {
                for (String column : List.of("video_url", "video_duration_seconds", "video_orientation")) {
                    if (!article.has(column)) {
                        cell(article, column, "视频要填");
                    }
                }
            }
            if ("PUBLISHED".equals(article.get("status")) && !article.has("published_at")) {
                cell(article, "published_at", "上架（PUBLISHED）要填发布时间");
            }
        }
        references(blocks, "article_ref", articleByRef.keySet(), ImportTemplates.ARTICLES);
        Map<String, Row> blockByRef = new LinkedHashMap<>();
        Set<String> listTypes = Set.of("FACTS", "STEPS", "LIST");
        for (Row block : blocks) {
            boolean list = listTypes.contains(block.get("block_type"));
            if (list) {
                if (block.has("text")) {
                    cell(block, "text", "要点、步骤、列表的内容写在 07c 表，这里空着");
                }
                if (!block.has("block_ref")) {
                    cell(block, "block_ref", "要点、步骤、列表要自定一个编号");
                }
            } else if (ok(block, "block_type") && !block.has("text")) {
                cell(block, "text", "这一段要有文字");
            }
            if (block.has("block_ref") && ok(block, "block_ref")) {
                Row first = blockByRef.putIfAbsent(block.get("block_ref"), block);
                if (first != null) {
                    cell(block, "block_ref", "编号重复（第 " + first.line() + " 行已经用过）");
                }
            }
        }
        uniqueBy(blocks, row -> row.get("article_ref") + "|" + row.get("sort_order"), "sort_order",
                "同一篇的段落顺序重复");
        references(blockItems, "block_ref", blockByRef.keySet(), ImportTemplates.ARTICLE_BLOCKS);
        for (Row item : blockItems) {
            Row block = blockByRef.get(item.get("block_ref"));
            if (block != null && !listTypes.contains(block.get("block_type"))) {
                cell(item, "block_ref", "只有 FACTS、STEPS、LIST 的段落能有子条目");
            }
        }
        references(articleRefs, "article_ref", articleByRef.keySet(), ImportTemplates.ARTICLES);
        List<Row> productMentions = new ArrayList<>();
        for (Row mention : articleRefs) {
            if ("PRODUCT".equals(mention.get("ref_type"))) {
                productMentions.add(mention);
            } else if (ok(mention, "ref_key") && !UUID_TEXT.matcher(mention.get("ref_key")).matches()
                    && ImportTemplates.REF.check(mention.get("ref_key")) != null) {
                cell(mention, "ref_key", "填对应表的编号或库里对象的 ID");
            }
        }
        productRefs(productMentions, "ref_key");

        Map<String, Row> topicByRef = unique(topics, "topic_ref", "专题编号");
        for (Row topic : topics) {
            publisher(topic, "publisher_kind", "publisher_organization_code");
            requireCity(topic, "city_code");
            if (ok(topic, "starts_on") && ok(topic, "ends_on")
                    && LocalDate.parse(topic.get("ends_on"), ImportTemplates.DATE)
                    .isBefore(LocalDate.parse(topic.get("starts_on"), ImportTemplates.DATE))) {
                cell(topic, "ends_on", "结束不能早于开始");
            }
        }
        references(topicItems, "topic_ref", topicByRef.keySet(), ImportTemplates.TOPICS);
        for (Row item : topicItems) {
            if ("ARTICLE".equals(item.get("item_type")) && ok(item, "item_key")
                    && !articleByRef.containsKey(item.get("item_key"))
                    && !UUID_TEXT.matcher(item.get("item_key")).matches()) {
                cell(item, "item_key", "在 07 表里找不到这个内容编号");
            }
        }
    }

    // ---- 共用的检查 ----

    private void publisher(Row row, String kindColumn, String organizationColumn) {
        String kind = row.get(kindColumn);
        if ("PLATFORM".equals(kind)) {
            if (row.has(organizationColumn)) {
                cell(row, organizationColumn, "平台发的空着");
            }
        } else if (ok(row, kindColumn)) {
            if (!row.has(organizationColumn)) {
                cell(row, organizationColumn, "服务商、商家发的要填经营主体编码");
            } else {
                requireProvider(row, organizationColumn);
            }
        }
    }

    /** 引用以前导入过的商品（03 表的 product_ref，或库里商品的 ID）。 */
    private void productRefs(List<Row> rows, String column) {
        List<String> wanted = rows.stream().filter(row -> ok(row, column)).map(row -> row.get(column))
                .filter(value -> !UUID_TEXT.matcher(value).matches()).distinct().toList();
        Map<String, UUID> found = wanted.isEmpty() ? Map.of() : refs.products(wanted);
        for (Row row : rows) {
            String value = row.get(column);
            if (ok(row, column) && !UUID_TEXT.matcher(value).matches() && !found.containsKey(value)) {
                cell(row, column, "找不到这个商品编号：要先在“商品与供给”里导入，或填库里商品的 ID");
            }
        }
    }

    @SafeVarargs
    private void loadOrganizations(List<String>... columns) {
        Set<String> codes = new LinkedHashSet<>();
        for (List<String> values : columns) {
            values.stream().filter(value -> !value.isEmpty()).forEach(codes::add);
        }
        organizations = codes.isEmpty() ? Map.of() : Map.copyOf(lookups.organizations(codes));
    }

    private void requireProvider(Row row, String column) {
        if (!ok(row, column)) {
            return;
        }
        Organization organization = organizations.get(row.get(column));
        if (organization == null) {
            cell(row, column, "库里没有这个经营主体编码，先导入经营主体（01 表）");
        } else if (!"PROVIDER".equals(organization.organizationType())) {
            cell(row, column, "这个编码不是经营机构");
        }
    }

    private void requireCity(Row row, String column) {
        if (!ok(row, column)) {
            return;
        }
        String code = row.get(column);
        if (!cities.computeIfAbsent(code, lookups::cityExists)) {
            cell(row, column, "平台城市目录里没有这个城市编码");
        }
    }

    /** 经纬度要么都填、要么都空。 */
    private void pair(Row row, String first, String second) {
        if (row.has(first) != row.has(second)) {
            cell(row, row.has(first) ? second : first, "经度和纬度要一起填");
        }
    }

    /** 同一类编号不能重复；返回编号到第一行的对照（编号本身有问题的行不算）。 */
    private Map<String, Row> unique(List<Row> rows, String column, String label) {
        Map<String, Row> seen = new LinkedHashMap<>();
        for (Row row : rows) {
            if (!ok(row, column)) {
                continue;
            }
            Row first = seen.putIfAbsent(row.get(column), row);
            if (first != null) {
                cell(row, column, label + "重复（" + first.fileName() + " 第 " + first.line() + " 行已经用过）");
            }
        }
        return seen;
    }

    private void uniqueBy(List<Row> rows, Function<Row, String> key, String column, String message) {
        Map<String, Row> seen = new HashMap<>();
        for (Row row : rows) {
            Row first = seen.putIfAbsent(key.apply(row), row);
            if (first != null) {
                cell(row, column, message + "（第 " + first.line() + " 行）");
            }
        }
    }

    private void references(List<Row> rows, String column, Set<String> defined, FileTemplate target) {
        for (Row row : rows) {
            if (ok(row, column) && !defined.contains(row.get(column))) {
                cell(row, column, "在“" + target.title() + "”（" + target.fileName() + "）里找不到编号 "
                        + row.get(column));
            }
        }
    }

    private LocalDateTime dateTime(Row row, String column) {
        return ok(row, column) ? LocalDateTime.parse(row.get(column), ImportTemplates.DATE_TIME) : null;
    }

    /** 这一格有值，并且通过了列规则（跨列检查只用这样的值，免得一个错报两遍）。 */
    private boolean ok(Row row, String column) {
        return row.has(column) && !badCells.contains(cellKey(row, column));
    }

    private void cell(Row row, String column, String message) {
        badCells.add(cellKey(row, column));
        issues.add(new Issue(row.fileName(), row.line(), column, message));
    }

    private void fileIssue(String fileName, String message) {
        issues.add(new Issue(fileName, 0, null, message));
    }

    private static String cellKey(Row row, String column) {
        return row.fileName() + "\n" + row.line() + "\n" + column;
    }

    private static List<String> column(List<Row> rows, String column) {
        return rows.stream().map(row -> row.get(column)).toList();
    }

    private static String businessRoleLabel(String code) {
        return switch (code) {
            case "MERCHANT" -> "商城商家";
            case "ORGANIZER" -> "活动主办";
            case "VENUE_OPERATOR" -> "场馆运营";
            case "INSPECTION_PROVIDER" -> "巡检服务商";
            case "SERVICE_PROVIDER" -> "专业服务商";
            default -> code;
        };
    }

    /** 北京时间“今天”（审核日期不能晚于它）。 */
    static LocalDate beijingToday(java.time.Clock clock) {
        return LocalDate.now(clock.withZone(BEIJING));
    }
}
