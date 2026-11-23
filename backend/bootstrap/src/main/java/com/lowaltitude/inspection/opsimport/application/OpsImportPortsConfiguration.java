package com.lowaltitude.inspection.opsimport.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.bootstrap.infrastructure.LocalImportFileStore;
import com.lowaltitude.inspection.identity.application.OrganizationImportService;
import com.lowaltitude.inspection.identity.application.OrganizationOnboardingService;
import com.lowaltitude.inspection.mall.application.MallCatalogImport;
import com.lowaltitude.inspection.mall.application.MallCatalogOpsService;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountService;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 批量导入（S2-3）的端口：查库接身份（机构、登录名）、巡检（城市目录）、交易（收款账户、占位商户号）；写入接身份的
 * 机构导入、交易的收款账户登记、商城的商品导入，各模块在自己的服务里写自己的表，都在导入的同一个事务里。
 * 导入文件：配置了 opsimport.files.local-dir（只在 application-dev.yml 里有）时用本机目录，否则不可用（预检 503）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class OpsImportPortsConfiguration {

    @Bean
    OpsImportPorts.Lookups opsImportLookups(OrganizationImportService organizations, CityOperationsService cities,
                                            ObjectProvider<TradeSourceOrders> orders,
                                            OpsMerchantAccountService accounts) {
        return new OpsImportPorts.Lookups() {
            @Override
            public Map<String, OpsImportPorts.Organization> organizations(Collection<String> codes) {
                Map<String, OpsImportPorts.Organization> found = new HashMap<>();
                organizations.organizationsByCode(codes).forEach((code, organization) -> found.put(code,
                        new OpsImportPorts.Organization(organization.id(), organization.organizationType(),
                                organization.status())));
                return found;
            }

            @Override
            public boolean cityExists(String cityCode) {
                return cities.cityName(cityCode).isPresent();
            }

            @Override
            public boolean merchantCollecting(UUID organizationId) {
                return orders.getObject().merchantCanCollect(organizationId);
            }

            @Override
            public Map<String, Optional<UUID>> loginOwners(Collection<String> loginNames) {
                return organizations.loginOwners(loginNames);
            }

            @Override
            public boolean subMchidRejected(String subMchid) {
                return accounts.placeholderRejected(subMchid);
            }
        };
    }

    @Bean
    OpsImportPorts.Writers opsImportWriters(OrganizationImportService organizations,
                                            OpsMerchantAccountService accounts, MallCatalogOpsService catalog) {
        return new OpsImportPorts.Writers() {
            @Override
            public OpsImportPorts.OrganizationsWritten organizations(
                    List<OpsImportPorts.OrganizationRecord> records, List<OpsImportPorts.OperatorRecord> operators,
                    String batchLabel, OpsImportPorts.Actor actor) {
                OrganizationImportService.Result result = organizations.importOrganizations(
                        records.stream().map(record -> new OrganizationImportService.OrganizationInput(record.code(),
                                record.name(), record.status(), record.qualificationReviewedOn())).toList(),
                        operators.stream().map(operator -> new OrganizationImportService.OperatorInput(
                                operator.organizationCode(), operator.loginName(), operator.displayName(),
                                operator.roles())).toList(),
                        batchLabel, new OrganizationOnboardingService.Operator(actor.accountId(), actor.displayName()));
                return new OpsImportPorts.OrganizationsWritten(
                        result.written().stream().map(written -> new OpsImportPorts.Written(
                                written.kind() == OrganizationImportService.WrittenKind.ORGANIZATION
                                        ? OpsImportRefKind.ORGANIZATION : OpsImportRefKind.OPERATOR,
                                written.reference(), written.targetId(), written.created())).toList(),
                        result.createdAccounts().stream().map(account -> new OpsImportPorts.CreatedAccount(
                                account.organizationCode(), account.loginName(), account.initialPassword())).toList());
            }

            @Override
            public List<OpsImportPorts.Written> merchantAccounts(List<OpsImportPorts.MerchantAccountRecord> records,
                                                                 String batchLabel, OpsImportPorts.Actor actor) {
                OpsMerchantAccountService.Operator operator = new OpsMerchantAccountService.Operator(
                        actor.accountId(), actor.displayName());
                List<OpsImportPorts.Written> written = new ArrayList<>();
                for (OpsImportPorts.MerchantAccountRecord record : records) {
                    boolean created = accounts.importAccount(record.organizationId(), record.subMchid(),
                            record.active(), batchLabel, operator);
                    written.add(new OpsImportPorts.Written(OpsImportRefKind.MERCHANT_ACCOUNT,
                            record.organizationCode(), record.organizationId(), created));
                }
                return written;
            }

            @Override
            public List<OpsImportPorts.Written> mall(List<OpsImportPorts.ProductRecord> products, String batchLabel,
                                                     OpsImportPorts.Actor actor) {
                List<MallCatalogImport.Written> written = catalog.importCatalog(
                        products.stream().map(OpsImportPortsConfiguration::product).toList(), batchLabel,
                        new MallCatalogOpsService.Operator(actor.accountId(), actor.displayName()));
                return written.stream().map(item -> new OpsImportPorts.Written(switch (item.kind()) {
                    case PRODUCT -> OpsImportRefKind.PRODUCT;
                    case SKU -> OpsImportRefKind.SKU;
                    case OFFER -> OpsImportRefKind.OFFER;
                }, item.reference(), item.targetId(), item.created())).toList();
            }
        };
    }

    @Bean
    OpsImportPorts.FileStore opsImportFileStore(@Value("${opsimport.files.local-dir:}") String localDirectory) {
        return localDirectory == null || localDirectory.isBlank()
                ? new LocalImportFileStore.Unavailable()
                : new LocalImportFileStore(Path.of(localDirectory));
    }

    static MallCatalogImport.Product product(OpsImportPorts.ProductRecord record) {
        return new MallCatalogImport.Product(record.productId(), record.reference(), record.category(), record.name(),
                record.summary(), record.coverUrl(), record.saleMode(), record.experienceBadge(), record.sortOrder(),
                record.status(), record.sellingPoints(),
                record.specs() == null ? null : record.specs().stream()
                        .map(spec -> new MallCatalogImport.Spec(spec.label(), spec.value(), spec.sortOrder())).toList(),
                record.packItems() == null ? null : record.packItems().stream()
                        .map(item -> new MallCatalogImport.PackItem(item.name(), item.quantity(), item.sortOrder()))
                        .toList(),
                record.skus().stream().map(sku -> new MallCatalogImport.Sku(sku.skuId(), sku.reference(), sku.name(),
                        sku.sortOrder(), sku.offers().stream().map(offer -> new MallCatalogImport.Offer(
                                offer.offerId(), offer.reference(), offer.merchantId(), offer.merchantKind(),
                                offer.cityCode(), offer.priceAmountMinor(), offer.priceUnit(), offer.stockQuantity(),
                                offer.expressAvailable(), offer.shippingFeeMinor(), offer.pickupPoint(),
                                offer.deliveryNote(), offer.shippingNote(), offer.fulfilledFrom(), offer.status()))
                        .toList())).toList());
    }
}
