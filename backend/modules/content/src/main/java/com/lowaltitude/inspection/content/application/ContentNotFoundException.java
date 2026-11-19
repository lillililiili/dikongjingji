package com.lowaltitude.inspection.content.application;

/** 内容或专题不存在、没有发布，或不在这个城市展示。对外是通用的 404。 */
public final class ContentNotFoundException extends RuntimeException {

    public ContentNotFoundException() {
        super("content not found");
    }
}
