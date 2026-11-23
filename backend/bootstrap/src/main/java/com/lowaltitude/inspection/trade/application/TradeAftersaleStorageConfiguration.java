package com.lowaltitude.inspection.trade.application;

import java.nio.file.Path;

import com.lowaltitude.inspection.bootstrap.infrastructure.LocalTradeAftersaleImageStore;
import com.lowaltitude.inspection.bootstrap.infrastructure.S3ModuleFileStores;
import com.lowaltitude.inspection.bootstrap.infrastructure.storage.PlatformStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 售后凭证图片接对象存储（S4-2）：platform.storage.provider=s3 时存私有桶（S6）；否则配置了 trade.aftersale.image-dir
 * （只在 application-dev.yml 里有）时用本机目录；都没有时上传回 503（TRADE_AFTERSALE_STORAGE_UNAVAILABLE）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class TradeAftersaleStorageConfiguration {

    @Bean
    TradeAftersaleImageStore tradeAftersaleImageStore(@Value("${trade.aftersale.image-dir:}") String directory,
                                                      ObjectProvider<PlatformStorage> platformStorage) {
        PlatformStorage storage = platformStorage.getIfAvailable(PlatformStorage::local);
        if (storage.isS3()) {
            return new S3ModuleFileStores.TradeAftersaleImages(storage);
        }
        if (directory == null || directory.isBlank()) {
            return TradeAftersaleImageStore.unavailable();
        }
        return new LocalTradeAftersaleImageStore(Path.of(directory));
    }
}
