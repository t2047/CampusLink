/**
 * 失物招领通知业务控制器（REST 控制器）。
 *
 * 主要作用与职责：
 *  1. 面向普通登录用户，提供失物招领模块内的站内通知（如报告状态变化、认领申请、
 *     审批结果等触发的提醒）查询与已读标记的一族 REST 端点，
 *     统一以 /api/lost-found/notifications 作为前缀。
 *  2. 端点覆盖：
 *      - GET  /notifications          分页查询当前用户的通知，可按"仅未读"过滤
 *      - GET  /notifications/unread-count  查询当前用户的未读通知数（供角标展示）
 *      - POST /notifications/{id}/read    将某条通知标记为已读
 *  3. 所有业务逻辑委托给 LostFoundNotificationService；本类只做参数绑定与透传当前登录用户。
 */
package com.app.campusagent.lostfound.controller;

// —— 领域模型与 DTO ——
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.LostFoundNotificationResponse;
import com.app.campusagent.lostfound.dto.UnreadNotificationCountResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
// —— 模块自定义异常与通知服务 ——
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundNotificationService;
// —— Spring Data 分页/排序与 HTTP 状态码 ——
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
// —— 登录用户注入与 Web MVC 绑定注解 ——
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 标注为 REST 控制器，所有端点统一以 /api/lost-found/notifications 作为前缀
@RestController
@RequestMapping("/api/lost-found/notifications")
public class LostFoundNotificationController {

    // 通知业务服务：查询、未读计数、标记已读的具体实现
    private final LostFoundNotificationService service;

    // 构造器注入通知服务（Spring 构造器依赖注入）
    public LostFoundNotificationController(LostFoundNotificationService service) {
        this.service = service;
    }

    /**
     * GET /api/lost-found/notifications
     * 分页查询当前登录用户的通知列表，可按"仅看未读"过滤。
     * 入参：page/size 分页参数；unreadOnly 是否只看未读；currentUser 当前登录用户。
     * 返回：PageResponse<LostFoundNotificationResponse> 分页通知列表（按创建时间倒序）。
     * 调用方：用户中心"消息通知"页面。
     */
    @GetMapping
    public PageResponse<LostFoundNotificationResponse> mine(
            @RequestParam(defaultValue = "0") int page,        // 页码，从 0 开始
            @RequestParam(defaultValue = "20") int size,       // 每页条数（默认 20）
            @RequestParam(defaultValue = "false") boolean unreadOnly, // true 时仅返回未读通知
            @AuthenticationPrincipal User currentUser) {       // 当前登录用户（只看自己的通知）
        return service.mine(currentUser, pageable(page, size), unreadOnly);
    }

    /**
     * GET /api/lost-found/notifications/unread-count
     * 查询当前登录用户的未读通知数量（用于前端红点/角标）。
     * 入参：currentUser 当前登录用户。
     * 返回：UnreadNotificationCountResponse（内含未读数量）。
     * 调用方：用户中心或导航栏的"未读角标"轮询接口。
     */
    @GetMapping("/unread-count")
    public UnreadNotificationCountResponse unreadCount(
            @AuthenticationPrincipal User currentUser) {
        // 包装未读数量为响应对象返回
        return new UnreadNotificationCountResponse(service.unreadCount(currentUser));
    }

    /**
     * POST /api/lost-found/notifications/{id}/read
     * 将指定通知标记为已读。
     * 入参：id 通知 id（路径参数）；currentUser 当前登录用户（只能标记自己的通知）。
     * 返回：LostFoundNotificationResponse 标记已读后的通知对象。
     * 调用方：用户点击某条通知或"全部已读"时调用。
     */
    @PostMapping("/{id}/read")
    public LostFoundNotificationResponse markRead(
            @PathVariable Long id,                  // 要标记已读的通知 id
            @AuthenticationPrincipal User currentUser) { // 当前登录用户（归属校验由 Service 完成）
        return service.markRead(id, currentUser);
    }

    /**
     * 私有辅助方法：构造通知列表的分页排序对象并做参数校验。
     * 排序固定为 createdAt 降序（最新通知在前）。
     */
    private Pageable pageable(int page, int size) {
        // 分页约束：页码非负，每页 1~100，否则 422
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        // 按创建时间倒序分页返回
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
