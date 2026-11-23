package com.lowaltitude.inspection.ops.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端素材上传（契约 openapi-ops-media.yaml 1.0.0）：类型按文件头判断、大小上限、本机存储、预览和权限。 */
class OpsMediaTest {

    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F');
    private static final byte[] PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D);
    private static final byte[] WEBP = bytes('R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P');
    private static final byte[] MP4 = bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm');

    @TempDir
    Path directory;

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-media.yaml",
            OpsMediaController.class)
            .response("OpsMedia", OpsMediaService.OpsMediaView.class)
            .enumeration("OpsMediaKind", OpsMediaService.OpsMediaKind.class)
            .other("ApiError", "OpsMediaErrorCode");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
        checker.verifyOpsErrors("OpsMediaErrorCode", OpsMediaService.Error.values());
        assertThat(checker.text()).contains("≤5 MB").contains("≤50 MB");
        assertThat(OpsMediaService.MAX_IMAGE_BYTES).isEqualTo(5L * 1024 * 1024);
        assertThat(OpsMediaService.MAX_VIDEO_BYTES).isEqualTo(50L * 1024 * 1024);
    }

    @Test
    void storesByFileHeaderAndGivesAnHttpsAddress() throws Exception {
        OpsMediaService service = new OpsMediaService(new LocalOpsMediaStore(directory, "https://media.dev.localhost/"));
        for (var sample : List.of(List.of(JPEG, "jpg", "image/jpeg", "IMAGE"), List.of(PNG, "png", "image/png", "IMAGE"),
                List.of(WEBP, "webp", "image/webp", "IMAGE"), List.of(MP4, "mp4", "video/mp4", "VIDEO"))) {
            var view = service.upload((byte[]) sample.get(0), new com.lowaltitude.inspection.ops.OpsActor(
                    OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME));
            assertThat(view.url()).startsWith("https://media.dev.localhost/ops-media/").endsWith("." + sample.get(1));
            assertThat(view.fileName()).matches("^[0-9a-f-]{36}\\." + sample.get(1) + "$");
            assertThat(view.contentType()).isEqualTo(sample.get(2));
            assertThat(view.kind().name()).isEqualTo(sample.get(3));
            assertThat(Files.readAllBytes(directory.resolve("ops-media").resolve(view.fileName())))
                    .isEqualTo(sample.get(0));
            assertThat(service.open(view.fileName()).content()).isEqualTo(sample.get(0));
        }
    }

    @Test
    void rejectsUnknownTypesOversizeAndBadNames() {
        OpsMediaService service = new OpsMediaService(new LocalOpsMediaStore(directory, "https://media.dev.localhost"));
        var actor = new com.lowaltitude.inspection.ops.OpsActor(OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME);
        assertThatThrownBy(() -> service.upload("%PDF-1.7".getBytes(), actor)).isInstanceOf(OpsException.class)
                .extracting(failure -> ((OpsException) failure).error()).isEqualTo(OpsMediaService.Error.TYPE_UNSUPPORTED);
        byte[] bigImage = Arrays.copyOf(JPEG, (int) OpsMediaService.MAX_IMAGE_BYTES + 1);
        assertThatThrownBy(() -> service.upload(bigImage, actor))
                .extracting(failure -> ((OpsException) failure).error()).isEqualTo(OpsMediaService.Error.TOO_LARGE);
        assertThatThrownBy(() -> service.upload(new byte[0], actor)).isInstanceOf(IllegalArgumentException.class);
        for (String name : List.of("../secret.jpg", "x.jpg", "00000000-0000-4000-8000-000000000000.gif")) {
            assertThatThrownBy(() -> service.open(name)).as(name)
                    .extracting(failure -> ((OpsException) failure).error()).isEqualTo(OpsMediaService.Error.NOT_FOUND);
        }
        assertThatThrownBy(() -> service.open("00000000-0000-4000-8000-000000000000.jpg"))
                .extracting(failure -> ((OpsException) failure).error()).isEqualTo(OpsMediaService.Error.NOT_FOUND);
        assertThatThrownBy(() -> new LocalOpsMediaStore(directory, "http://media.dev.localhost"))
                .isInstanceOf(IllegalArgumentException.class);
        OpsMediaService unavailable = new OpsMediaService(new OpsMediaStore.Unavailable());
        assertThatThrownBy(() -> unavailable.upload(JPEG, actor))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(OpsMediaService.Error.STORE_UNAVAILABLE);
    }

    @Test
    void onlyOperatorsUploadAndPreview() throws Exception {
        OpsMediaService service = new OpsMediaService(new LocalOpsMediaStore(directory, "https://media.dev.localhost"));
        MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsMediaController(service, OpsTestSupport.actors()));
        String fileName = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(multipart("/api/v1/ops/media")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", PNG))
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("IMAGE"))
                .andExpect(jsonPath("$.sizeBytes").value(PNG.length))
                .andReturn().getResponse().getContentAsString(), "$.fileName");
        mockMvc.perform(get("/api/v1/ops/media/" + fileName).principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));
        mockMvc.perform(multipart("/api/v1/ops/media")
                        .file(new MockMultipartFile("file", "a.gif", "image/gif", "GIF89a".getBytes()))
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_MEDIA_TYPE_UNSUPPORTED"));
        mockMvc.perform(multipart("/api/v1/ops/media")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", PNG))
                        .principal(OpsTestSupport.support()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/ops/media/" + fileName).principal(OpsTestSupport.support()))
                .andExpect(status().isForbidden());
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}
