/**
 * 失物招领（Lost & Found）模块的「通知」仓储接口。
 *
 * <p>负责 {@link LostFoundNotification}（站内通知：认领进展、报告状态变化等）实体的
 * 持久化访问，基于 {@code Long} 主键继承 {@link JpaRepository} 的标准 CRUD。
 * 主要提供按接收人查询通知、统计未读数、通知归属校验以及按报告级联清理等能力。</p>
 */
package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 通知仓储。
 *
 * <p>通知以 {@code recipient}（接收人）为核心维度：个人中心需要"全部通知"、
 * "未读通知"和"未读角标计数"三套查询；点击单条通知时需做接收人归属校验；
 * 报告被（硬）删除时需级联清理其通知。</p>
 */
public interface LostFoundNotificationRepository extends JpaRepository<LostFoundNotification, Long> {

    /**
     * 分页查询某接收人的全部通知，按创建时间倒序。
     *
     * <p>对应个人中心的"全部通知"列表。
     * 生成 SQL 语义：{@code WHERE recipient_id = ? ORDER BY created_at DESC}。</p>
     *
     * @param recipientId 接收人（用户）主键
     * @param pageable    分页参数
     * @return 该接收人的通知分页结果
     */
    Page<LostFoundNotification> findByRecipientId(Long recipientId, Pageable pageable);

    /**
     * 分页查询某接收人未读（{@code readAt} 为 {@code null}）的通知。
     *
     * <p>对应个人中心的"未读"筛选。
     * 生成 SQL 语义：{@code WHERE recipient_id = ? AND read_at IS NULL}。</p>
     *
     * @param recipientId 接收人（用户）主键
     * @param pageable    分页参数
     * @return 该接收人未读通知的分页结果
     */
    Page<LostFoundNotification> findByRecipientIdAndReadAtIsNull(Long recipientId, Pageable pageable);

    /**
     * 统计某接收人的未读通知数量。
     *
     * <p>用于个人中心的"未读角标"数字。
     * 生成 SQL 语义：{@code SELECT COUNT(*) FROM lost_found_notifications
     * WHERE recipient_id = ? AND read_at IS NULL}。</p>
     *
     * @param recipientId 接收人（用户）主键
     * @return 未读通知数量
     */
    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    /**
     * 按「通知主键 + 接收人」精确查询单条通知。
     *
     * <p>用于点击某条通知查看详情时做归属校验：仅当通知确实属于当前登录用户时才返回
     * 结果，否则返回 {@link Optional#empty()}，从而防止越权读取他人通知。
     * 生成 SQL 语义：{@code WHERE id = ? AND recipient_id = ?}。</p>
     *
     * @param id          通知主键
     * @param recipientId 接收人（用户）主键
     * @return 匹配的通知（若存在），否则为空
     */
    Optional<LostFoundNotification> findByIdAndRecipientId(Long id, Long recipientId);

    /**
     * 删除某个报告关联的全部通知。
     *
     * <p>在报告被（硬）删除时调用，用于级联清理以该报告为上下文的通知数据。
     * 生成 SQL 语义：{@code DELETE FROM lost_found_notifications WHERE report_id = ?}。</p>
     *
     * @param reportId 报告主键
     */
    void deleteByReportId(Long reportId);
}
