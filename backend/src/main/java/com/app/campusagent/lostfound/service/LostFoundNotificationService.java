/**
 * 失物招领模块的"站内通知服务"（Notification Service）。
 *
 * <p>职责：围绕失物招领的认领（claim）流程生成站内通知并落库，供收件人在"通知中心"
 * 查看自己的通知列表、统计未读数、标记已读。通知主题覆盖：认领已提交（告知失主）、
 * 认领已通过（告知认领人 + 告知失主报告已被认领）、认领已驳回（告知认领人）。</p>
 *
 * <p>调用方式：写操作方法（claimSubmitted/claimApproved/claimRejected）由认领管理/审批
 * 业务服务在状态流转成功后调用；查询方法（mine/unreadCount/markRead）由当前登录用户
 * 通过 Controller 层触发。本服务不是定时任务，无 @Scheduled 配置。</p>
 *
 * <p>事务特性：写操作标注 @Transactional，与调用方的认领状态变更处于同一事务 ——
 * 状态变更与通知写入一起提交或一起回滚，避免"状态改了却没发通知"的不一致；
 * 查询方法标注 {@code readOnly = true}，交由数据库/ORM 层做只读优化。</p>
 */
package com.app.campusagent.lostfound.service;

// User：用户实体，作为通知收件人（recipient）
import com.app.campusagent.domain.User;
// LostFoundClaim：认领实体，通知关联的目标认领
import com.app.campusagent.lostfound.domain.LostFoundClaim;
// LostFoundNotification：通知实体，对应数据库 lost_found_notification 表
import com.app.campusagent.lostfound.domain.LostFoundNotification;
// NotificationType：通知类型枚举（CLAIM_SUBMITTED / CLAIM_APPROVED 等）
import com.app.campusagent.lostfound.domain.NotificationType;
// LostFoundNotificationResponse：通知出参 DTO，用于 API 返回
import com.app.campusagent.lostfound.dto.LostFoundNotificationResponse;
// PageResponse：统一分页响应封装
import com.app.campusagent.lostfound.dto.PageResponse;
// LostFoundApiException：模块自定义业务异常，携带 HTTP 状态码与错误码
import com.app.campusagent.lostfound.exception.LostFoundApiException;
// LostFoundNotificationRepository：通知仓库，提供查询/计数/保存
import com.app.campusagent.lostfound.repository.LostFoundNotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LostFoundNotificationService {

    // 通知仓库：本服务的唯一依赖，负责通知的持久化与查询
    private final LostFoundNotificationRepository repository;

    /**
     * 构造器注入通知仓库（Spring 自动装配单例 bean）。
     *
     * @param repository 通知仓库
     */
    public LostFoundNotificationService(LostFoundNotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * 通知"失主"：有人对其报告提交了认领申请。
     *
     * @param claim 新提交的认领实体（其关联的报告确定收件人为失主）
     * 事务特性：@Transactional，加入调用方（认领提交服务）事务，与认领状态变更同生共死。
     */
    @Transactional
    public void claimSubmitted(LostFoundClaim claim) {
        // 收件人为报告的发布者（失主），类型 CLAIM_SUBMITTED；标题/内容拼装后落库
        save(
                claim.getReport().getCreatedBy(),
                NotificationType.CLAIM_SUBMITTED,
                claim,
                "New claim submitted",
                "A user submitted an ownership claim for " + claim.getReport().getItemName() + ".");
    }

    /**
     * 认领被通过时向双方各发一条通知：
     * 1) 告知认领人"认领已通过"；2) 告知失主"报告已被标记为认领"。
     *
     * @param claim 已审批通过的认领实体
     * 事务特性：@Transactional，两次 save 在同一事务内，要么都成功、要么都回滚。
     */
    @Transactional
    public void claimApproved(LostFoundClaim claim) {
        // 第一条：给认领人（claimant）发 CLAIM_APPROVED 通知
        save(
                claim.getClaimant(),
                NotificationType.CLAIM_APPROVED,
                claim,
                "Claim approved",
                "Your claim for " + claim.getReport().getItemName() + " was approved.");
        // 第二条：给失主（报告发布者）发 REPORT_CLAIMED 通知，告知物品已被认领
        save(
                claim.getReport().getCreatedBy(),
                NotificationType.REPORT_CLAIMED,
                claim,
                "Report marked claimed",
                claim.getReport().getItemName() + " has been marked as claimed.");
    }

    /**
     * 认领被驳回时通知认领人。
     *
     * @param claim 被驳回的认领实体
     * 事务特性：@Transactional，加入调用方（驳回服务）事务。
     */
    @Transactional
    public void claimRejected(LostFoundClaim claim) {
        // 收件人为认领人（claimant），类型 CLAIM_REJECTED
        save(
                claim.getClaimant(),
                NotificationType.CLAIM_REJECTED,
                claim,
                "Claim rejected",
                "Your claim for " + claim.getReport().getItemName() + " was rejected.");
    }

    /**
     * 查询当前用户的站内通知列表（分页），可按"只看未读"过滤。
     *
     * @param currentUser 当前登录用户，即收件人
     * @param pageable    Spring Data 分页参数（页码 / 页大小 / 排序）
     * @param unreadOnly  true 时只返回未读通知，false 返回全部通知
     * @return 统一分页响应，元素为通知出参 DTO（含已读标记、关联报告/认领 ID）
     * 事务特性：readOnly 只读事务，提示数据库/ORM 层做只读优化。
     */
    @Transactional(readOnly = true)
    public PageResponse<LostFoundNotificationResponse> mine(
            User currentUser,
            Pageable pageable,
            boolean unreadOnly) {
        // 收件人固定为当前用户 ID，防止越权查询他人通知
        Long recipientId = currentUser.getId();
        // 按是否需要"只看未读"选择不同的仓库查询（两者都带分页参数）
        Page<LostFoundNotification> page = unreadOnly
                ? repository.findByRecipientIdAndReadAtIsNull(recipientId, pageable)
                : repository.findByRecipientId(recipientId, pageable);
        // 把实体映射为出参 DTO 后再包装成统一分页响应
        return PageResponse.from(page.map(this::toResponse));
    }

    /**
     * 统计当前用户的未读通知数量（用于角标/徽标展示）。
     *
     * @param currentUser 当前登录用户，即收件人
     * @return 未读通知条数（long）
     * 事务特性：readOnly 只读事务。
     */
    @Transactional(readOnly = true)
    public long unreadCount(User currentUser) {
        // 按"收件人 + readAt 为空"计数，即未读数量
        return repository.countByRecipientIdAndReadAtIsNull(currentUser.getId());
    }

    /**
     * 将某条通知标记为已读。
     *
     * @param id          通知 ID
     * @param currentUser 当前登录用户，仅允许本人标记自己的通知
     * @return 更新后的通知出参 DTO（read 字段为 true）
     * @throws LostFoundApiException 通知不存在或不属于当前用户时抛出
     *                               （404 + 错误码 NOTIFICATION_NOT_FOUND）
     * 事务特性：@Transactional，find + markRead + save 在同一事务内完成。
     */
    @Transactional
    public LostFoundNotificationResponse markRead(Long id, User currentUser) {
        // 按"ID + 收件人"联合查询：既定位通知，又保证只能操作自己的通知
        LostFoundNotification notification = repository.findByIdAndRecipientId(id, currentUser.getId())
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "NOTIFICATION_NOT_FOUND",
                        "The requested notification does not exist"));
        // 把 readAt 置为当前时间（实体内部方法），再保存并返回最新状态
        notification.markRead();
        return toResponse(repository.save(notification));
    }

    /**
     * 内部辅助：统一组装并保存一条通知实体，供各业务方法复用。
     *
     * @param recipient 收件人（失主或认领人）
     * @param type      通知类型枚举
     * @param claim     关联的认领实体（据此取得关联报告）
     * @param title     通知标题
     * @param message   通知正文
     */
    private void save(
            User recipient,
            NotificationType type,
            LostFoundClaim claim,
            String title,
            String message) {
        // 构造通知实体：同时关联报告与认领，便于前端从通知跳转到对应详情
        repository.save(new LostFoundNotification(
                recipient,
                type,
                claim.getReport(),
                claim,
                title,
                message));
    }

    /**
     * 内部辅助：把通知实体映射为出参 DTO（组装前端所需字段）。
     *
     * @param notification 通知实体
     * @return 通知出参 DTO；read 由 readAt 是否非空推导得出
     */
    private LostFoundNotificationResponse toResponse(LostFoundNotification notification) {
        // 报告/认领可能为空（如报告被删除），此时对应 ID 传 null，交由前端兜底处理
        return new LostFoundNotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getReport() == null ? null : notification.getReport().getId(),
                notification.getClaim() == null ? null : notification.getClaim().getId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getReadAt() != null,
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
