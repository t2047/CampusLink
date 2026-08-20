/**
 * 失物招领报告业务控制器（REST 控制器）。
 *
 * 主要作用与职责：
 *  1. 面向普通登录用户，提供"招领/寻物报告（Report）"的核心 REST 端点，
 *     统一以 /api/lost-found 作为前缀，是用户前端（网页/小程序）的主要入口。
 *  2. 端点覆盖：
 *      - POST   /reports                  创建报告（multipart，report JSON + 可选多张图片）
 *      - GET    /reports                  分页检索报告（多条件组合筛选 + 排序）
 *      - GET    /reports/{reportId}       报告详情
 *      - PUT    /reports/{reportId}       编辑更新报告（multipart，可替换图片）
 *      - POST   /reports/{reportId}/close 关闭报告（结束招领/寻物流程）
 *      - DELETE /reports/{reportId}       删除报告（仅本人，逻辑删除）
 *      - GET    /metadata                 返回前端下拉框所需的枚举元数据（类型/类别/状态）
 *  3. 所有业务逻辑委托给 LostFoundReportService；本类负责参数绑定、
 *     multipart 文件接收、分页/排序参数校验，并透传当前登录用户。
 */
package com.app.campusagent.lostfound.controller;

// —— 领域模型与枚举 ——
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
// —— 业务请求/响应 DTO ——
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.LostFoundMetadataResponse;
import com.app.campusagent.lostfound.dto.LostFoundReportResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.UpdateLostFoundReportRequest;
// —— 模块自定义异常与报告服务 ——
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundReportService;
// —— 校验、Spring Data 分页/排序、HTTP 相关 ——
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
// —— 登录用户注入与 Web MVC 绑定注解 ——
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
// —— JDK 工具类 ——
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

// 标注为 REST 控制器，所有端点统一以 /api/lost-found 作为前缀
@RestController
@RequestMapping("/api/lost-found")
public class LostFoundReportController {

