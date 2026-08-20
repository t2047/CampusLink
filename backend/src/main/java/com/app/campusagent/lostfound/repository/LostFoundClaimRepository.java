/**
 * 失物招领（Lost & Found）模块的「认领单」仓储接口。
 *
 * <p>负责 {@link LostFoundClaim}（用户对失物招领报告提交的认领请求）实体的持久化访问。
 * 该接口继承两个 Spring Data JPA 接口：</p>
 * <ul>
 *   <li>{@link JpaRepository}：基于 {@code Long} 主键的标准 CRUD；</li>
 *   <li>{@link JpaSpecificationExecutor}：基于 {@link Specification} 的动态组合查询，
 *       支撑管理端按报告、认领人、状态等条件多维筛选认领单。</li>
 * </ul>
 */
package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

/**
 * 认领单仓储。
 *
 * <p>本接口的查询方法均由 Spring Data 依据方法名自动推导 SQL，无需手写 JPQL。
 * 方法主要服务于"我的认领""我发布的报告收到的认领""认领审核"等业务场景。</p>
 */
public interface LostFoundClaimRepository
        extends JpaRepository<LostFoundClaim, Long>,
                JpaSpecificationExecutor<LostFoundClaim> {

    /**
     * 统计处于指定状态的认领单数量。
     *
     * <p>生成 SQL 语义：{@code SELECT COUNT(*) FROM lost_found_claims WHERE status = ?}。</p>
     *
     * @param status 认领状态（SUBMITTED / APPROVED / REJECTED 等，见 {@link ClaimStatus}）
     * @return 处于该状态的认领单总数
     */
    long countByStatus(ClaimStatus status);

    /**
     * 判断"某个报告 + 某个认领人"组合下是否已存在处于给定状态集合中的认领单。
     *
     * <p>用于创建认领单前的防重复校验：避免同一用户对同一报告重复提交认领。
     * 生成 SQL 语义：{@code WHERE report_id = ? AND claimant_id = ? AND status IN (?)}。</p>
     *
     * @param reportId   报告主键
     * @param claimantId 认领人（用户）主键
     * @param statuses   需要检查的状态集合
     * @return 若已存在任一匹配记录返回 {@code true}，否则返回 {@code false}
     */
    boolean existsByReportIdAndClaimantIdAndStatusIn(
            Long reportId,
            Long claimantId,
            Collection<ClaimStatus> statuses);

    /**
     * 查询某认领人提交的全部认领单，按创建时间倒序排列。
     *
     * <p>对应"我的认领"列表，最新提交的认领排在最前。
     * 生成 SQL 语义：{@code WHERE claimant_id = ? ORDER BY created_at DESC}。</p>
     *
     * @param claimantId 认领人（用户）主键
     * @return 该认领人提交的认领单列表，按创建时间倒序
     */
    List<LostFoundClaim> findByClaimantIdOrderByCreatedAtDesc(Long claimantId);

    /**
     * 查询「某报告创建人（失主/物主）名下报告」收到的全部认领单，按创建时间倒序排列。
     *
     * <p>对应"我发布的报告被认领"列表：通过报告创建人（owner）间接定位到该用户名下的
     * 所有报告及其认领单，底层会执行一次报告 → 认领单的 JOIN。
     * 生成 SQL 语义：{@code WHERE report.createdBy.id = ? ORDER BY claim.createdAt DESC}。</p>
     *
     * @param ownerId 报告创建人（用户）主键
     * @return 该用户名下报告收到的认领单列表，按创建时间倒序
     */
    List<LostFoundClaim> findByReportCreatedByIdOrderByCreatedAtDesc(Long ownerId);

    /**
     * 查询某个报告下处于指定状态的认领单列表。
     *
     * <p>典型用途：认领审核页加载某一报告的全部已提交认领，
     * 或批准某条认领后检索同报告其他待处理认领以作后续处理。</p>
     *
     * @param reportId 报告主键
     * @param status   认领状态
     * @return 该报告下处于该状态的认领单列表
     */
    List<LostFoundClaim> findByReportIdAndStatus(Long reportId, ClaimStatus status);

    /**
     * 删除某个报告下的全部认领单。
     *
     * <p>在报告被（硬）删除时调用，用于级联清理该报告下沉淀的认领数据。
     * 生成 SQL 语义：{@code DELETE FROM lost_found_claims WHERE report_id = ?}。</p>
     *
     * @param reportId 报告主键
     */
    void deleteByReportId(Long reportId);
}
