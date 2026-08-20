/**
 * 管理后台【用户摘要】嵌套 DTO（dto/admin 子包）。
 *
 * <p>用于认领人（claimant）与报告发布者（owner）两种场景的列表摘要展示，
 * 仅暴露 id 与 email，避免把角色等敏感信息带入列表接口。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

public record AdminClaimUserSummary(
        // 用户主键
        Long id,
        // 用户邮箱
        String email) {
}
