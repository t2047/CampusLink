/**
 * 失物招领 Agent 内部接口控制器（REST 控制器）。
 *
 * 主要作用与职责：
 *  1. 供"失物招领智能助手 Agent"的服务端（而非浏览器前端）调用的一组内部 REST 端点，
 *     统一以 /api/internal/lost-found 作为前缀，并通过 @PreAuthorize 限定只有
 *     AGENT_LOST_FOUND 角色可访问，避免普通用户绕过界面直接调用。
 *  2. Agent 通过本控制器可完成：
 *      - POST /reports/lost              以 Agent 身份登记一条"寻物（丢失）"报告
 *      - POST /reports/found             以 Agent 身份登记一条"招领（捡到）"报告
 *      - GET  /candidates                检索匹配某条寻物报告的"招领候选"列表（供 Agent 推荐匹配）
 *      - GET  /lost-candidates           检索匹配某条招领报告的"寻物候选"列表
 *      - GET  /reports/{reportId}        获取报告详情
 *      - POST /reports/{reportId}/claims 为指定报告发起认领申请
 *  3. 报告/认领的具体业务委托给 LostFoundReportService / LostFoundClaimService；
 *     Agent 创建的图片先经"暂存（staging）"流程上传，本控制器把暂存的 objectKey 列表传给 Service 完成关联。
 */
package com.app.campusagent.lostfound.controller;

// —— 领域模型与枚举 ——
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportType;
// —— 业务请求/响应 DTO 与通用分页响应 ——
import com.app.campusagent.lostfound.dto.CreateClaimRequest;
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.LostFoundClaimResponse;
import com.app.campusagent.lostfound.dto.LostFoundReportResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
// —— Agent 专用 DTO（候选列表项、建报告请求等）——
import com.app.campusagent.lostfound.dto.agent.AgentCandidateResponse;
import com.app.campusagent.lostfound.dto.agent.AgentCreateFoundReportRequest;
import com.app.campusagent.lostfound.dto.agent.AgentCreateLostReportRequest;
// —— 模块自定义异常与报告/认领服务 ——
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundClaimService;
import com.app.campusagent.lostfound.service.LostFoundReportService;
// —— 校验与 Spring Data 分页/排序 ——
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
// —— Spring Security：角色控制、当前登录用户注入 ——
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
// —— Web MVC 绑定注解 ——
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
// —— JDK 工具类 ——
import java.time.LocalDate;
import java.util.List;

// 标注为 REST 控制器，所有端点统一以 /api/internal/lost-found 作为前缀
@RestController
@RequestMapping("/api/internal/lost-found")
// 类级权限控制：只有拥有 AGENT_LOST_FOUND 角色的调用方（Agent 服务端）可访问
@PreAuthorize("hasRole('AGENT_LOST_FOUND')")
public class LostFoundAgentInternalController {

    // 报告业务服务：负责登记、查询报告与候选检索
    private final LostFoundReportService reportService;
    // 认领业务服务：负责创建认领申请
    private final LostFoundClaimService claimService;

    // 构造器同时注入报告服务与认领服务（Spring 构造器依赖注入）
    public LostFoundAgentInternalController(
            LostFoundReportService reportService,
            LostFoundClaimService claimService) {
        this.reportService = reportService;
        this.claimService = claimService;
    }

