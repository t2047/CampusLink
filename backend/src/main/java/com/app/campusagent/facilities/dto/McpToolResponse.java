package com.app.campusagent.facilities.dto;

public record McpToolResponse<T>(boolean success, T data, ToolError error) {

    public static <T> McpToolResponse<T> success(T data) {
        return new McpToolResponse<>(true, data, null);
    }

    public static <T> McpToolResponse<T> failure(String code, String message) {
        return new McpToolResponse<>(false, null, new ToolError(code, message));
    }

    public record ToolError(String code, String message) {
    }
}
