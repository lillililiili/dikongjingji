package com.lowaltitude.inspection.application.city;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** 契约 contracts/openapi-ops-city.yaml 和 OpsCityController 逐项对照（S3-1）；公开读取的 1.1.0 带出开通状态。 */
class OpsCityOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-city.yaml", OpsCityController.class)
            .response("OpsCity", CityOperationsService.OpsCityView.class)
            .response("OpsCityPage", CityOperationsService.OpsCityPageView.class)
            .response("OpsCityStatusChange", CityOperationsService.OpsCityStatusChangeView.class)
            .request("OpsCityStatusRequest", OpsCityController.ChangeStatusRequest.class,
                    "serviceStatus", "note", "version")
            .requestEnumField("OpsCityStatusRequest.serviceStatus", "CityServiceStatus")
            .enumeration("CityServiceStatus", CityServiceStatus.class)
            .other("ApiError", "OpsCityErrorCode");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
    }

    @Test
    void limitsAreTheOnesTheServiceEnforces() {
        var properties = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsCityStatusRequest")).get("properties"));
        assertThat(OpsContractChecker.map(properties.get("note")).get("maxLength"))
                .isEqualTo(CityOperationsService.MAX_NOTE);
        assertThat(checker.text()).contains("maximum: " + CityOperationsService.MAX_PAGE_SIZE)
                .contains("maxLength: " + CityOperationsService.MAX_KEYWORD)
                .contains("maxItems: " + CityOperationsService.CHANGE_HISTORY_LIMIT);
    }

    @Test
    void errorCodesAreTheOnesTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (CityOperationException.Error error : CityOperationException.Error.values()) {
            ResponseEntity<ApiError> response = handler.handleCityOperation(new CityOperationException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            if (code.startsWith("OPS_")) {
                produced.add(code);
                assertThat(response.getStatusCode().value()).isEqualTo(409);
            } else {
                assertThat(code).isEqualTo("COMMON_NOT_FOUND");
            }
        }
        checker.verifyErrorCodes("OpsCityErrorCode", produced);
    }

    // 公开读取（openapi-platform-city.yaml 1.1.0）：城市带开通状态，目录另给已开通、筹备中两组。
    @Test
    void publicDirectoryCarriesTheServiceStatus() throws Exception {
        String contract = java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "..", "contracts", "openapi-platform-city.yaml"));
        assertThat(contract).contains("version: 1.1.0")
                .contains("        - openCities\n        - preparingCities\n")
                .contains("        - serviceStatus\n");
        assertThat(java.util.Arrays.stream(CityDirectoryReadService.DirectoryView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("hotCities", "provinces", "openCities", "preparingCities");
        assertThat(java.util.Arrays.stream(CityDirectoryReadService.CityView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("code", "name", "pinyin", "initials", "provinceCode", "provinceName", "serviceStatus");
        for (CityServiceStatus status : CityServiceStatus.values()) {
            assertThat(contract).contains("            - " + status.name());
        }
    }
}
