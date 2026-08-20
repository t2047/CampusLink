/**
 * 管理后台【认领人详情】嵌套 DTO（dto/admin 子包）。
 *
 * <p>作为 AdminClaimDetailResponse.claimant 的嵌套结构出现，
 * 携带认领人的 id、邮箱与角色，便于管理员核实申请人身份。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.domain.Role;

public record AdminClaimUserDetail(
        // 用户主键
        Long id,
        // 用户邮箱
        String email,
        // 用户角色（Role 枚举）：STUDENT 学生 / ADMIN 管理员 / SUPER_ADMIN 超级管理员
        Role role) {
}
