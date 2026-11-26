package com.lowaltitude.inspection.home;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DevelopmentMediaControllerTest {
    @Test
    void deliversOnlyPublishedAssetDirectoriesAndStreamsByteRanges(@TempDir Path root) throws Exception {
        Path video = root.resolve("videos/preview/sample.mp4");
        Files.createDirectories(video.getParent());
        Files.write(video, new byte[]{0, 1, 2, 3, 4, 5});
        var mvc = MockMvcBuilders.standaloneSetup(new DevelopmentMediaController(root.toString())).build();
        String path = "/api/v1/dev-media/videos/preview/sample.mp4";
        mvc.perform(head(path)).andExpect(status().isOk()).andExpect(header().longValue("Content-Length", 6));
        mvc.perform(get(path).header("Range", "bytes=2-4")).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-4/6")).andExpect(content().bytes(new byte[]{2, 3, 4}));
        Path image = root.resolve("images/inspection/detail-defect-list.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2});
        mvc.perform(get("/api/v1/dev-media/images/inspection/detail-defect-list.png")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andExpect(content().bytes(new byte[]{1, 2}));
        mvc.perform(get("/api/v1/dev-media/videos/preview/absent.mp4")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/dev-media/videos/private/sample.mp4")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/dev-media/videos/preview/sample.sql")).andExpect(status().isNotFound());
    }
}
