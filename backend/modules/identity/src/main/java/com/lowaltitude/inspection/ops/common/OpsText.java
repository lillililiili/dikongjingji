package com.lowaltitude.inspection.ops.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import com.lowaltitude.inspection.media.DevelopmentMediaPath;

/**
 * 运营端写接口共用的输入整理和校验（S3-1）。和管理端 core/ui/form-rules.ts 一致：去掉首尾空白再判断，长度按码点算。
 * 不合格一律抛 IllegalArgumentException（全局异常处理给 400 COMMON_INVALID_ARGUMENT）；业务规则（上线前必填、状态冲突）
 * 由各模块抛 {@link OpsException}。
 */
public final class OpsText {

    /** 图片、视频、回看等地址的最大长度（各模块表里都是 varchar(500)）。 */
    public static final int MAX_URL = 500;
    /** 操作人显示名快照的最大长度。 */
    public static final int MAX_OPERATOR_NAME = 80;

    /** An editor can retain the existing seeded media reference. New or changed media must use HTTPS. */
    public static String mediaUrl(String value, String existing, String field) {
        return DevelopmentMediaPath.matches(value) && value.equals(existing) ? value : httpsUrl(value, field);
    }

    private OpsText() {
    }

    /** 必填的一行字：去空白后 1–max 字，不能有换行等控制字符。 */
    public static String required(String value, int max, String field) {
        String text = optional(value, max, field);
        if (text == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    /** 选填的一行字：空白当作没填（返回 null），否则最多 max 字，不能有控制字符。 */
    public static String optional(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (length(text) > max || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return text;
    }

    /** 选填的多行字（须知、说明）：允许换行，别的控制字符不行；统一成 \n。 */
    public static String multiline(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (length(text) > max || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return text;
    }

    /**
     * 选填的受控地址：只收带主机名的 https 地址（运营上传后由对象存储端口给出，或运营填的受控 CDN 地址），最多 500 字，
     * 不能有空白；空白当作没填。
     */
    public static String httpsUrl(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.length() > MAX_URL || text.chars().anyMatch(c -> Character.isWhitespace(c)
                || Character.isISOControl(c))) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        try {
            URI uri = new URI(text);
            if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(field + " must be an https address");
            }
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(field + " is invalid", invalid);
        }
        return text;
    }

    /** 列表搜索的关键词：去掉 LIKE 通配符，最多 max 字；空白当作不筛选。 */
    public static String keyword(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[%_\\\\]", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (length(cleaned) > max || cleaned.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("keyword is invalid");
        }
        return cleaned;
    }

    /** 分页参数：page ≥ 1，size 在 1–max。 */
    public static void page(int page, int size, int max) {
        if (page < 1 || size < 1 || size > max) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    /** 操作人显示名快照（写进改动记录）：必须有，超长截断。 */
    public static String operatorName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
        return clip(name.strip(), MAX_OPERATOR_NAME);
    }

    /** 枚举值（按大写名）；空白或不认识都按参数无效。 */
    public static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(field + " is invalid", unknown);
        }
    }

    /** 选填的枚举筛选条件：空白返回 null。 */
    public static <E extends Enum<E>> E optionalEnum(Class<E> type, String value, String field) {
        return value == null || value.isBlank() ? null : enumValue(type, value, field);
    }

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    public static String clip(String value, int max) {
        return length(value) <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }
}
