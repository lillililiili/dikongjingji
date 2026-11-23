package com.lowaltitude.inspection.identity.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.identity.application.OnboardingException.Error;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.AccountRoleRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.AccountRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.AttachmentRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.OrganizationRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper.QualificationRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“机构入驻与资质审核”（S3-1，契约 contracts/openapi-ops-organization.yaml 1.0.0，迁移 V007）。
 *
 * <ul>
 *   <li>平台运营新建机构（经营机构或企业客户，带所在城市），同一个幂等键只建一家。</li>
 *   <li>资质资料只存文字（主体名称、统一社会信用代码、经营范围、行业资质说明）和附件元数据；资料或附件有改动就回到待审核，
 *       上一次的审核结果作废，App 上的“资质已审核”先撤下。</li>
 *   <li>审核：只能审待审核的那一版（带版本号）；通过要至少一份附件，写 identity_organization.qualification_reviewed_at；
 *       驳回要写原因，并清空审核时间。</li>
 *   <li>账号：给机构建账号（默认机构就是这家），服务端生成一次性的初始口令，只返回这一次；授机构角色（按机构类型限定），
 *       角色变了令牌版本加一，旧令牌立刻失效。</li>
 *   <li>每一步都写一条操作记录（操作人、时间、说明），详情页能看到。</li>
 * </ul>
 * 只有平台运营能调，角色由控制器检查。
 */
