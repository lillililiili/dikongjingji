package com.lowaltitude.inspection.opsimport.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.opsimport.application.ImportTables.Row;
import com.lowaltitude.inspection.opsimport.application.OpsImportException.Error;
import com.lowaltitude.inspection.opsimport.application.OpsImportPorts.Actor;
import com.lowaltitude.inspection.opsimport.application.OpsImportPorts.Organization;
import com.lowaltitude.inspection.opsimport.application.OpsImportPorts.Written;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportBatchPageView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportBatchSummaryView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportBatchView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportChangeView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportCreatedAccountView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportFileSpecView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportFileView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportIssueView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportKindView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportReviewItemView;
import com.lowaltitude.inspection.opsimport.infrastructure.persistence.OpsImportMapper;
import com.lowaltitude.inspection.opsimport.infrastructure.persistence.OpsImportMapper.BatchRow;
import com.lowaltitude.inspection.opsimport.infrastructure.persistence.OpsImportMapper.FileRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量导入（S2-3，契约 contracts/openapi-ops-import.yaml 1.0.0，迁移 opsimport V001，填表规则 docs/ops/首批资源导入.md）。
 *
 * <ul>
 *   <li>预检：一类的几张 CSV 存进私有存储（保留 180 天），逐行校验，记一个批次（CHECKED / REJECTED）和最多 500 条问题。
 *       同一类、同样内容以前导入过时，批次直接标成 IMPORTED 并指向那一次，什么都不写。</li>
 *   <li>导入：只导 CHECKED 的批次；按库里的当前数据再校验一次，然后在一个事务里经各模块的写入端口写进去，记下改了哪些对象、
 *       更新编号对照表。同一个批次再导一次直接返回第一次的结果。</li>
 *   <li>只有 ORGANIZATIONS、MERCHANT_ACCOUNTS、MALL 能写；其余这一版只预检。</li>
 * </ul>
 * 只有平台运营能调，角色由控制器检查。
 */
public class OpsImportService {

    public static final int MAX_FILES = 12;
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;
    public static final int MAX_STORED_ISSUES = 500;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_FILE_NAME = 120;
    public static final Duration RETENTION = Duration.ofDays(180);

    private final OpsImportMapper mapper;
    private final OpsImportPorts.Lookups lookups;
    private final OpsImportPorts.Writers writers;
    private final OpsImportPorts.FileStore files;
    private final Clock clock;

    public OpsImportService(OpsImportMapper mapper, OpsImportPorts.Lookups lookups, OpsImportPorts.Writers writers,
                            OpsImportPorts.FileStore files, Clock clock) {
        this.mapper = mapper;
        this.lookups = lookups;
        this.writers = writers;
        this.files = files;
        this.clock = clock;
    }

    public List<OpsImportKindView> kinds() {
        return java.util.Arrays.stream(OpsImportKind.values())
                .map(kind -> new OpsImportKindView(kind, kind.title(), kind.writable(),
                        ImportTemplates.KINDS.get(kind).files().stream()
                                .map(file -> new OpsImportFileSpecView(file.fileName(), file.title(), file.required(),
                                        file.header()))
                                .toList()))
                .toList();
    }

