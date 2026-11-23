package com.lowaltitude.inspection.opsimport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.opsimport.application.ImportValidator.Issue;
import com.lowaltitude.inspection.opsimport.application.ImportValidator.Upload;
import org.junit.jupiter.api.Test;

/** 预检规则：按列、跨表、查库。查库换成内存里的几家机构、两个城市。 */
class ImportValidatorTest {

    private static final UUID SHOP = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID UNPAID = UUID.fromString("c1000000-0000-4000-8000-000000000006");
    private static final UUID CUSTOMER = UUID.fromString("c1000000-0000-4000-8000-000000000099");

    private final Map<String, Optional<UUID>> logins = new HashMap<>();
    private final OpsImportPorts.Lookups lookups = new OpsImportPorts.Lookups() {
        @Override
        public Map<String, OpsImportPorts.Organization> organizations(Collection<String> codes) {
            Map<String, OpsImportPorts.Organization> all = Map.of(
                    "SHOP", new OpsImportPorts.Organization(SHOP, "PROVIDER", "ACTIVE"),
                    "UNPAID", new OpsImportPorts.Organization(UNPAID, "PROVIDER", "ACTIVE"),
                    "CORP", new OpsImportPorts.Organization(CUSTOMER, "CUSTOMER", "ACTIVE"));
            Map<String, OpsImportPorts.Organization> found = new HashMap<>();
            codes.forEach(code -> { if (all.containsKey(code)) found.put(code, all.get(code)); });
            return found;
        }

        @Override
        public boolean cityExists(String cityCode) {
            return Set.of("370500", "370100").contains(cityCode);
        }

        @Override
        public boolean merchantCollecting(UUID organizationId) {
            return SHOP.equals(organizationId);
        }

        @Override
        public Map<String, Optional<UUID>> loginOwners(Collection<String> loginNames) {
            Map<String, Optional<UUID>> owners = new HashMap<>();
            loginNames.forEach(login -> { if (logins.containsKey(login)) owners.put(login, logins.get(login)); });
            return owners;
        }

        @Override
        public boolean subMchidRejected(String subMchid) {
            return subMchid.startsWith("1900000");
        }
    };

    private ImportValidator.Result validate(OpsImportKind kind, Upload... uploads) {
        return new ImportValidator(lookups, references -> Map.of("OLD-P", UUID.randomUUID()),
                LocalDate.of(2026, 11, 23)).validate(kind, List.of(uploads));
    }

