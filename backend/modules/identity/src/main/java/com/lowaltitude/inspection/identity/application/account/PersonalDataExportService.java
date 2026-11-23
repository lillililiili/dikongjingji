package com.lowaltitude.inspection.identity.application.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.application.account.AccountViews.DataExportOverviewView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.DataExportView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.DataSectionView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.DownloadIntentView;
import com.lowaltitude.inspection.identity.application.account.AccountViews.ExportFile;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.ExportRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人信息查看与导出（S5-8，§5“账号”：个人信息导出生成文件，受控下载）。
 *
 * <ul>
 *   <li>可导出的项：账号信息、企业身份（本模块），参与人、收货地址、订单（别的模块经 {@link AccountPorts.PersonalDataSource}）。</li>
 *   <li>申请要验证当前手机号；同一时间只能有一份生成中的。服务端定时任务生成 ZIP（每项一个 JSON，加 manifest.json），
 *       放受控存储，只存元数据；副本保留 7 天，过期删除文件。</li>
 *   <li>下载：本人换一个 5 分钟有效、只能用一次的凭据，凭据随链接给出；下载接口不要登录头，凭据就是授权。</li>
 * </ul>
 */
public class PersonalDataExportService {

    public static final String ACCOUNT = "ACCOUNT";
    public static final String ENTERPRISES = "ENTERPRISES";
    public static final Duration RETENTION = Duration.ofDays(7);
    public static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    static final int LISTED = 10;

    private final AccountMapper mapper;
    private final SmsCodeService codes;
    private final EnterpriseService enterprises;
    private final List<AccountPorts.PersonalDataSource> sources;
    private final AccountPorts.ExportFiles files;
    private final AccountPorts.JsonWriter json;
    private final Clock clock;
    private final SecureRandom random;

    public PersonalDataExportService(AccountMapper mapper, SmsCodeService codes, EnterpriseService enterprises,
                                     List<AccountPorts.PersonalDataSource> sources, AccountPorts.ExportFiles files,
                                     AccountPorts.JsonWriter json, Clock clock, SecureRandom random) {
        this.mapper = mapper;
        this.codes = codes;
        this.enterprises = enterprises;
        this.sources = List.copyOf(sources);
        this.files = files;
        this.json = json;
        this.clock = clock;
        this.random = random;
    }

    @Transactional(readOnly = true)
    public DataExportOverviewView overview(UUID userId) {
        UserRow user = requireUser(userId);
        List<DataSectionView> sections = new ArrayList<>();
        sections.add(new DataSectionView(ACCOUNT, "账号信息", "显示名、手机号、注册方式和当前身份", null));
        for (AccountPorts.PersonalDataSource source : sources) {
            sections.add(new DataSectionView(source.code(), source.title(), source.description(),
                    source.count(userId)));
        }
        sections.add(new DataSectionView(ENTERPRISES, "企业身份", "所在企业和角色",
                (long) mapper.findMyEnterprises(userId).size()));
        List<DataExportView> exports = mapper.findExports(userId, LISTED).stream().map(this::view).toList();
        return new DataExportOverviewView(PhoneNumbers.mask(user.mobile()), List.copyOf(sections), exports);
    }

    @Transactional(noRollbackFor = AccountException.class)
    public DataExportView request(UUID userId, List<String> requested, String code) {
        Set<String> available = availableCodes();
        if (requested == null || requested.isEmpty() || requested.size() > available.size()
                || !available.containsAll(requested) || new LinkedHashSet<>(requested).size() != requested.size()) {
            throw new AccountException(Error.INVALID);
        }
        UserRow user = mapper.lockUser(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new AccountException(Error.NOT_FOUND);
        }
        if (user.mobile() == null) {
            throw new AccountException(Error.PHONE_REQUIRED);
        }
        if (mapper.countPendingExports(userId) > 0) {
            throw new AccountException(Error.EXPORT_IN_PROGRESS);
        }
        codes.verify(user.mobile(), SmsCodeService.EXPORT, code, userId);
        OffsetDateTime now = now();
        // 按固定顺序存，生成的文件和页面显示的顺序一致。
        String sections = String.join(",", available.stream().filter(requested::contains).toList());
        UUID exportId = mapper.insertExport(userId, sections, now);
        mapper.insertAudit(userId, "ACCOUNT_DATA_EXPORT_REQUEST", "DATA_EXPORT", exportId, null,
                UUID.randomUUID().toString(), now);
        return view(mapper.findExport(exportId, userId));
    }

    /**
     * 生成一份等着的导出（定时任务反复调用，直到返回 false）。存储不可用或某一项读取失败时这份记为失败，
     * 页面上可以重新申请。
     */
    @Transactional
    public boolean generateNext() {
        List<ExportRow> pending = mapper.lockPendingExports(1);
        if (pending.isEmpty()) {
            return false;
        }
        ExportRow row = pending.get(0);
        OffsetDateTime now = now();
        try {
            byte[] zip = build(row, now);
            String key = "data-exports/" + row.userId() + "/" + row.id() + ".zip";
            files.put(key, zip);
            mapper.markExportReady(row.id(), key, zip.length, sha256(zip), now, now.plus(RETENTION));
        } catch (IOException | RuntimeException failed) {
            // 存储不可用（ExportStoreUnavailableException）或某一项读取失败。
            mapper.markExportFailed(row.id());
        }
        return true;
    }

