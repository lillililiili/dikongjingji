package com.lowaltitude.inspection.ops.common;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运营端的业务拒绝（见 {@link OpsError}）。details 放进 ApiError.details：上线前必填项没填时是
 * {@code {"missing": ["coverUrl", "sessions"]}}（字段键由各块契约列出，页面翻成中文），其余一般为空。
 */
public final class OpsException extends RuntimeException {

    /** details 里放缺了哪些必填项的键。 */
    public static final String MISSING = "missing";

    private final transient OpsError error;
    private final transient Map<String, Object> details;

    public OpsException(OpsError error) {
        this(error, null);
    }

    public OpsException(OpsError error, Map<String, Object> details) {
        super(Objects.requireNonNull(error, "error").code());
        this.error = error;
        this.details = details == null ? null : Map.copyOf(details);
    }

    /** 上线（发布、上架）前必填项没齐：missing 是缺的字段键，按页面上的顺序。 */
    public static OpsException incomplete(OpsError error, List<String> missing) {
        return new OpsException(error, Map.of(MISSING, List.copyOf(missing)));
    }

    public OpsError error() {
        return error;
    }

    public Map<String, Object> details() {
        return details;
    }
}
