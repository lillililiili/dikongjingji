package com.lowaltitude.inspection.trade.application;

import java.nio.file.Path;

import com.lowaltitude.inspection.bootstrap.infrastructure.LocalTradeAftersaleImageStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 售后凭证图片接对象存储（S4-2）：配置了 trade.aftersale.image-dir（只在 application-dev.yml 里有）时用本机目录；
 * 没配时上传回 503（TRADE_AFTERSALE_STORAGE_UNAVAILABLE），等真实环境接私有桶。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class TradeAftersaleStorageConfiguration {

    @Bean
    TradeAftersaleImageStore tradeAftersaleImageStore(@Value("${trade.aftersale.image-dir:}") String directory) {
        if (directory == null || directory.isBlank()) {
            return TradeAftersaleImageStore.unavailable();
        }
        return new LocalTradeAftersaleImageStore(Path.of(directory));
    }
}
