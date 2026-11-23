package com.lowaltitude.inspection.ops.media;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.common.OpsError;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运营端素材上传（S3-1，契约 contracts/openapi-ops-media.yaml 1.0.0）：图片（JPEG、PNG、WebP，≤5 MB）和视频（MP4，≤50 MB）。
 * 类型按文件头判断，不信浏览器给的类型和文件名；存进对象存储端口，返回受控 HTTPS 地址，运营再把地址填进活动、场馆、
 * 内容等表单（业务模块只认 https 地址）。上传本身不改任何业务数据，所以不写改动记录，只记一行日志（账号 ID 和键）。
 */
public class OpsMediaService {

    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;

    /** 存储键：ops-media/&lt;uuid&gt;.&lt;扩展名&gt;。 */
    static final Pattern KEY = Pattern.compile(
            "^ops-media/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|mp4)$");
    private static final Pattern FILE_NAME = Pattern.compile(
            "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(jpg|png|webp|mp4)$");

    private static final Logger LOGGER = LoggerFactory.getLogger(OpsMediaService.class);

    private final OpsMediaStore store;

    public OpsMediaService(OpsMediaStore store) {
        this.store = store;
    }

    public OpsMediaView upload(byte[] content, OpsActor actor) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("file is empty");
        }
        Format format = Format.sniff(content);
        if (format == null) {
            throw new OpsException(Error.TYPE_UNSUPPORTED);
        }
        if (content.length > format.kind.maxBytes) {
            throw new OpsException(Error.TOO_LARGE);
        }
        String key = "ops-media/" + UUID.randomUUID() + "." + format.extension;
        try {
            store.put(key, content, format.contentType);
            String url = store.publicUrl(key);
            LOGGER.info("ops media uploaded account={} key={} bytes={}", actor.accountId(), key, content.length);
            return new OpsMediaView(url, key.substring("ops-media/".length()), format.kind, format.contentType,
                    content.length);
        } catch (OpsMediaStore.UnavailableException unavailable) {
            throw new OpsException(Error.STORE_UNAVAILABLE);
        }
    }

    /** 运营端预览：按上传时给的文件名（&lt;uuid&gt;.&lt;扩展名&gt;）取回内容。 */
    public OpsMediaContent open(String fileName) {
        Matcher matcher = fileName == null ? null : FILE_NAME.matcher(fileName.toLowerCase(Locale.ROOT));
        if (matcher == null || !matcher.matches()) {
            throw new OpsException(Error.NOT_FOUND);
        }
        Format format = Format.byExtension(matcher.group(2));
        byte[] content;
        try {
            content = store.read("ops-media/" + matcher.group(1) + "." + matcher.group(2));
        } catch (OpsMediaStore.UnavailableException unavailable) {
            throw new OpsException(Error.STORE_UNAVAILABLE);
        }
        if (content == null) {
            throw new OpsException(Error.NOT_FOUND);
        }
        return new OpsMediaContent(format.contentType, content);
    }

    public record OpsMediaView(String url, String fileName, OpsMediaKind kind, String contentType, long sizeBytes) {
    }

    public record OpsMediaContent(String contentType, byte[] content) {
    }

    public enum OpsMediaKind {
        IMAGE(MAX_IMAGE_BYTES),
        VIDEO(MAX_VIDEO_BYTES);

        private final long maxBytes;

        OpsMediaKind(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    enum Format {
        JPEG("jpg", "image/jpeg", OpsMediaKind.IMAGE),
        PNG("png", "image/png", OpsMediaKind.IMAGE),
        WEBP("webp", "image/webp", OpsMediaKind.IMAGE),
        MP4("mp4", "video/mp4", OpsMediaKind.VIDEO);

        private final String extension;
        private final String contentType;
        private final OpsMediaKind kind;

        Format(String extension, String contentType, OpsMediaKind kind) {
            this.extension = extension;
            this.contentType = contentType;
            this.kind = kind;
        }

        static Format byExtension(String extension) {
            return Arrays.stream(values()).filter(format -> format.extension.equals(extension)).findFirst()
                    .orElseThrow();
        }

        /** 按文件头认：JPEG FF D8 FF；PNG 89 50 4E 47 0D 0A 1A 0A；WebP RIFF....WEBP；MP4 第 4–7 字节是 ftyp。 */
        static Format sniff(byte[] bytes) {
            if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
                return JPEG;
            }
            if (startsWith(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
                return PNG;
            }
            if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
                return WEBP;
            }
            if (startsWith(bytes, 4, 'f', 't', 'y', 'p')) {
                return MP4;
            }
            return null;
        }

        private static boolean startsWith(byte[] bytes, int offset, int... expected) {
            if (bytes.length < offset + expected.length) {
                return false;
            }
            for (int index = 0; index < expected.length; index++) {
                if ((bytes[offset + index] & 0xFF) != expected[index]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 契约 OpsMediaErrorCode。 */
    public enum Error implements OpsError {
        NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
        TYPE_UNSUPPORTED(422, "OPS_MEDIA_TYPE_UNSUPPORTED", "只能上传 JPEG、PNG、WebP 图片或 MP4 视频"),
        TOO_LARGE(422, "OPS_MEDIA_TOO_LARGE", "图片不超过 5 MB，视频不超过 50 MB"),
        STORE_UNAVAILABLE(503, "OPS_MEDIA_STORE_UNAVAILABLE", "素材存储暂不可用");

        private final int status;
        private final String code;
        private final String message;

        Error(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        @Override
        public int httpStatus() {
            return status;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
