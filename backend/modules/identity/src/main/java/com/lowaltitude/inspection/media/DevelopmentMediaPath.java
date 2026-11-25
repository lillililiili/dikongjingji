package com.lowaltitude.inspection.media;

import java.util.regex.Pattern;

/** A narrow same-origin alternative for media supplied by development seeds. */
public final class DevelopmentMediaPath {
    public static final String PREFIX = "/api/v1/dev-media/";
    private static final Pattern PATH = Pattern.compile(PREFIX
            + "(?:images/(?:preview|home|inspection)/[a-z0-9-]+[.](?:jpg|png|webp)|videos/preview/[a-z0-9-]+[.]mp4)");

    private DevelopmentMediaPath() {}

    public static boolean matches(String url) {
        return url != null && PATH.matcher(url).matches();
    }
}
