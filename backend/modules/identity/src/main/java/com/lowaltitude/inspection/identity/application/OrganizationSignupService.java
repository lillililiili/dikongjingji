package com.lowaltitude.inspection.identity.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.identity.application.OnboardingException.Error;
import com.lowaltitude.inspection.identity.application.OrganizationOnboardingService.Operator;
import com.lowaltitude.inspection.identity.application.OrganizationOnboardingService.QualificationDraft;
import com.lowaltitude.inspection.identity.application.account.AccountException;
import com.lowaltitude.inspection.identity.application.account.PhoneNumbers;
import com.lowaltitude.inspection.identity.application.account.SmsCodeService;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家自助入驻（契约 contracts/openapi-organization-signup.yaml 1.0.0，迁移 V012）。
 *
 * <ul>
 *   <li>申请（不登录）：机构名称和所在城市、营业执照上的主体名称和统一社会信用代码、联系人姓名和手机号（短信验证码）、
 *       自己设的登录名和口令。一次建齐：经营机构（PROVIDER）、待审核的资质资料、默认机构是这家的账号，授服务商操作员
 *       （PROVIDER_OPERATOR）。同一个幂等键重放返回第一次建的。</li>
 *   <li>之后商家用这个登录名登录工作台，在“入驻资料”里补资质附件、改资料；这些沿用运营端机构入驻的同一套服务和规则
 *       （资料或附件一动就回到待审核），操作人记商家自己的账号。审核仍只在运营端“机构与资质”做。</li>
 *   <li>审核通过前账号已经能登录、能建草稿商品，但商品上架要等资质审核通过（商城的商家目录服务检查）。</li>
 * </ul>
 */
public class OrganizationSignupService {

