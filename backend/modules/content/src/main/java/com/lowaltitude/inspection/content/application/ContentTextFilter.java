package com.lowaltitude.inspection.content.application;

/**
 * 评论的关键词过滤（计划 §4 第 3 条：过关键词过滤后直接显示）。开发环境用一个小词表
 * （ContentWordListFilter）；以后换成正式的过滤服务时实现这个接口即可。
 */
public interface ContentTextFilter {

    /** 这段文字不能发布时为 true。 */
    boolean blocks(String text);
}
