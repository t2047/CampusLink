/**
 * 失物招领认领业务控制器（REST 控制器）。
 *
 * 主要作用与职责：
 *  1. 面向普通登录用户，提供"认领（Claim）"业务的核心 REST 端点，
 *     统一以 /api/lost-found 作为前缀，是用户前端（网页/小程序）调用的一族接口。
 *  2. 端点覆盖：
 *      - POST /reports/{reportId}/claims     为某条报告发起认领申请（用户端）
 *      - GET  /claims/mine                   我发出的认领记录（我是认领人）
 *      - GET  /claims/received               我收到的认领记录（我是报告发布人）
 *      - POST /claims/{claimId}/approve      通过某条认领申请（发布人操作）
 *      - POST /claims/{claimId}/reject       驳回某条认领申请（发布人操作）
 *  3. 所有业务逻辑委托给 LostFoundClaimService；本类只做参数绑定与透传当前登录用户，
 *     权限判断（是否本人报告、是否本人认领等）由 Service 层完成。
 */
package com.app.campusagent.lostfound.controller;

// —— 领域模型与 DTO ——
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.ClaimDecisionRequest;
import com.app.campusagent.lostfound.dto.CreateClaimRequest;
import com.app.campusagent.lostfound.dto.LostFoundClaimResponse;
import com.app.campusagent.lostfound.service.LostFoundClaimService;
// —— 校验、HTTP 状态码/响应、登录用户注入 ——
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
// —— Web MVC 绑定注解 ——
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
// —— JDK 工具类 ——
import java.util.List;

// 标注为 REST 控制器，所有端点统一以 /api/lost-found 作为前缀
@RestController
@RequestMapping("/api/lost-found")
public class LostFoundClaimController {

    // 认领业务服务：创建、查询、审批认领的具体实现
    private final LostFoundClaimService claimService;

    // 构造器注入认领服务（Spring 构造器依赖注入）
    public LostFoundClaimController(LostFoundClaimService claimService) {
        this.claimService = claimService;
    }

    /**
     * POST /api/lost-found/reports/{reportId}/claims
     * 用户端为指定报告发起一条认领申请（例如捡到者申请认领某条寻物报告）。
     * 入参：reportId 目标报告 id（路径参数）；request 为认领信息请求体（@Valid 校验，含联系方式/说明等）；
     *       currentUser 为当前登录用户（认领发起人）。
     * 返回：201 Created，响应体为创建成功的认领记录。
     * 调用方：用户前端"报告详情页"的"我要认领"按钮。
     */
    @PostMapping("/reports/{reportId}/claims")
    public ResponseEntity<LostFoundClaimResponse> create(
            @PathVariable Long reportId,                 // 被认领的报告 id
            @Valid @RequestBody CreateClaimRequest request, // 认领信息请求体
            @AuthenticationPrincipal User currentUser) {     // 当前登录用户（认领人）
        // 创建认领并返回 201 与认领响应体
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(claimService.create(reportId, request, currentUser));
    }

    /**
     * GET /api/lost-found/claims/mine
     * 查询当前登录用户"作为认领人"发出的所有认领记录。
     * 入参：currentUser 当前登录用户。
     * 返回：List<LostFoundClaimResponse> 认领记录列表（非分页，按最近优先）。
     * 调用方：用户中心"我认领的"页面。
     */
    @GetMapping("/claims/mine")
    public List<LostFoundClaimResponse> mine(@AuthenticationPrincipal User currentUser) {
        return claimService.mine(currentUser);
    }

    /**
     * GET /api/lost-found/claims/received
     * 查询当前登录用户"作为报告发布人"收到的所有认领申请。
     * 入参：currentUser 当前登录用户。
     * 返回：List<LostFoundClaimResponse> 收到的认领记录列表。
     * 调用方：用户中心"收到的认领申请"页面（据此通过/驳回申请）。
     */
    @GetMapping("/claims/received")
    public List<LostFoundClaimResponse> received(@AuthenticationPrincipal User currentUser) {
        return claimService.received(currentUser);
    }

    /**
     * POST /api/lost-found/claims/{claimId}/approve
     * 报告发布人通过一条认领申请，认领成功后认领人可凭此取回物品。
     * 入参：claimId 认领 id；request 为决定请求体（可含备注，@Valid 校验）；currentUser 当前登录用户。
     * 返回：LostFoundClaimResponse 状态更新后的认领记录。
     * 调用方：用户中心"收到的认领申请"里的"通过"按钮。
     */
    @PostMapping("/claims/{claimId}/approve")
    public LostFoundClaimResponse approve(
            @PathVariable Long claimId,                 // 要通过的认领 id
            @Valid @RequestBody ClaimDecisionRequest request, // 审批决定（含备注）
            @AuthenticationPrincipal User currentUser) {     // 当前登录用户（报告发布人）
        return claimService.approve(claimId, request, currentUser);
    }

    /**
     * POST /api/lost-found/claims/{claimId}/reject
     * 报告发布人驳回一条认领申请，逻辑与 approve 对称。
     * 入参：claimId 认领 id；request 为决定请求体（可含备注，@Valid 校验）；currentUser 当前登录用户。
     * 返回：LostFoundClaimResponse 状态更新后的认领记录。
     * 调用方：用户中心"收到的认领申请"里的"驳回"按钮。
     */
    @PostMapping("/claims/{claimId}/reject")
    public LostFoundClaimResponse reject(
            @PathVariable Long claimId,                 // 要驳回的认领 id
            @Valid @RequestBody ClaimDecisionRequest request, // 驳回决定（含备注）
            @AuthenticationPrincipal User currentUser) {     // 当前登录用户（报告发布人）
        return claimService.reject(claimId, request, currentUser);
    }
}