    public static final int MAX_CONTACT_NAME = 40;
    public static final int MIN_PASSWORD = 8;
    public static final int MAX_PASSWORD = 64;
    /** 口令：8–64 位，至少一个字母和一个数字，不含空白。 */
    static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*[0-9])\\S{8,64}$");

    private final OrganizationOnboardingMapper mapper;
    private final OrganizationOnboardingService onboarding;
    private final OnboardingPorts.Cities cities;
    private final OnboardingPorts.PasswordHasher hasher;
    private final SmsCodeService smsCodes;
    private final Clock clock;
    private final SignupRequestSignature signatures;

    public OrganizationSignupService(OrganizationOnboardingMapper mapper, OrganizationOnboardingService onboarding,
                                     OnboardingPorts.Cities cities, OnboardingPorts.PasswordHasher hasher,
                                     SmsCodeService smsCodes, Clock clock, SignupRequestSignature signatures) {
        this.mapper = mapper;
        this.onboarding = onboarding;
        this.cities = cities;
        this.hasher = hasher;
        this.smsCodes = smsCodes;
        this.clock = clock;
        this.signatures = signatures;
    }

    /** 给联系人手机号发入驻验证码（频率、每日上限和锁定由短信验证码服务管）。 */
    @Transactional
    public void sendCode(String phone) {
        smsCodes.send(PhoneNumbers.require(phone), SmsCodeService.SIGNUP, null);
    }

    /** 申请入驻。先核验证码，再一次建齐机构、资质、账号和角色；同一个幂等键重放返回第一次的结果。 */
    @Transactional(noRollbackFor = AccountException.class)
    public OrganizationSignupCreated signUp(UUID idempotencyKey, SignupDraft draft) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        if (draft == null) {
            throw new IllegalArgumentException("signup is required");
        }
        String organizationName = text(draft.organizationName(), OrganizationOnboardingService.MAX_NAME,
                "organization name");
        String cityCode = draft.cityCode() == null ? "" : draft.cityCode().strip();
        if (!cityCode.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("city code is invalid");
        }
        String subjectName = text(draft.subjectName(), OrganizationOnboardingService.MAX_SUBJECT_NAME, "subject name");
        String licenseNumber = draft.licenseNumber() == null ? "" : draft.licenseNumber().strip().toUpperCase(Locale.ROOT);
        if (!OrganizationOnboardingService.LICENSE_NUMBER.matcher(licenseNumber).matches()) {
            throw new IllegalArgumentException("license number is invalid");
        }
        String scope = optionalText(draft.businessScope(), OrganizationOnboardingService.MAX_SCOPE, "business scope");
        String contactName = text(draft.contactName(), MAX_CONTACT_NAME, "contact name");
        String phone = PhoneNumbers.require(draft.phone());
        String loginName = draft.loginName() == null ? "" : draft.loginName().strip();
        if (!OrganizationOnboardingService.LOGIN_NAME.matcher(loginName).matches()) {
            throw new IllegalArgumentException("login name is invalid");
        }
        if (draft.password() == null || !PASSWORD.matcher(draft.password()).matches()) {
            throw new IllegalArgumentException("password is invalid");
        }

        String signature = signatures.sign(idempotencyKey, organizationName, cityCode, subjectName, licenseNumber,
                scope, contactName, phone, loginName, draft.password(), draft.smsCode());
        mapper.lockSignupKey(idempotencyKey);
        var receipt = mapper.findSignupReceipt(idempotencyKey);
        if (receipt != null) {
            boolean same = receipt.signature() == null
                    ? mapper.matchesLegacySignup(receipt.id(), organizationName, cityCode, subjectName, licenseNumber,
                            scope, contactName, phone, loginName)
                    : receipt.signature().equals(signature);
            if (!same) {
                throw new OnboardingException(Error.IDEMPOTENCY_CONFLICT);
            }
            return signupResult(receipt.id(), organizationName, loginName, false);
        }
        if (cities.cityName(cityCode).isEmpty()) {
            throw new OnboardingException(Error.CITY_UNKNOWN);
        }
        // 验证码只能用一次；先查占用再用码，免得码用掉了却因为登录名被占而失败。
        if (mapper.countAccountsByLoginName(loginName) > 0) {
            throw new OnboardingException(Error.LOGIN_NAME_TAKEN);
        }
        if (mapper.countAccountsByMobile(phone) > 0) {
            // 手机号被占沿用账号模块的码（ACCOUNT_PHONE_TAKEN），和手机号登录、换号一致。
            throw new AccountException(AccountException.Error.PHONE_TAKEN);
        }
        smsCodes.verify(phone, SmsCodeService.SIGNUP, draft.smsCode(), null);

        OffsetDateTime at = now();
        UUID organizationId = mapper.insertOrganization(onboarding.organizationCode(at), organizationName,
                OpsOrganizationType.PROVIDER.name(), cityCode, idempotencyKey, at);
        if (organizationId == null) {
            // 自助申请按键排队；这里只可能与运营端建机构共用了键。
            throw new OnboardingException(Error.IDEMPOTENCY_CONFLICT);
        }
        UUID accountId = mapper.insertSignupAccount(loginName, contactName, hasher.hash(draft.password()), phone,
                organizationId, at);
        if (accountId == null) {
            throw new OnboardingException(Error.LOGIN_NAME_TAKEN);
        }
        mapper.insertMember(organizationId, accountId, at);
        mapper.grantRole(accountId, OpsGrantableRole.PROVIDER_OPERATOR.name(), organizationId);
        mapper.insertQualification(organizationId, subjectName, licenseNumber, scope, null, accountId, at);
        Operator applicant = new Operator(accountId, contactName);
        mapper.insertOperation(organizationId, OpsOrganizationAction.SIGNUP.name(),
                "商家自助申请入驻：机构“" + organizationName + "”，城市 " + cityLabel(cityCode) + "，主体“" + subjectName
                        + "”，统一社会信用代码 " + licenseNumber + "，联系人 " + contactName + "，登录名 " + loginName
                        + "，角色：服务商操作员", applicant.accountId(), applicant.displayName(), at);
        mapper.insertOperation(organizationId, OpsOrganizationAction.QUALIFICATION_SUBMIT.name(),
                "录入资质资料：主体“" + subjectName + "”，统一社会信用代码 " + licenseNumber + "，进入待审核",
                applicant.accountId(), applicant.displayName(), at);
        mapper.saveSignupSignature(organizationId, signature);
        return signupResult(organizationId, organizationName, loginName, true);
    }

    // ---- 工作台“入驻资料”：机构看自己的、改自己的 ----

    @Transactional(readOnly = true)
    public OwnOrganizationView own(UUID organizationId) {
        return own(onboarding.organization(organizationId));
    }

    /** 改资质资料（沿用运营端规则：保存后回到待审核）。操作人是机构自己的账号。 */
    @Transactional
    public OwnOrganizationView submitQualification(UUID organizationId, QualificationDraft draft, Operator member) {
        return own(onboarding.submitQualification(organizationId, draft, member));
    }

    @Transactional
    public OwnOrganizationView addAttachment(UUID organizationId, String fileName, byte[] content, Operator member) {
        return own(onboarding.addAttachment(organizationId, fileName, content, member));
    }

    @Transactional
    public OwnOrganizationView removeAttachment(UUID organizationId, UUID attachmentId, Operator member) {
        return own(onboarding.removeAttachment(organizationId, attachmentId, member));
    }

    @Transactional(readOnly = true)
    public OpsAttachmentContent attachmentContent(UUID organizationId, UUID attachmentId) {
        return onboarding.attachmentContent(organizationId, attachmentId);
    }

    private static OwnOrganizationView own(OpsOrganizationDetailView detail) {
        return new OwnOrganizationView(detail.organization(), detail.qualification(), detail.attachments(),
                detail.providerProfileStatus());
    }

    private static OrganizationSignupCreated signupResult(UUID id, String name, String login, boolean created) {
        return new OrganizationSignupCreated(
                new OrganizationSignupResult(id, name, login, OpsQualificationStatus.SUBMITTED), created);
    }

    private String cityLabel(String cityCode) {
        return cities.cityName(cityCode).map(name -> name + "（" + cityCode + "）").orElse(cityCode);
    }

    private static String text(String value, int max, String what) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > max) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return text;
    }

    private static String optionalText(String value, int max, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return text(value, max, what);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** 申请入驻填的内容。licenseNumber 会转成大写；businessScope 可空。 */
    public record SignupDraft(String organizationName, String cityCode, String subjectName, String licenseNumber,
                              String businessScope, String contactName, String phone, String smsCode,
                              String loginName, String password) {
    }
}
