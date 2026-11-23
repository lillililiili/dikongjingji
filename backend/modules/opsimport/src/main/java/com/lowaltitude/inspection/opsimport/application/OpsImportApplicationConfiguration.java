package com.lowaltitude.inspection.opsimport.application;

import java.time.Clock;

import com.lowaltitude.inspection.opsimport.infrastructure.persistence.OpsImportMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 批量导入（S2-3）。查库、写入、文件存储的端口由启动模块接上（OpsImportPorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = OpsImportMapper.class)
public class OpsImportApplicationConfiguration {

    @Bean
    OpsImportService opsImportService(OpsImportMapper mapper, OpsImportPorts.Lookups lookups,
                                      OpsImportPorts.Writers writers, OpsImportPorts.FileStore files) {
        return new OpsImportService(mapper, lookups, writers, files, Clock.systemUTC());
    }
}