    private static Upload csv(String name, String... lines) {
        return new Upload(name, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static final String ORG_HEADER =
            "organization_code,organization_name,organization_type,business_roles,credit_code,qualification_reviewed_on,status";

    @Test
    void organizationsCheckCodesDatesRolesAndLogins() {
        logins.put("taken", Optional.of(UNPAID));
        logins.put("mine", Optional.of(SHOP));

        var result = validate(OpsImportKind.ORGANIZATIONS,
                csv("01-organizations.csv", ORG_HEADER,
                        "SHOP,示例商家,PROVIDER,MERCHANT|ORGANIZER,91370500MA3C00000X,2026-11-01,ACTIVE",
                        "SHOP,重复,PROVIDER,MERCHANT,91370500MA3C00000X,,ACTIVE",
                        "CORP,企业客户,PROVIDER,MERCHANT,91370500MA3C00000X,2027-01-01,ACTIVE",
                        "bad code,名称,CUSTOMER,SHOP,123,,ACTIVE"),
                csv("01b-organization-operators.csv", "organization_code,login_name,display_name,role_code",
                        "SHOP,mine,店员,PROVIDER_OPERATOR",
                        "SHOP,mine,店员,PROVIDER_OPERATOR",
                        "SHOP,taken,店员,LIVE_HOST",
                        "NOPE,fresh1,店员,PROVIDER_OPERATOR"));

        assertThat(result.issues()).extracting(Issue::fileName, Issue::line, Issue::column).containsExactlyInAnyOrder(
                tuple("01-organizations.csv", 3, "organization_code"),
                tuple("01-organizations.csv", 4, "organization_code"),
                tuple("01-organizations.csv", 4, "qualification_reviewed_on"),
                tuple("01-organizations.csv", 5, "organization_code"),
                tuple("01-organizations.csv", 5, "organization_type"),
                tuple("01-organizations.csv", 5, "business_roles"),
                tuple("01-organizations.csv", 5, "credit_code"),
                tuple("01b-organization-operators.csv", 3, "role_code"),
                tuple("01b-organization-operators.csv", 4, "login_name"),
                tuple("01b-organization-operators.csv", 5, "organization_code"));
        // 核对列只在预检结果里，不进库。
        assertThat(result.reviewItems()).extracting(ImportValidator.ReviewItem::label)
                .contains("统一社会信用代码", "平台角色");
        assertThat(result.rowCount()).isEqualTo(8);
    }

    @Test
    void wrongFileNamesHeadersAndMissingRequiredTablesAreFileIssues() {
        var result = validate(OpsImportKind.MALL,
                csv("products.csv", "x"),
                csv("03d-mall-skus.csv", "product_ref,sku_ref,name"));

        assertThat(result.issues()).extracting(Issue::fileName, Issue::line).containsExactlyInAnyOrder(
                tuple("products.csv", 0),
                tuple("03d-mall-skus.csv", 1),
                tuple("03-mall-products.csv", 0));
    }

    private static final String PRODUCT_HEADER =
            "product_ref,category,name,summary,cover_url,sale_mode,experience_badge,selling_points,sort_order,status";
    private static final String OFFER_HEADER = "sku_ref,merchant_organization_code,merchant_kind,city_code,price_amount_minor,"
            + "price_unit,stock_quantity,express_available,shipping_fee_minor,pickup_point,delivery_note,shipping_note,"
            + "fulfilled_from,status";

    @Test
    void mallChecksUnitsPricesDeliveryCollectingAndPublishing() {
        var result = validate(OpsImportKind.MALL,
                csv("03-mall-products.csv", PRODUCT_HEADER,
                        "P1,ROBOT,机器人,,,BUY,,图形化编程|避障练习,10,PUBLISHED",
                        "P2,DRONE,询价无人机,,,INQUIRY,,,,DRAFT",
                        "P3,RENTAL,租赁机器狗,一句话,,RENT,,,,DRAFT",
                        "P4,PARTS,没有规格的配件,,,BUY,,,,DRAFT"),
                csv("03d-mall-skus.csv", "product_ref,sku_ref,name,sort_order",
                        "P1,P1-A,标准版,10", "P2,P2-A,测绘版,", "P3,P3-A,按天,", "PX,PX-A,找不到商品,"),
                csv("03e-mall-offers.csv", OFFER_HEADER,
                        "P1-A,UNPAID,LOCAL_STORE,370500,99900,ITEM,5,false,0,,,,,ACTIVE",
                        "P1-A,UNPAID,LOCAL_STORE,370500,99900,ITEM,,true,0,,,,,DISABLED",
                        "P2-A,SHOP,FACTORY_DIRECT,,100,DAY,,true,0,,,,,ACTIVE",
                        "P3-A,SHOP,LOCAL_STORE,999999,,DAY,,true,0,,,,,ACTIVE",
                        "P1-B,SHOP,LOCAL_STORE,,1,ITEM,-1,true,19.90,,,,,ACTIVE"));

        assertThat(result.issues()).extracting(Issue::fileName, Issue::line, Issue::column).containsExactlyInAnyOrder(
                tuple("03-mall-products.csv", 2, "summary"),
                tuple("03-mall-products.csv", 5, "product_ref"),
                tuple("03d-mall-skus.csv", 5, "product_ref"),
                tuple("03e-mall-offers.csv", 2, "pickup_point"),
                tuple("03e-mall-offers.csv", 2, "merchant_organization_code"),
                tuple("03e-mall-offers.csv", 3, "city_code"),
                tuple("03e-mall-offers.csv", 4, "price_unit"),
                tuple("03e-mall-offers.csv", 4, "price_amount_minor"),
                tuple("03e-mall-offers.csv", 5, "city_code"),
                tuple("03e-mall-offers.csv", 5, "price_amount_minor"),
                tuple("03e-mall-offers.csv", 6, "sku_ref"),
                tuple("03e-mall-offers.csv", 6, "stock_quantity"),
                tuple("03e-mall-offers.csv", 6, "shipping_fee_minor"));
    }

    @Test
    void placeholderMerchantNumbersAreRejectedWhereTheEnvironmentRejectsThem() {
        var result = validate(OpsImportKind.MERCHANT_ACCOUNTS,
                csv("02-merchant-payment-accounts.csv", "organization_code,channel,sub_mchid,active",
                        "SHOP,WECHAT,1900000101,true", "NOPE,ALIPAY,12,yes"));

        assertThat(result.issues()).extracting(Issue::line, Issue::column).containsExactlyInAnyOrder(
                tuple(2, "sub_mchid"), tuple(3, "organization_code"), tuple(3, "channel"), tuple(3, "sub_mchid"),
                tuple(3, "active"));
    }

    @Test
    void contentChecksAuthorsVideosBlocksAndProductMentions() {
        var result = validate(OpsImportKind.CONTENT,
                csv("07-articles.csv", "article_ref,kind,title,author_kind,author_organization_code,author_name,sponsored,"
                                + "city_code,cover_url,video_url,video_duration_seconds,video_orientation,video_caption,video_tags,"
                                + "status,published_at",
                        "A1,CASE,案例,PLATFORM,,平台,false,370500,,,,,,,PUBLISHED,",
                        "A2,VIDEO,视频,MERCHANT,SHOP,商家,false,370500,,https://v.example.com/a.mp4,,,,,DRAFT,"),
                csv("07b-article-blocks.csv", "article_ref,block_ref,sort_order,block_type,text",
                        "A1,,1,FACTS,不该有字", "A1,,2,PARAGRAPH,"),
                csv("07d-article-refs.csv", "article_ref,sort_order,ref_type,ref_key,title",
                        "A1,1,PRODUCT,OLD-P,提到的商品", "A1,2,PRODUCT,NEW-P,没导入过的商品"));

        assertThat(result.issues()).extracting(Issue::fileName, Issue::line, Issue::column).containsExactlyInAnyOrder(
                tuple("07-articles.csv", 2, "author_kind"),
                tuple("07-articles.csv", 2, "published_at"),
                tuple("07-articles.csv", 3, "sponsored"),
                tuple("07-articles.csv", 3, "video_duration_seconds"),
                tuple("07-articles.csv", 3, "video_orientation"),
                tuple("07b-article-blocks.csv", 2, "text"),
                tuple("07b-article-blocks.csv", 2, "block_ref"),
                tuple("07b-article-blocks.csv", 3, "text"),
                tuple("07d-article-refs.csv", 3, "ref_key"));
    }
}
