/**
 * 创建认领申请请求 DTO（请求体）。
 * <p>
 * 用户在失物招领详情页点击"认领"并提交证明材料时使用的请求体，使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClaimRequest(
        // 认领证明描述：用户对该物品特征、丢失经过等作出的说明，必填（@NotBlank）；
        // @Size(min = 10, max = 1000) 最小 10 字符保证有一定实质内容、减少空泛申领，
        // 最大 1000 字符防止超大提交体
        @NotBlank @Size(min = 10, max = 1000) String proofDescription) {
}