    /** 预检一组文件，建一个批次。 */
    @Transactional
    public OpsImportBatchView check(String kindCode, List<UploadedFile> uploads, Actor actor) {
        requireActor(actor);
        OpsImportKind kind = kind(kindCode);
        List<ImportValidator.Upload> normalized = normalize(uploads);
        String sha = digest(normalized);
        UUID batchId = UUID.randomUUID();
        OffsetDateTime at = now();
        List<String> keys = new ArrayList<>();
        try {
            for (int i = 0; i < normalized.size(); i++) {
                String key = "imports/" + batchId + "/" + (i + 1) + ".csv";
                files.put(key, normalized.get(i).content());
                keys.add(key);
            }
        } catch (OpsImportPorts.FileStoreUnavailableException unavailable) {
            throw new OpsImportException(Error.FILE_STORE_UNAVAILABLE);
        }
        ImportValidator.Result result = validator().validate(kind, normalized);
        UUID imported = mapper.findImported(kind.name(), sha);
        OffsetDateTime retainedUntil = at.plus(RETENTION);
        if (imported != null) {
            mapper.insertBatch(batchId, kind.name(), OpsImportStatus.IMPORTED.name(), sha, result.rowCount(), 0,
                    imported, actor.accountId(), clip(actor.displayName(), 80), at, actor.accountId(),
                    clip(actor.displayName(), 80), at, retainedUntil);
        } else {
            int issueCount = result.issues().size();
            mapper.insertBatch(batchId, kind.name(),
                    (issueCount == 0 ? OpsImportStatus.CHECKED : OpsImportStatus.REJECTED).name(), sha,
                    result.rowCount(), issueCount, null, actor.accountId(), clip(actor.displayName(), 80), at, null,
                    null, null, retainedUntil);
            for (int i = 0; i < Math.min(issueCount, MAX_STORED_ISSUES); i++) {
                ImportValidator.Issue issue = result.issues().get(i);
                mapper.insertIssue(batchId, i + 1, clip(issue.fileName(), MAX_FILE_NAME), issue.line(),
                        issue.column(), clip(issue.message(), 300));
            }
        }
        for (int i = 0; i < normalized.size(); i++) {
            ImportValidator.Upload upload = normalized.get(i);
            mapper.insertFile(batchId, upload.fileName(), result.files().get(i).rowCount(), upload.content().length,
                    sha256(upload.content()), keys.get(i), i + 1);
        }
        List<OpsImportReviewItemView> review = imported != null ? List.of() : result.reviewItems().stream()
                .map(item -> new OpsImportReviewItemView(item.fileName(), item.line(), item.reference(), item.label(),
                        item.value()))
                .toList();
        return view(batchId, review, List.of());
    }

    /** 导入一个预检通过的批次；已经导入过的直接返回第一次的结果（不再给初始口令）。 */
    @Transactional
    public OpsImportBatchView run(UUID batchId, Actor actor) {
        requireActor(actor);
        BatchRow batch = batchId == null ? null : mapper.lockBatch(batchId);
        if (batch == null) {
            throw new OpsImportException(Error.NOT_FOUND);
        }
        if (OpsImportStatus.IMPORTED.name().equals(batch.status())) {
            return view(batchId, List.of(), List.of());
        }
        if (OpsImportStatus.REJECTED.name().equals(batch.status())) {
            throw new OpsImportException(Error.HAS_ISSUES);
        }
        OpsImportKind kind = OpsImportKind.valueOf(batch.kind());
        if (!kind.writable()) {
            throw new OpsImportException(Error.NOT_WRITABLE);
        }
        mapper.lockContent(kind.name(), batch.fileSha256());
        OffsetDateTime at = now();
        UUID imported = mapper.findImported(kind.name(), batch.fileSha256());
        if (imported != null) {
            mapper.markImported(batchId, imported, 0, actor.accountId(), clip(actor.displayName(), 80), at);
            return view(batchId, List.of(), List.of());
        }
        List<ImportValidator.Upload> uploads = new ArrayList<>();
        try {
            for (FileRow file : mapper.findFiles(batchId)) {
                uploads.add(new ImportValidator.Upload(file.fileName(), files.read(file.storageKey())));
            }
        } catch (OpsImportPorts.FileStoreUnavailableException unavailable) {
            throw new OpsImportException(Error.FILE_STORE_UNAVAILABLE);
        }
        ImportValidator.Result result = validator().validate(kind, uploads);
        if (!result.issues().isEmpty()) {
            throw new OpsImportException(Error.HAS_ISSUES);
        }
        String label = "批次 " + batchId.toString().substring(0, 8);
        List<Written> written;
        List<OpsImportCreatedAccountView> created = List.of();
        switch (kind) {
            case ORGANIZATIONS -> {
                OpsImportPorts.OrganizationsWritten outcome = writers.organizations(organizationRecords(result),
                        operatorRecords(result), label, actor);
                written = outcome.written();
                created = outcome.createdAccounts().stream()
                        .map(account -> new OpsImportCreatedAccountView(account.organizationCode(),
                                account.loginName(), account.initialPassword()))
                        .toList();
            }
            case MERCHANT_ACCOUNTS -> written = writers.merchantAccounts(merchantAccountRecords(result), label, actor);
            case MALL -> written = writers.mall(productRecords(result), label, actor);
            default -> throw new OpsImportException(Error.NOT_WRITABLE);
        }
        for (int i = 0; i < written.size(); i++) {
            Written item = written.get(i);
            mapper.insertChange(batchId, i + 1, item.kind().name(), clip(item.reference(), 200), item.targetId(),
                    (item.created() ? OpsImportChangeType.CREATED : OpsImportChangeType.UPDATED).name());
            if (item.kind() == OpsImportRefKind.PRODUCT || item.kind() == OpsImportRefKind.SKU
                    || item.kind() == OpsImportRefKind.OFFER) {
                mapper.upsertRef(item.kind().name(), item.reference(), item.targetId(), batchId, at);
            }
        }
        if (mapper.markImported(batchId, null, written.size(), actor.accountId(), clip(actor.displayName(), 80),
                at) != 1) {
            throw new IllegalStateException("import batch changed while it was being imported");
        }
        return view(batchId, List.of(), created);
    }

