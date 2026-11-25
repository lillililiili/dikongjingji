package com.lowaltitude.inspection.home;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.lowaltitude.inspection.media.DevelopmentMediaPath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves development seed media from a read-only asset directory. Spring handles byte ranges for Resource responses. */
@RestController
@Profile("dev")
public final class DevelopmentMediaController {
    private final Path root;

    public DevelopmentMediaController(@Value("${platform.dev-media.root:${DEV_MEDIA_ROOT:apps/mobile/src/static}}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @GetMapping(DevelopmentMediaPath.PREFIX + "{kind}/{group}/{name}")
    public ResponseEntity<Resource> file(@PathVariable String kind, @PathVariable String group, @PathVariable String name)
            throws IOException {
        String relative = kind + "/" + group + "/" + name;
        if (!DevelopmentMediaPath.matches(DevelopmentMediaPath.PREFIX + relative) || !Files.isDirectory(root)) {
            return ResponseEntity.notFound().build();
        }
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(root.toRealPath())) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "mp4" -> MediaType.parseMediaType("video/mp4");
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "png" -> MediaType.IMAGE_PNG;
            default -> MediaType.IMAGE_JPEG;
        };
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(file));
    }
}
