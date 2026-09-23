package org.wuyang.ytdlp.model;

import java.util.List;

/**
 * 格式列表响应 DTO
 *
 * @param success  是否成功
 * @param message  结果消息
 * @param formats  格式列表
 *
 * @author wuyang
 */
public record FormatListResponse(
        boolean success,
        String message,
        List<Format> formats
) {

    /** 构造失败响应 */
    public static FormatListResponse fail(String message) {
        return new FormatListResponse(false, message, List.of());
    }

    /** 构造成功响应 */
    public static FormatListResponse ok(List<Format> formats) {
        return new FormatListResponse(true, "获取成功", formats);
    }
}