    // 报告列表允许的排序字段白名单：防止外部传入任意 JPA 属性路径造成注入或报错
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "eventDate", "itemName");

    // 报告业务服务：创建、检索、详情、更新、关闭、删除的具体实现
    private final LostFoundReportService reportService;

    // 构造器注入报告服务（Spring 构造器依赖注入）
    public LostFoundReportController(LostFoundReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * POST /api/lost-found/reports （Content-Type: multipart/form-data）
     * 创建一条新的招领/寻物报告，可同时上传多张图片。
     * 入参：request 为 multipart 的 "report" 字段（JSON 报告信息，@Valid 校验）；
     *       images 为可选的多张图片文件；currentUser 为当前登录用户（报告发布人）。
     * 返回：201 Created，响应体为创建成功的完整报告信息。
     * 调用方：用户前端"发布招领/寻物"表单提交。
     */
    @PostMapping(value = "/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LostFoundReportResponse> create(
            @Valid @RequestPart("report") CreateLostFoundReportRequest request, // multipart 中的 JSON 报告信息
            @RequestPart(value = "images", required = false) List<MultipartFile> images, // 可选的多张图片
            @AuthenticationPrincipal User currentUser) {      // 当前登录用户（发布人）
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reportService.create(request, images, currentUser));
    }

    /**
     * GET /api/lost-found/reports
     * 分页检索招领/寻物报告，支持报告类型、关键词、类别、颜色、地点、
     * 事件日期范围、状态、发布人等条件组合筛选，并支持排序。
     * 入参：见各 @RequestParam；currentUser 当前登录用户（用于可见性/权限判断）。
     * 返回：PageResponse<LostFoundReportResponse> 分页报告列表。
     * 调用方：用户前端"失物招领广场/列表"页。
     */
    @GetMapping("/reports")
    public PageResponse<LostFoundReportResponse> search(
            @RequestParam(required = false) ReportType reportType,    // 报告类型（LOST/FOUND），不传则不限
            @RequestParam(required = false) String keyword,           // 关键词，对名称/描述等做模糊匹配
            @RequestParam(required = false) ItemCategory category,    // 按物品种类筛选
            @RequestParam(required = false) String colour,            // 按物品颜色筛选
            @RequestParam(required = false) String location,          // 按丢失/捡到地点筛选
            @RequestParam(required = false) LocalDate dateFrom,       // 事件日期范围下界（含当天）
            @RequestParam(required = false) LocalDate dateTo,         // 事件日期范围上界（含当天）
            @RequestParam(required = false) ReportStatus status,      // 按报告状态筛选（OPEN/CLOSED 等）
            @RequestParam(required = false) String owner,             // 按发布人标识筛选（邮箱等）
            @RequestParam(defaultValue = "0") int page,               // 页码，从 0 开始
            @RequestParam(defaultValue = "20") int size,              // 每页条数（默认 20）
            @RequestParam(defaultValue = "createdAt,desc") String sort, // 排序："字段,asc|desc"
            @AuthenticationPrincipal User currentUser) {              // 当前登录用户（可见性判断）
        // 把查询参数、校验后的分页排序对象与当前用户一并交给 Service 执行组合查询
        return reportService.search(
                reportType,
                keyword,
                category,
                colour,
                location,
                dateFrom,
                dateTo,
                status,
                owner,
                pageable(page, size, sort),
                currentUser);
    }

    /**
     * GET /api/lost-found/reports/{reportId}
     * 获取单条报告的完整详情（含图片、状态、发布人等信息）。
     * 入参：reportId 报告 id（路径参数）；currentUser 当前登录用户（可见性/权限判断）。
     * 返回：LostFoundReportResponse 报告详情。
     * 调用方：用户前端"报告详情页"。
     */
    @GetMapping("/reports/{reportId}")
    public LostFoundReportResponse getById(
            @PathVariable Long reportId,                 // 报告 id
            @AuthenticationPrincipal User currentUser) { // 当前登录用户
        return reportService.getById(reportId, currentUser);
    }

    /**
     * PUT /api/lost-found/reports/{reportId} （Content-Type: multipart/form-data）
     * 编辑更新一条报告（仅发布人本人可操作），可更新字段并替换图片。
     * 入参：reportId 报告 id；request 为 multipart 的 "report" 字段（更新信息，@Valid 校验）；
     *       images 为可选的新图片；currentUser 当前登录用户（须为发布人）。
     * 返回：LostFoundReportResponse 更新后的报告信息。
     * 调用方：用户前端"编辑我的报告"表单提交。
     */
    @PutMapping(value = "/reports/{reportId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LostFoundReportResponse update(
            @PathVariable Long reportId,                 // 要更新的报告 id
            @Valid @RequestPart("report") UpdateLostFoundReportRequest request, // multipart 中的 JSON 更新信息
            @RequestPart(value = "images", required = false) List<MultipartFile> images, // 可选的新图片
            @AuthenticationPrincipal User currentUser) { // 当前登录用户（须为报告发布人）
        return reportService.update(reportId, request, images, currentUser);
    }

    /**
     * POST /api/lost-found/reports/{reportId}/close
     * 关闭一条报告（例如物品已找到/已归还，流程结束），仅发布人本人可操作。
     * 入参：reportId 报告 id；currentUser 当前登录用户。
     * 返回：LostFoundReportResponse 关闭后的报告信息（状态变为 CLOSED）。
     * 调用方：用户前端报告详情页的"关闭报告"按钮。
     */
    @PostMapping("/reports/{reportId}/close")
    public LostFoundReportResponse close(
            @PathVariable Long reportId,                 // 要关闭的报告 id
            @AuthenticationPrincipal User currentUser) { // 当前登录用户（须为发布人）
        return reportService.close(reportId, currentUser);
    }

    /**
     * DELETE /api/lost-found/reports/{reportId}
     * 删除一条报告（仅发布人本人，逻辑删除），返回 204 No Content。
     * 入参：reportId 报告 id；currentUser 当前登录用户。
     * 调用方：用户前端报告详情页的"删除报告"按钮。
     */
    @DeleteMapping("/reports/{reportId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long reportId,                 // 要删除的报告 id
            @AuthenticationPrincipal User currentUser) { // 当前登录用户（须为发布人）
        // 执行删除，成功后返回 204 No Content
        reportService.delete(reportId, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/lost-found/metadata
     * 返回前端表单/筛选所需的下拉枚举元数据：报告类型、物品种类、报告状态、认领状态。
     * 无入参，返回 LostFoundMetadataResponse（各枚举的名称列表）。
     * 调用方：用户前端加载页面时初始化下拉框选项。
     */
    @GetMapping("/metadata")
    public LostFoundMetadataResponse metadata() {
        // 把四个枚举的 name 列表装配成元数据响应体
        return new LostFoundMetadataResponse(
                names(ReportType.values()),   // 报告类型（LOST / FOUND）
                names(ItemCategory.values()), // 物品种类
                names(ReportStatus.values()), // 报告状态
                names(ClaimStatus.values())); // 认领状态
    }

    /**
     * 构造"报告列表"查询的分页排序对象，并进行参数合法性校验。
     * 入参：page/size 分页参数，sortValue 形如 "createdAt,desc" 的排序串。
     * 返回：Spring Data 的 Pageable。
     * 校验失败（分页越界 / 排序字段或方向非法）时抛出 LostFoundApiException（422）。
     */
    private Pageable pageable(int page, int size, String sortValue) {
        // 分页约束：页码非负，每页条数在 1~100 之间，否则 422
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        // 按第一个逗号拆分排序串为 [字段, 方向]，方向可省略
        String[] parts = sortValue.split(",", 2);
        String field = parts[0];
        // 排序字段必须命中白名单，防止任意属性路径注入
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SORT_FIELD",
                    "sort field must be createdAt, eventDate or itemName");
        }
        // 解析排序方向；省略方向时默认升序 ASC，非法方向抛 422
        Sort.Direction direction = parts.length == 2
                ? Sort.Direction.fromOptionalString(parts[1]).orElseThrow(() ->
                        new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_SORT_DIRECTION",
                                "sort direction must be asc or desc"))
                : Sort.Direction.ASC;
        // 组装带分页与排序的 Pageable 对象
        return PageRequest.of(page, size, Sort.by(direction, field));
    }

    /**
     * 私有辅助方法：把枚举数组映射为枚举名称字符串列表（供 metadata 接口使用）。
     */
    private List<String> names(Enum<?>[] values) {
        // 用流式处理把每个枚举转成其 name 字符串并收集为列表
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