    /**
     * POST /api/internal/lost-found/reports/lost
     * Agent 登记一条"寻物（丢失）"报告。
     * 入参：request 为 Agent 专用建报告请求体（物品名/类别/描述/颜色/地点/事件时间等，@Valid 校验）；
     *       currentUser 为当前 Agent 身份。
     * 返回：201 Created，响应体为创建成功后的完整报告信息。
     * 调用方：Agent 对话流程中识别到用户丢失物品时调用。
     */
    @PostMapping("/reports/lost")
    public ResponseEntity<LostFoundReportResponse> reportLost(
            @Valid @RequestBody AgentCreateLostReportRequest request,
            @AuthenticationPrincipal User currentUser) {
        // 把 Agent 专用请求体映射为通用建报告请求 DTO，报告类型固定为 LOST
        CreateLostFoundReportRequest serviceRequest = new CreateLostFoundReportRequest(
                ReportType.LOST,
                request.itemName(),
                request.category(),
                request.description(),
                request.colour(),
                request.location(),
                request.eventDate(),
                request.timeDescription());
        // 使用暂存图片的 objectKey 列表创建报告，并返回 201 与报告响应体
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reportService.createFromStaged(
                        serviceRequest, imageKeys(request.imageKeys()), currentUser));
    }

    /**
     * POST /api/internal/lost-found/reports/found
     * Agent 登记一条"招领（捡到）"报告，逻辑与 reportLost 对称，报告类型固定为 FOUND。
     * 入参：request 为 Agent 专用建报告请求体；currentUser 为当前 Agent 身份。
     * 返回：201 Created，响应体为创建成功后的完整报告信息。
     */
    @PostMapping("/reports/found")
    public ResponseEntity<LostFoundReportResponse> reportFound(
            @Valid @RequestBody AgentCreateFoundReportRequest request,
            @AuthenticationPrincipal User currentUser) {
        // 把 Agent 专用请求体映射为通用建报告请求 DTO，报告类型固定为 FOUND
        CreateLostFoundReportRequest serviceRequest = new CreateLostFoundReportRequest(
                ReportType.FOUND,
                request.itemName(),
                request.category(),
                request.description(),
                request.colour(),
                request.location(),
                request.eventDate(),
                request.timeDescription());
        // 使用暂存图片的 objectKey 列表创建报告，并返回 201 与报告响应体
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reportService.createFromStaged(
                        serviceRequest, imageKeys(request.imageKeys()), currentUser));
    }

    /**
     * GET /api/internal/lost-found/candidates
     * 检索与某条寻物报告相匹配的"招领候选"（报告类型固定为 FOUND）。
     * 入参：keyword/category/colour/location 物品特征筛选，dateFrom/dateTo 事件日期范围，
     *       page/size 分页（默认每页 100 条，供 Agent 批量扫描）。
     * 返回：PageResponse<AgentCandidateResponse> 候选招领报告分页列表。
     * 调用方：Agent 匹配阶段按条件找出可推荐的招领报告。
     */
    @GetMapping("/candidates")
    public PageResponse<AgentCandidateResponse> candidates(
            @RequestParam(required = false) String keyword,       // 关键词模糊搜索
            @RequestParam(required = false) ItemCategory category,// 按种类筛选
            @RequestParam(required = false) String colour,        // 按颜色筛选
            @RequestParam(required = false) String location,      // 按地点筛选
            @RequestParam(required = false) LocalDate dateFrom,   // 事件日期范围下界
            @RequestParam(required = false) LocalDate dateTo,     // 事件日期范围上界
            @RequestParam(defaultValue = "0") int page,           // 页码，从 0 开始
            @RequestParam(defaultValue = "100") int size) {       // 每页条数（默认 100）
        return searchCandidates(
                ReportType.FOUND,   // 候选类型固定为"招领"报告
                keyword,
                category,
                colour,
                location,
                dateFrom,
                dateTo,
                page,
                size);
    }

    /**
     * GET /api/internal/lost-found/lost-candidates
     * 检索与某条招领报告相匹配的"寻物候选"（报告类型固定为 LOST），
     * 逻辑与 candidates 对称，供 Agent 帮捡到物品的用户找失主。
     */
    @GetMapping("/lost-candidates")
    public PageResponse<AgentCandidateResponse> lostCandidates(
            @RequestParam(required = false) String keyword,       // 关键词模糊搜索
            @RequestParam(required = false) ItemCategory category,// 按种类筛选
            @RequestParam(required = false) String colour,        // 按颜色筛选
            @RequestParam(required = false) String location,      // 按地点筛选
            @RequestParam(required = false) LocalDate dateFrom,   // 事件日期范围下界
            @RequestParam(required = false) LocalDate dateTo,     // 事件日期范围上界
            @RequestParam(defaultValue = "0") int page,           // 页码，从 0 开始
            @RequestParam(defaultValue = "100") int size) {       // 每页条数（默认 100）
        return searchCandidates(
                ReportType.LOST,    // 候选类型固定为"寻物"报告
                keyword,
                category,
                colour,
                location,
                dateFrom,
                dateTo,
                page,
                size);
    }

    /**
     * 辅助方法：把可能为 null 的暂存图片 objectKey 列表规范化为空列表，
     * 避免下游因 null 列表而 NPE。
     */
    private static List<String> imageKeys(List<String> imageKeys) {
        // 若 Agent 未上传图片则返回不可变空列表，否则原样返回
        return imageKeys == null ? List.of() : imageKeys;
    }

    /**
     * 私有共用方法：按给定报告类型执行候选检索，并进行分页参数校验。
     * 排序固定为 createdAt 降序（最新优先）。
     */
    private PageResponse<AgentCandidateResponse> searchCandidates(
            ReportType reportType,     // 候选报告的类型（LOST 或 FOUND）
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            int page,
            int size) {
        // 分页约束：页码非负，每页 1~100，否则 422
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        // 委托 Service 执行候选检索，固定按创建时间倒序分页返回
        return reportService.searchCandidates(
                reportType,
                keyword,
                category,
                colour,
                location,
                dateFrom,
                dateTo,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /**
     * GET /api/internal/lost-found/reports/{reportId}
     * Agent 获取指定报告的详情（含图片、状态等完整信息）。
     * 入参：reportId 报告 id；currentUser 当前 Agent 身份（用于权限/可见性判断）。
     * 返回：LostFoundReportResponse 报告详情。
     */
    @GetMapping("/reports/{reportId}")
    public LostFoundReportResponse detail(
            @PathVariable Long reportId,             // 报告 id（路径参数）
            @AuthenticationPrincipal User currentUser) { // 当前 Agent 身份
        return reportService.getById(reportId, currentUser);
    }

    /**
     * POST /api/internal/lost-found/reports/{reportId}/claims
     * Agent 为指定报告发起一条认领申请（例如捡到者认领某条寻物报告）。
     * 入参：reportId 目标报告 id；request 为认领请求体（联系方式、说明等，@Valid 校验）；
     *       currentUser 为当前 Agent 身份。
     * 返回：201 Created，响应体为创建成功的认领记录。
     */
    @PostMapping("/reports/{reportId}/claims")
    public ResponseEntity<LostFoundClaimResponse> claim(
            @PathVariable Long reportId,             // 被认领的报告 id
            @Valid @RequestBody CreateClaimRequest request, // 认领信息请求体
            @AuthenticationPrincipal User currentUser) {    // 当前 Agent 身份
        // 创建认领并返回 201 与认领响应体
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(claimService.create(reportId, request, currentUser));
    }
}