    @Transactional
    public DownloadIntentView createDownload(UUID userId, UUID exportId) {
        ExportRow row = mapper.findExport(exportId, userId);
        if (row == null) {
            throw new AccountException(Error.NOT_FOUND);
        }
        OffsetDateTime now = now();
        if (!"READY".equals(row.status()) || row.expiresAt() == null || !row.expiresAt().isAfter(now)) {
            throw new AccountException(Error.EXPORT_NOT_READY);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expiresAt = now.plus(DOWNLOAD_TTL);
        mapper.setDownloadToken(exportId, userId, tokenHash(token), expiresAt);
        return new DownloadIntentView("/api/v1/account/data-exports/" + exportId + "/file?token=" + token,
                fileName(row), expiresAt);
    }

    /** 用掉下载凭据取文件；凭据不对、过期、用过或副本已过期一律按不存在回答。 */
    @Transactional
    public ExportFile download(UUID exportId, String token) {
        if (exportId == null || token == null || token.length() < 32 || token.length() > 64) {
            throw new AccountException(Error.NOT_FOUND);
        }
        String key = mapper.consumeDownloadToken(exportId, tokenHash(token), now());
        if (key == null) {
            throw new AccountException(Error.NOT_FOUND);
        }
        byte[] content;
        try {
            content = files.read(key);
        } catch (AccountPorts.ExportStoreUnavailableException unavailable) {
            throw new AccountException(Error.EXPORT_UNAVAILABLE);
        }
        return new ExportFile("personal-data-" + exportId.toString().substring(0, 8) + ".zip", content);
    }

    /** 过期的副本删除文件、记为过期（定时任务）。 */
    @Transactional
    public int expireOld(int limit) {
        int expired = 0;
        for (ExportRow row : mapper.findExpiredExports(now(), limit)) {
            try {
                if (row.storageKey() != null) {
                    files.delete(row.storageKey());
                }
            } catch (AccountPorts.ExportStoreUnavailableException unavailable) {
                // 存储暂时不可用：仍记为过期，凭据随之作废；文件留到存储恢复后人工清理。
            }
            expired += mapper.markExportExpired(row.id());
        }
        return expired;
    }

    private byte[] build(ExportRow row, OffsetDateTime now) throws IOException {
        List<String> sections = Arrays.asList(row.sections().split(","));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("exportId", row.id().toString());
        manifest.put("generatedAt", now.toString());
        manifest.put("sections", sections);
        manifest.put("note", "这是你在平台保存的个人信息副本，含敏感信息，请妥善保管。");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            put(zip, "manifest.json", manifest);
            for (String section : sections) {
                put(zip, section.toLowerCase(Locale.ROOT) + ".json", sectionContent(row.userId(), section));
            }
        }
        return buffer.toByteArray();
    }

    private Object sectionContent(UUID userId, String section) {
        if (ACCOUNT.equals(section)) {
            UserRow user = mapper.findUser(userId);
            CurrentIdentity identity = enterprises.currentIdentity(userId);
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("accountId", userId.toString());
            account.put("displayName", user.displayName());
            account.put("phone", user.mobile());
            account.put("registeredVia", user.registeredVia());
            account.put("currentIdentity", identity.isEnterprise() ? identity.enterpriseName() : "个人");
            return account;
        }
        if (ENTERPRISES.equals(section)) {
            return mapper.findMyEnterprises(userId).stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("enterpriseName", row.organizationName());
                item.put("creditCode", row.creditCode());
                item.put("role", row.memberRole());
                return item;
            }).toList();
        }
        for (AccountPorts.PersonalDataSource source : sources) {
            if (source.code().equals(section)) {
                return source.export(userId);
            }
        }
        throw new IllegalStateException("unknown section " + section);
    }

    private void put(ZipOutputStream zip, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(json.write(value));
        zip.closeEntry();
    }

    private Set<String> availableCodes() {
        Set<String> codesInOrder = new LinkedHashSet<>();
        codesInOrder.add(ACCOUNT);
        sources.forEach(source -> codesInOrder.add(source.code()));
        codesInOrder.add(ENTERPRISES);
        return codesInOrder;
    }

    private DataExportView view(ExportRow row) {
        return new DataExportView(row.id(), row.status(), List.of(row.sections().split(",")), row.requestedAt(),
                row.readyAt(), row.expiresAt(), row.sizeBytes());
    }

    private static String fileName(ExportRow row) {
        return "personal-data-" + row.id().toString().substring(0, 8) + ".zip";
    }

    private UserRow requireUser(UUID userId) {
        UserRow user = userId == null ? null : mapper.findUser(userId);
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new AccountException(Error.NOT_FOUND);
        }
        return user;
    }

    static String tokenHash(String token) {
        return sha256(("data-export:" + token).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