    @Transactional(readOnly = true)
    public OpsImportBatchPageView batches(String kindCode, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String kind = kindCode == null || kindCode.isBlank() ? null : kind(kindCode).name();
        long total = mapper.countBatches(kind);
        List<BatchRow> rows = mapper.findBatches(kind, size, Math.multiplyExact(page - 1, size));
        Map<UUID, List<String>> names = new HashMap<>();
        if (!rows.isEmpty()) {
            mapper.findFileNames(rows.stream().map(BatchRow::id).toList()).forEach(row ->
                    names.computeIfAbsent(row.batchId(), ignored -> new ArrayList<>()).add(row.fileName()));
        }
        List<OpsImportBatchSummaryView> items = rows.stream()
                .map(row -> new OpsImportBatchSummaryView(row.id(), OpsImportKind.valueOf(row.kind()),
                        OpsImportStatus.valueOf(row.status()), names.getOrDefault(row.id(), List.of()),
                        row.rowCount(), row.issueCount(), row.changeCount(), row.checkedByName(), row.checkedAt(),
                        row.importedByName(), row.importedAt()))
                .toList();
        return new OpsImportBatchPageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public OpsImportBatchView batch(UUID batchId) {
        return view(batchId, List.of(), List.of());
    }

    // ---- 组装写入端口的输入（行都已校验过） ----

    private static List<OpsImportPorts.OrganizationRecord> organizationRecords(ImportValidator.Result result) {
        return result.tables().rows(ImportTemplates.ORGANIZATIONS).stream()
                .map(row -> new OpsImportPorts.OrganizationRecord(row.get("organization_code"),
                        row.get("organization_name"), row.get("status"),
                        row.has("qualification_reviewed_on")
                                ? LocalDate.parse(row.get("qualification_reviewed_on"), ImportTemplates.DATE)
                                : null))
                .toList();
    }

    private static List<OpsImportPorts.OperatorRecord> operatorRecords(ImportValidator.Result result) {
        Map<String, List<Row>> byLogin = new LinkedHashMap<>();
        for (Row row : result.tables().rows(ImportTemplates.OPERATORS)) {
            byLogin.computeIfAbsent(row.get("login_name"), ignored -> new ArrayList<>()).add(row);
        }
        return byLogin.values().stream()
                .map(rows -> new OpsImportPorts.OperatorRecord(rows.getFirst().get("organization_code"),
                        rows.getFirst().get("login_name"), rows.getFirst().get("display_name"),
                        rows.stream().map(row -> row.get("role_code")).distinct().toList()))
                .toList();
    }

    private static List<OpsImportPorts.MerchantAccountRecord> merchantAccountRecords(ImportValidator.Result result) {
        return result.tables().rows(ImportTemplates.MERCHANT_ACCOUNTS).stream()
                .map(row -> new OpsImportPorts.MerchantAccountRecord(row.get("organization_code"),
                        result.organizations().get(row.get("organization_code")).id(), row.get("sub_mchid"),
                        row.bool("active")))
                .toList();
    }

    private List<OpsImportPorts.ProductRecord> productRecords(ImportValidator.Result result) {
        ImportTables tables = result.tables();
        List<Row> products = tables.rows(ImportTemplates.PRODUCTS);
        List<Row> skus = tables.rows(ImportTemplates.SKUS);
        List<Row> offers = tables.rows(ImportTemplates.OFFERS);
        Map<String, UUID> productIds = ids("PRODUCT", products.stream().map(row -> row.get("product_ref")).toList());
        Map<String, UUID> skuIds = ids("SKU", skus.stream().map(row -> row.get("sku_ref")).toList());
        Map<String, UUID> offerIds = ids("OFFER", offers.stream().map(OpsImportService::offerKey).toList());
        Map<String, List<Row>> specsByProduct = group(tables.rows(ImportTemplates.PRODUCT_SPECS), "product_ref");
        Map<String, List<Row>> packsByProduct = group(tables.rows(ImportTemplates.PACK_ITEMS), "product_ref");
        Map<String, List<Row>> skusByProduct = group(skus, "product_ref");
        Map<String, List<Row>> offersBySku = group(offers, "sku_ref");
        boolean specsGiven = tables.has(ImportTemplates.PRODUCT_SPECS);
        boolean packsGiven = tables.has(ImportTemplates.PACK_ITEMS);
        Map<String, Organization> merchants = result.organizations();

        List<OpsImportPorts.ProductRecord> records = new ArrayList<>();
        for (Row product : products) {
            String ref = product.get("product_ref");
            List<OpsImportPorts.SkuRecord> skuRecords = new ArrayList<>();
            for (Row sku : skusByProduct.getOrDefault(ref, List.of())) {
                String skuRef = sku.get("sku_ref");
                List<OpsImportPorts.OfferRecord> offerRecords = offersBySku.getOrDefault(skuRef, List.of()).stream()
                        .map(offer -> new OpsImportPorts.OfferRecord(offerIds.get(offerKey(offer)), offerKey(offer),
                                merchants.get(offer.get("merchant_organization_code")).id(),
                                offer.get("merchant_kind"), blankToNull(offer.get("city_code")),
                                offer.amount("price_amount_minor"), offer.get("price_unit"),
                                offer.integer("stock_quantity"), offer.bool("express_available"),
                                offer.amount("shipping_fee_minor"), blankToNull(offer.get("pickup_point")),
                                blankToNull(offer.get("delivery_note")), blankToNull(offer.get("shipping_note")),
                                blankToNull(offer.get("fulfilled_from")), offer.get("status")))
                        .toList();
                skuRecords.add(new OpsImportPorts.SkuRecord(skuIds.get(skuRef), skuRef, sku.get("name"),
                        sku.sortOrder("sort_order"), offerRecords));
            }
            records.add(new OpsImportPorts.ProductRecord(productIds.get(ref), ref, product.get("category"),
                    product.get("name"), blankToNull(product.get("summary")), blankToNull(product.get("cover_url")),
                    product.get("sale_mode"), blankToNull(product.get("experience_badge")),
                    product.sortOrder("sort_order"), product.get("status"),
                    ImportTemplates.split(product.get("selling_points")),
                    specsGiven ? specsByProduct.getOrDefault(ref, List.of()).stream()
                            .map(row -> new OpsImportPorts.SpecRecord(row.get("label"), row.get("value"),
                                    row.sortOrder("sort_order")))
                            .toList() : null,
                    packsGiven ? packsByProduct.getOrDefault(ref, List.of()).stream()
                            .map(row -> new OpsImportPorts.PackItemRecord(row.get("name"), row.integer("quantity"),
                                    row.sortOrder("sort_order")))
                            .toList() : null,
                    skuRecords));
        }
        return records;
    }

    /** 供给的编号：规格编号|商家编码|城市编码（全国为 *）。 */
    static String offerKey(Row offer) {
        return offer.get("sku_ref") + "|" + offer.get("merchant_organization_code") + "|"
                + (offer.has("city_code") ? offer.get("city_code") : "*");
    }

    /** 以前导入过的编号用对照表里的 ID，新的生成一个。 */
    private Map<String, UUID> ids(String refKind, List<String> references) {
        List<String> distinct = references.stream().distinct().toList();
        Map<String, UUID> ids = new HashMap<>();
        if (!distinct.isEmpty()) {
            mapper.findRefs(refKind, distinct).forEach(row -> ids.put(row.reference(), row.targetId()));
        }
        for (String reference : distinct) {
            ids.computeIfAbsent(reference, ignored -> UUID.randomUUID());
        }
        return ids;
    }

    private static Map<String, List<Row>> group(List<Row> rows, String column) {
        Map<String, List<Row>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.get(column), ignored -> new ArrayList<>()).add(row));
        return grouped;
    }

    // ---- 内部 ----

    private ImportValidator validator() {
        return new ImportValidator(lookups, references -> {
            Map<String, UUID> found = new HashMap<>();
            mapper.findRefs(OpsImportRefKind.PRODUCT.name(), List.copyOf(references))
                    .forEach(row -> found.put(row.reference(), row.targetId()));
            return found;
        }, ImportValidator.beijingToday(clock));
    }

    private OpsImportBatchView view(UUID batchId, List<OpsImportReviewItemView> reviewItems,
                                    List<OpsImportCreatedAccountView> createdAccounts) {
        BatchRow row = batchId == null ? null : mapper.findBatch(batchId);
        if (row == null) {
            throw new OpsImportException(Error.NOT_FOUND);
        }
        List<OpsImportFileView> fileViews = mapper.findFiles(batchId).stream()
                .map(file -> new OpsImportFileView(file.fileName(), file.rowCount()))
                .toList();
        List<OpsImportIssueView> issues = mapper.findIssues(batchId).stream()
                .map(issue -> new OpsImportIssueView(issue.fileName(), issue.line(), issue.columnName(),
                        issue.message()))
                .toList();
        List<OpsImportChangeView> changes = mapper.findChanges(batchId).stream()
                .map(change -> new OpsImportChangeView(OpsImportRefKind.valueOf(change.refKind()), change.reference(),
                        change.targetId(), OpsImportChangeType.valueOf(change.changeType())))
                .toList();
        return new OpsImportBatchView(row.id(), OpsImportKind.valueOf(row.kind()), OpsImportStatus.valueOf(row.status()),
                row.fileSha256().strip(), fileViews, row.rowCount(), row.issueCount(), issues, reviewItems, changes,
                createdAccounts, row.duplicateOf(), row.checkedByName(), row.checkedAt(), row.importedByName(),
                row.importedAt(), row.retainedUntil());
    }

    /** 文件名只留最后一段；1–12 个文件、每个 ≤2 MB、合计 ≤10 MB、不能同名、不能是空文件。 */
    static List<ImportValidator.Upload> normalize(List<UploadedFile> uploads) {
        if (uploads == null || uploads.isEmpty() || uploads.size() > MAX_FILES) {
            throw new IllegalArgumentException("1–12 files are required");
        }
        long total = 0;
        Set<String> names = new HashSet<>();
        List<ImportValidator.Upload> normalized = new ArrayList<>();
        for (UploadedFile upload : uploads) {
            if (upload == null || upload.content() == null || upload.content().length == 0
                    || upload.content().length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file size is invalid");
            }
            total += upload.content().length;
            String raw = upload.fileName() == null ? "" : upload.fileName().replace('\\', '/');
            String name = raw.substring(raw.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_FILE_NAME
                    || !names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("file names are invalid");
            }
            normalized.add(new ImportValidator.Upload(name, upload.content()));
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("files are too large");
        }
        return normalized;
    }

    /** 按文件名排好后，名称和内容一起算摘要：同样的一组文件，上传顺序不同也算同一份。 */
    static String digest(List<ImportValidator.Upload> uploads) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            uploads.stream().sorted(Comparator.comparing(ImportValidator.Upload::fileName)).forEach(upload -> {
                digest.update(upload.fileName().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Integer.toString(upload.content().length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(upload.content());
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static OpsImportKind kind(String code) {
        try {
            return OpsImportKind.valueOf(code == null ? "" : code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new OpsImportException(Error.NOT_FOUND);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String clip(String value, int max) {
        return value.codePointCount(0, value.length()) <= max
                ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static void requireActor(Actor actor) {
        if (actor == null || actor.accountId() == null || actor.displayName() == null
                || actor.displayName().isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** 上传的一个文件。 */
    public record UploadedFile(String fileName, byte[] content) {
    }
}