public class OrganizationOnboardingService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_NAME = 60;
    public static final int MAX_SUBJECT_NAME = 160;
    public static final int MAX_SCOPE = 500;
    public static final int MAX_NOTE = 500;
    public static final int MAX_REJECT_REASON = 200;
    public static final int MAX_DISPLAY_NAME = 40;
    public static final int MAX_FILE_NAME = 120;
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final int MAX_ATTACHMENTS = 10;
    public static final int OPERATION_HISTORY_LIMIT = 50;
    public static final int INITIAL_PASSWORD_LENGTH = 12;
    /** 统一社会信用代码：18 位数字或大写字母（不含 I、O、Z、S、V）。只校验格式，不做校验位计算。 */
    static final Pattern LICENSE_NUMBER = Pattern.compile("^[0-9A-HJ-NPQRTUWXY]{18}$");
    /** 登录名：小写字母开头，4–32 位小写字母、数字、下划线、短横线。 */
    static final Pattern LOGIN_NAME = Pattern.compile("^[a-z][a-z0-9_-]{3,31}$");
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final Map<String, String> ROLE_LABELS = Map.of(
            "PROVIDER_OPERATOR", "服务商操作员",
            "LIVE_HOST", "直播主办",
            "CUSTOMER", "客户");

    private final OrganizationOnboardingMapper mapper;
    private final OnboardingPorts.Cities cities;
    private final OnboardingPorts.PasswordHasher hasher;
    private final OnboardingPorts.QualificationFiles files;
    private final Clock clock;
    private final SecureRandom random;

    public OrganizationOnboardingService(
            OrganizationOnboardingMapper mapper,
            OnboardingPorts.Cities cities,
            OnboardingPorts.PasswordHasher hasher,
            OnboardingPorts.QualificationFiles files
    ) {
        this(mapper, cities, hasher, files, Clock.systemUTC(), new SecureRandom());
    }

    OrganizationOnboardingService(
            OrganizationOnboardingMapper mapper,
            OnboardingPorts.Cities cities,
            OnboardingPorts.PasswordHasher hasher,
            OnboardingPorts.QualificationFiles files,
            Clock clock,
            SecureRandom random
    ) {
        this.mapper = mapper;
        this.cities = cities;
        this.hasher = hasher;
        this.files = files;
        this.clock = clock;
        this.random = random;
    }

    // ---- 机构 ----

    @Transactional(readOnly = true)
    public OpsOrganizationPageView organizations(String keyword, String typeCode, String qualificationStatusCode,
                                                 int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String filterKeyword = keyword(keyword);
        String type = typeCode == null || typeCode.isBlank()
                ? null : parse(OpsOrganizationType.class, typeCode, "type").name();
        String status = qualificationStatusCode == null || qualificationStatusCode.isBlank()
                ? null : parse(OpsQualificationStatus.class, qualificationStatusCode, "qualification status").name();
        long total = mapper.countOrganizations(filterKeyword, type, status);
        List<OpsOrganizationView> items = mapper.findOrganizations(filterKeyword, type, status, size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(this::organizationView)
                .toList();
        return new OpsOrganizationPageView(page, size, total, (long) page * size < total, items);
    }

    /** 新建机构。同一个幂等键、同样的内容再提交，返回第一次建的那家（created 为 false）；内容不同时拒绝。 */
    @Transactional
    public OpsOrganizationCreated create(UUID idempotencyKey, String typeCode, String name, String cityCode,
                                         Operator operator) {
        requireOperator(operator);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        OpsOrganizationType type = parse(OpsOrganizationType.class, typeCode, "type");
        String organizationName = text(name, MAX_NAME, "name");
        String city = city(cityCode);
        UUID existing = mapper.findOrganizationByCreateKey(idempotencyKey);
        if (existing != null) {
            OrganizationRow row = mapper.findOrganization(existing);
            if (row == null || !row.organizationType().equals(type.name())
                    || !row.organizationName().equals(organizationName) || !city.equals(row.cityCode())) {
                throw new OnboardingException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new OpsOrganizationCreated(detail(existing), false);
        }
        OffsetDateTime at = now();
        UUID id = mapper.insertOrganization(organizationCode(at), organizationName, type.name(), city, idempotencyKey,
                at);
        if (id == null) {
            // 同一个键的另一次提交刚刚写进去了。
            return create(idempotencyKey, typeCode, name, cityCode, operator);
        }
        record(id, OpsOrganizationAction.CREATE,
                "新建" + typeLabel(type) + "“" + organizationName + "”，城市 " + cityLabel(city), operator, at);
        return new OpsOrganizationCreated(detail(id), true);
    }

    @Transactional(readOnly = true)
    public OpsOrganizationDetailView organization(UUID organizationId) {
        return detail(requireOrganization(organizationId).organizationId());
    }

    // ---- 资质资料与审核 ----

    /**
     * 录入或修改资质资料。第一次录入不带版本号；修改要带详情里读到的版本号。保存后都是“待审核”，上一次的审核结果作废。
     */
    @Transactional
    public OpsOrganizationDetailView submitQualification(UUID organizationId, QualificationDraft draft,
                                                         Operator operator) {
        requireOperator(operator);
        if (draft == null) {
            throw new IllegalArgumentException("qualification is required");
        }
        String subjectName = text(draft.subjectName(), MAX_SUBJECT_NAME, "subject name");
        String licenseNumber = draft.licenseNumber() == null ? "" : draft.licenseNumber().strip()
                .toUpperCase(Locale.ROOT);
        if (!LICENSE_NUMBER.matcher(licenseNumber).matches()) {
            throw new IllegalArgumentException("license number is invalid");
        }
        String scope = optionalText(draft.businessScope(), MAX_SCOPE, "business scope");
        String note = optionalText(draft.qualificationNote(), MAX_NOTE, "qualification note");
        UUID id = lock(organizationId);
        QualificationRow current = mapper.findQualification(id);
        OffsetDateTime at = now();
        if (current == null) {
            if (draft.version() != null) {
                throw new OnboardingException(Error.VERSION_CONFLICT);
            }
            mapper.insertQualification(id, subjectName, licenseNumber, scope, note, operator.accountId(), at);
        } else {
            if (draft.version() == null || current.version() != draft.version()
                    || mapper.updateQualification(id, subjectName, licenseNumber, scope, note, operator.accountId(),
                    draft.version(), at) != 1) {
                throw new OnboardingException(Error.VERSION_CONFLICT);
            }
        }
        withdrawReview(id, at);
        record(id, OpsOrganizationAction.QUALIFICATION_SUBMIT,
                (current == null ? "录入资质资料" : "修改资质资料") + "：主体“" + subjectName + "”，统一社会信用代码 "
                        + licenseNumber + "，进入待审核", operator, at);
        return detail(id);
    }

    /** 审核待审核的资料：通过（至少一份附件）或驳回（写原因）。 */
    @Transactional
    public OpsOrganizationDetailView review(UUID organizationId, String decisionCode, String reason, Integer version,
                                            Operator operator) {
        requireOperator(operator);
        OpsReviewDecision decision = parse(OpsReviewDecision.class, decisionCode, "decision");
        String rejectReason = decision == OpsReviewDecision.REJECT
                ? text(reason, MAX_REJECT_REASON, "reason")
                : optionalText(reason, MAX_REJECT_REASON, "reason");
        if (version == null) {
            throw new IllegalArgumentException("version is required");
        }
        UUID id = lock(organizationId);
        QualificationRow current = mapper.findQualification(id);
        if (current == null || !OpsQualificationStatus.SUBMITTED.name().equals(current.status())) {
            throw new OnboardingException(Error.QUALIFICATION_NOT_PENDING);
        }
        if (current.version() != version) {
            throw new OnboardingException(Error.VERSION_CONFLICT);
        }
        if (decision == OpsReviewDecision.APPROVE && mapper.findAttachments(id).isEmpty()) {
            throw new OnboardingException(Error.QUALIFICATION_INCOMPLETE);
        }
        OffsetDateTime at = now();
        String status = decision == OpsReviewDecision.APPROVE
                ? OpsQualificationStatus.APPROVED.name() : OpsQualificationStatus.REJECTED.name();
        if (mapper.reviewQualification(id, status, decision == OpsReviewDecision.REJECT ? rejectReason : null,
                operator.accountId(), version, at) != 1) {
            throw new OnboardingException(Error.VERSION_CONFLICT);
        }
        mapper.updateQualificationReviewedAt(id, decision == OpsReviewDecision.APPROVE ? at : null, at);
        if (decision == OpsReviewDecision.APPROVE) {
            record(id, OpsOrganizationAction.QUALIFICATION_APPROVE,
                    "资质审核通过" + (rejectReason == null ? "" : "：" + rejectReason), operator, at);
        } else {
            record(id, OpsOrganizationAction.QUALIFICATION_REJECT, "资质审核驳回：" + rejectReason, operator, at);
        }
        return detail(id);
    }

    // ---- 附件 ----

    /** 上传一份资质附件（PDF、JPEG、PNG，≤10 MB，每家最多 10 份）。类型按文件头判断，不信客户端给的类型。 */
    @Transactional
    public OpsOrganizationDetailView addAttachment(UUID organizationId, String fileName, byte[] content,
                                                   Operator operator) {
        requireOperator(operator);
        String name = fileName(fileName);
        if (content == null || content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file size is invalid");
        }
        String contentType = sniff(content);
        UUID id = lock(organizationId);
        if (mapper.findAttachments(id).size() >= MAX_ATTACHMENTS) {
            throw new OnboardingException(Error.ATTACHMENT_LIMIT);
        }
        UUID attachmentId = UUID.randomUUID();
        String key = "organizations/" + id + "/qualification/" + attachmentId;
        try {
            files.put(key, content);
        } catch (OnboardingPorts.FileStoreUnavailableException unavailable) {
            throw new OnboardingException(Error.FILE_STORE_UNAVAILABLE);
        }
        OffsetDateTime at = now();
        mapper.insertAttachment(attachmentId, id, name, contentType, content.length, sha256(content), key,
                operator.accountId(), at);
        reopen(id, operator, at);
        record(id, OpsOrganizationAction.ATTACHMENT_ADD, "上传资质附件“" + name + "”", operator, at);
        return detail(id);
    }

    @Transactional
    public OpsOrganizationDetailView removeAttachment(UUID organizationId, UUID attachmentId, Operator operator) {
        requireOperator(operator);
        if (attachmentId == null) {
            throw new IllegalArgumentException("attachment is required");
        }
        UUID id = lock(organizationId);
        AttachmentRow attachment = mapper.findAttachment(id, attachmentId);
        if (attachment == null) {
            throw new OnboardingException(Error.NOT_FOUND);
        }
        OffsetDateTime at = now();
        mapper.removeAttachment(id, attachmentId, operator.accountId(), at);
        reopen(id, operator, at);
        record(id, OpsOrganizationAction.ATTACHMENT_REMOVE, "移除资质附件“" + attachment.fileName() + "”", operator, at);
        return detail(id);
    }

    @Transactional(readOnly = true)
    public OpsAttachmentContent attachmentContent(UUID organizationId, UUID attachmentId) {
        UUID id = requireOrganization(organizationId).organizationId();
        AttachmentRow attachment = attachmentId == null ? null : mapper.findAttachment(id, attachmentId);
        if (attachment == null) {
            throw new OnboardingException(Error.NOT_FOUND);
        }
        try {
            return new OpsAttachmentContent(attachment.fileName(), attachment.contentType(),
                    files.read(attachment.storageKey()));
        } catch (OnboardingPorts.FileStoreUnavailableException unavailable) {
            throw new OnboardingException(Error.FILE_STORE_UNAVAILABLE);
        }
    }

    // ---- 账号与角色 ----

    /** 给机构建一个账号并授角色（至少一个）。初始口令由服务端生成，只在返回里出现这一次。 */
    @Transactional
    public OpsAccountCreatedView createAccount(UUID organizationId, String loginName, String displayName,
                                               Collection<String> roleCodes, Operator operator) {
        requireOperator(operator);
        String login = loginName == null ? "" : loginName.strip();
        if (!LOGIN_NAME.matcher(login).matches()) {
            throw new IllegalArgumentException("login name is invalid");
        }
        String name = text(displayName, MAX_DISPLAY_NAME, "display name");
        UUID id = lock(organizationId);
        OpsOrganizationType type = OpsOrganizationType.valueOf(mapper.findOrganization(id).organizationType());
        Set<OpsGrantableRole> roles = roles(roleCodes, type);
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
        OffsetDateTime at = now();
        String password = initialPassword();
        UUID accountId = mapper.insertAccount(login, name, hasher.hash(password), id, at);
        if (accountId == null) {
            throw new OnboardingException(Error.LOGIN_NAME_TAKEN);
        }
        mapper.insertMember(id, accountId, at);
        for (OpsGrantableRole role : roles) {
            mapper.grantRole(accountId, role.name(), role.organizationScoped() ? id : null, at);
        }
        record(id, OpsOrganizationAction.ACCOUNT_CREATE,
                "新建账号 " + login + "（" + name + "），角色：" + roleLabels(roles), operator, at);
        OpsAccountView account = accounts(id).stream()
                .filter(candidate -> candidate.accountId().equals(accountId))
                .findFirst()
                .orElseThrow();
        return new OpsAccountCreatedView(account, password);
    }

    /**
     * 把账号在这家机构能授的角色改成给定的这些（可以为空，表示全部收回）；不在可授范围里的角色不动。
     * 有改动时令牌版本加一，账号要重新登录。
     */
    @Transactional
    public OpsOrganizationDetailView replaceRoles(UUID organizationId, UUID accountId, Collection<String> roleCodes,
                                                  Operator operator) {
        requireOperator(operator);
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
        UUID id = lock(organizationId);
        OpsOrganizationType type = OpsOrganizationType.valueOf(mapper.findOrganization(id).organizationType());
        Set<OpsGrantableRole> wanted = roles(roleCodes, type);
        AccountRow account = mapper.lockAccount(id, accountId);
        if (account == null) {
            throw new OnboardingException(Error.NOT_FOUND);
        }
        OffsetDateTime at = now();
        Set<String> current = mapper.findAccountRoles(id, List.of(accountId), at).stream()
                .map(AccountRoleRow::roleCode)
                .collect(Collectors.toSet());
        List<OpsGrantableRole> granted = new ArrayList<>();
        List<OpsGrantableRole> revoked = new ArrayList<>();
        for (OpsGrantableRole role : OpsGrantableRole.forType(type)) {
            UUID scope = role.organizationScoped() ? id : null;
            if (wanted.contains(role) && !current.contains(role.name())) {
                mapper.grantRole(accountId, role.name(), scope, at);
                granted.add(role);
            } else if (!wanted.contains(role) && current.contains(role.name())) {
                mapper.revokeRole(accountId, role.name(), scope, at);
                revoked.add(role);
            }
        }
        if (!granted.isEmpty() || !revoked.isEmpty()) {
            mapper.bumpTokenVersion(accountId, at);
            List<String> parts = new ArrayList<>();
            if (!granted.isEmpty()) {
                parts.add("授予 " + roleLabels(granted));
            }
            if (!revoked.isEmpty()) {
                parts.add("收回 " + roleLabels(revoked));
            }
            record(id, OpsOrganizationAction.ROLES_CHANGE,
                    "账号 " + account.loginName() + "：" + String.join("，", parts) + "（需重新登录）", operator, at);
        }
        return detail(id);
    }

    // ---- 内部 ----

    private OpsOrganizationDetailView detail(UUID id) {
        OrganizationRow row = mapper.findOrganization(id);
        if (row == null) {
            throw new OnboardingException(Error.NOT_FOUND);
        }
        QualificationRow qualification = mapper.findQualification(id);
        List<OpsAttachmentView> attachments = mapper.findAttachments(id).stream()
                .map(attachment -> new OpsAttachmentView(attachment.id(), attachment.fileName(),
                        attachment.contentType(), attachment.sizeBytes(), attachment.uploadedAt()))
                .toList();
        List<OpsOperationView> operations = mapper.findOperations(id, OPERATION_HISTORY_LIMIT).stream()
                .map(operation -> new OpsOperationView(operation.id(),
                        OpsOrganizationAction.valueOf(operation.action()), operation.detail(),
                        operation.operatorName(), operation.occurredAt()))
                .toList();
        OpsOrganizationType type = OpsOrganizationType.valueOf(row.organizationType());
        return new OpsOrganizationDetailView(organizationView(row),
                qualification == null ? null : new OpsQualificationView(qualification.subjectName(),
                        qualification.licenseNumber(), qualification.businessScope(),
                        qualification.qualificationNote(), OpsQualificationStatus.valueOf(qualification.status()),
                        qualification.submittedAt(), qualification.reviewedAt(), qualification.rejectReason(),
                        qualification.version()),
                attachments, accounts(id), OpsGrantableRole.forType(type), operations);
    }

    private List<OpsAccountView> accounts(UUID organizationId) {
        List<AccountRow> rows = mapper.findAccounts(organizationId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, Set<String>> roles = new LinkedHashMap<>();
        for (AccountRoleRow role : mapper.findAccountRoles(organizationId,
                rows.stream().map(AccountRow::accountId).toList(), now())) {
            roles.computeIfAbsent(role.accountId(), ignored -> new TreeSet<>()).add(role.roleCode());
        }
        return rows.stream()
                .map(row -> new OpsAccountView(row.accountId(), row.loginName(), row.displayName(), row.status(),
                        List.copyOf(roles.getOrDefault(row.accountId(), Set.of())), row.createdAt()))
                .toList();
    }

    private OpsOrganizationView organizationView(OrganizationRow row) {
        return new OpsOrganizationView(row.organizationId(), row.organizationCode(), row.organizationName(),
                OpsOrganizationType.valueOf(row.organizationType()), row.cityCode(),
                row.cityCode() == null ? null : cities.cityName(row.cityCode()).orElse(null), row.status(),
                OpsQualificationStatus.valueOf(row.qualificationStatus()), row.qualificationReviewedAt(),
                row.createdAt());
    }

    private OrganizationRow requireOrganization(UUID organizationId) {
        OrganizationRow row = organizationId == null ? null : mapper.findOrganization(organizationId);
        if (row == null) {
            throw new OnboardingException(Error.NOT_FOUND);
        }
        return row;
    }

    private UUID lock(UUID organizationId) {
        UUID id = organizationId == null ? null : mapper.lockOrganization(organizationId);
        if (id == null) {
            throw new OnboardingException(Error.NOT_FOUND);
        }
        return id;
    }

    /** 资料改了：上一次的审核结果作废，App 上的“资质已审核”先撤下。 */
    private void withdrawReview(UUID id, OffsetDateTime at) {
        mapper.updateQualificationReviewedAt(id, null, at);
    }

    /** 附件改了：已通过或已驳回的资料回到待审核（还没录资料时什么都不动）。 */
    private void reopen(UUID id, Operator operator, OffsetDateTime at) {
        if (mapper.reopenQualification(id, operator.accountId(), at) > 0) {
            withdrawReview(id, at);
        } else if (mapper.findQualification(id) == null) {
            // 没有资料记录、但以前线下写过审核时间的机构：附件变了，同样撤下“资质已审核”，等录入资料后再审。
            withdrawReview(id, at);
        }
    }

    private void record(UUID organizationId, OpsOrganizationAction action, String detail, Operator operator,
                        OffsetDateTime at) {
        mapper.insertOperation(organizationId, action.name(), clip(detail, 500), operator.accountId(),
                clip(operator.displayName(), 80), at);
    }

    private Set<OpsGrantableRole> roles(Collection<String> roleCodes, OpsOrganizationType type) {
        if (roleCodes == null) {
            throw new IllegalArgumentException("roles are required");
        }
        Set<OpsGrantableRole> roles = EnumSet.noneOf(OpsGrantableRole.class);
        for (String code : roleCodes) {
            OpsGrantableRole role;
            try {
                role = parse(OpsGrantableRole.class, code, "role");
            } catch (IllegalArgumentException unknown) {
                throw new OnboardingException(Error.ROLE_NOT_GRANTABLE);
            }
            if (role.organizationType() != type) {
                throw new OnboardingException(Error.ROLE_NOT_GRANTABLE);
            }
            roles.add(role);
        }
        return roles;
    }

    private String city(String cityCode) {
        if (cityCode == null || !CITY_CODE.matcher(cityCode).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        if (cities.cityName(cityCode).isEmpty()) {
            throw new OnboardingException(Error.CITY_UNKNOWN);
        }
        return cityCode;
    }

    private String cityLabel(String cityCode) {
        return cities.cityName(cityCode).map(name -> name + "（" + cityCode + "）").orElse(cityCode);
    }

    /** 机构编码：ORG-北京日期-8 位随机十六进制，只给人看、给对账用，不当作关联键。 */
    private String organizationCode(OffsetDateTime at) {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return "ORG-" + at.atZoneSameInstant(BEIJING).format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    /** 12 位初始口令，大小写字母和数字都有，去掉了容易看错的 0、O、1、l、I。 */
    String initialPassword() {
        while (true) {
            StringBuilder password = new StringBuilder(INITIAL_PASSWORD_LENGTH);
            for (int i = 0; i < INITIAL_PASSWORD_LENGTH; i++) {
                password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
            }
            String value = password.toString();
            if (value.chars().anyMatch(Character::isUpperCase) && value.chars().anyMatch(Character::isLowerCase)
                    && value.chars().anyMatch(Character::isDigit)) {
                return value;
            }
        }
    }

    /** 按文件头判断类型；不是 PDF、JPEG、PNG 的一律拒绝。 */
    static String sniff(byte[] content) {
        if (startsWith(content, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
            return "application/pdf";
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        throw new IllegalArgumentException("file type is not supported");
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 文件名只留最后一段，去掉控制字符和路径分隔符，1–120 字。 */
    static String fileName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("file name is required");
        }
        return clip(name, MAX_FILE_NAME);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String typeLabel(OpsOrganizationType type) {
        return type == OpsOrganizationType.PROVIDER ? "经营机构" : "企业客户";
    }

    private static String roleLabels(Collection<OpsGrantableRole> roles) {
        return roles.stream().map(role -> ROLE_LABELS.get(role.name())).collect(Collectors.joining("、"));
    }

    static String keyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String cleaned = keyword.replaceAll("[%_\\\\]", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_NAME) {
            throw new IllegalArgumentException("keyword is too long");
        }
        return cleaned;
    }

    private static String text(String value, int max, String what) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
    }

    private static String optionalText(String value, int max, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String code, String what) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        try {
            return Enum.valueOf(type, code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(what + " is invalid", unknown);
        }
    }

    private static String clip(String value, int max) {
        return value.codePointCount(0, value.length()) <= max
                ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static void requireOperator(Operator operator) {
        if (operator == null || operator.accountId() == null || operator.displayName() == null
                || operator.displayName().isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** 谁在操作（平台运营的账号和显示名），写进操作记录。 */
    public record Operator(UUID accountId, String displayName) {
    }

    /** 资质资料。version：第一次录入不带，修改时带详情里读到的版本号。 */
    public record QualificationDraft(String subjectName, String licenseNumber, String businessScope,
                                     String qualificationNote, Integer version) {
    }
}
