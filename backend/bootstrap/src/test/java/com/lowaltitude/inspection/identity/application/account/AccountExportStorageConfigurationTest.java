package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lowaltitude.inspection.bootstrap.infrastructure.LocalDataExportStore;
import com.lowaltitude.inspection.bootstrap.infrastructure.S3ModuleFileStores;
import com.lowaltitude.inspection.bootstrap.infrastructure.storage.PlatformStorage;
import com.lowaltitude.inspection.bootstrap.infrastructure.storage.S3ObjectClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class AccountExportStorageConfigurationTest {
    @Test
    void selectsPrivateS3AndKeepsTheLocalAndUnconfiguredModes() {
        var configuration = new IdentityAccountPortsConfiguration();
        var beans = new StaticListableBeanFactory();
        var provider = beans.getBeanProvider(PlatformStorage.class);
        assertThat(configuration.accountExportFiles("", provider)).isInstanceOf(LocalDataExportStore.Unavailable.class);
        assertThat(configuration.accountExportFiles("exports", provider)).isInstanceOf(LocalDataExportStore.class);
        beans.addBean("platformStorage", PlatformStorage.s3(mock(S3ObjectClient.class), "private", "public",
                "https://files.example.test"));
        assertThat(configuration.accountExportFiles("", provider)).isInstanceOf(S3ModuleFileStores.DataExports.class);
        assertThat(configuration.accountExportFiles("exports", provider)).isInstanceOf(S3ModuleFileStores.DataExports.class);
    }
}
