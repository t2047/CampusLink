/**
 * 失物招领（Lost & Found）模块的「报告」仓储接口。
 *
 * <p>负责 {@link LostFoundReport}（失物招领核心实体：寻物/招领报告）的持久化访问，
 * 是本模块最核心的仓储。该接口继承两个 Spring Data JPA 接口：</p>
 * <ul>
 *   <li>{@link JpaRepository}：基于 {@code Long} 主键的标准 CRUD；</li>
 *   <li>{@link JpaSpecificationExecutor}：基于 {@link Specification} 的动态组合查询，
 *       支撑公开搜索、管理端筛选等多维条件过滤。</li>
 * </ul>
 */
package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 报告仓储。
 *
 * <p>除统计类方法与文本向量回填查询外，分页查询 {@code findAll(Specification, Pageable)}
 * 被覆写并配合 {@link EntityGraph} 一次抓取创建人 {@code createdBy}，
 * 避免逐条懒加载关联造成的 N+1 查询。</p>
 */
public interface LostFoundReportRepository extends
        JpaRepository<LostFoundReport, Long>,
        JpaSpecificationExecutor<LostFoundReport> {

    /**
     * 统计处于指定状态的报告数量。
     *
     * <p>生成 SQL 语义：{@code SELECT COUNT(*) FROM lost_found_reports WHERE status = ?}。</p>
     *
     * @param status 报告状态（OPEN / CLAIMED / CLOSED，见 {@link ReportStatus}）
     * @return 处于该状态的报告总数
     */
    long countByStatus(ReportStatus status);

    /**
     * 统计指定报告类型（寻物 / 招领）的报告数量。
     *
     * <p>生成 SQL 语义：{@code SELECT COUNT(*) FROM lost_found_reports WHERE report_type = ?}。</p>
     *
     * @param reportType 报告类型（见 {@link ReportType}）
     * @return 该类型的报告总数
     */
    long countByReportType(ReportType reportType);

    /**
     * 统计被管理员下架（{@code adminHidden = true}）的报告数量。
     *
     * <p>下架记录不进入公开搜索、候选匹配和非 owner/非管理员详情。
     * 生成 SQL 语义：{@code SELECT COUNT(*) FROM lost_found_reports WHERE admin_hidden = TRUE}。</p>
     *
     * @return 下架报告总数
     */
    long countByAdminHiddenTrue();

    /**
     * 分页查询「需要计算文本向量」的报告。
     *
     * <p>报告在创建/编辑后由后台任务异步生成语义向量与跨模态文本向量。下列任一情况
     * 都说明报告需要（重新）计算语义向量：</p>
     * <ul>
     *   <li>{@code semanticTextEmbedding} 为 {@code null}——从未计算；</li>
     *   <li>{@code semanticTextRevision} 为 {@code null}——旧数据未记录模型版本；</li>
     *   <li>{@code semanticTextRevision} 不等于目标 {@code revision}——模型升级，旧向量作废。</li>
     * </ul>
     * <p>当 {@code requireCrossModal = true} 时，还会额外要求跨模态文本向量也缺失或过期
     * （同样按版本号比较），以同时触发跨模态向量的计算。</p>
     *
     * <p>JPQL 逐行语义（文本块内部不得插入注释，故在此说明）：</p>
     * <ol>
     *   <li>{@code from LostFoundReport r}——遍历全部报告；</li>
     *   <li>前三个 {@code or} 分支判定语义向量缺失或版本过期，命中其一即入选；</li>
     *   <li>当 {@code requireCrossModal} 为真时，追加跨模态向量缺失/过期的判定；</li>
     *   <li>{@code order by r.id}——按 id 升序，保证分批处理时结果稳定、不重不漏。</li>
     * </ol>
     *
     * @param revision           当前语义向量模型版本号
     * @param crossModalRevision 当前跨模态向量模型版本号
     * @param requireCrossModal  是否同时要求计算跨模态向量
     * @param pageable           分页参数
     * @return 需要计算文本向量的报告分页结果
     */
    @Query("""
            select r from LostFoundReport r
            where r.semanticTextEmbedding is null
               or r.semanticTextRevision is null
               or r.semanticTextRevision <> :revision
               or (:requireCrossModal = true and (
                    r.crossModalTextRevision is null
                    or r.crossModalTextRevision <> :crossModalRevision))
            order by r.id
            """)
    Page<LostFoundReport> findNeedingTextEmbedding(
            @Param("revision") String revision,
            @Param("crossModalRevision") String crossModalRevision,
            @Param("requireCrossModal") boolean requireCrossModal,
            Pageable pageable);

    /**
     * 分页查询报告，并同时抓取创建人 {@code createdBy}。
     *
     * <p>报告通过 {@code @ManyToOne} 懒加载关联创建人 {@code createdBy}。若分页后逐条
     * 访问该关联，每条报告会额外触发一次关联查询（N+1）。因此覆写本方法并用
     * {@code @EntityGraph(attributePaths = "createdBy")} 在同一条 SQL 中 JOIN 抓取
     * 创建人信息，消除 N+1 开销。</p>
     *
     * @param specification 动态过滤条件（公开搜索 / 管理端筛选等，可为空）
     * @param pageable      分页与排序参数
     * @return 报告分页结果，其中 createdBy 关联已一并加载
     */
    @Override
    @EntityGraph(attributePaths = "createdBy")
    Page<LostFoundReport> findAll(Specification<LostFoundReport> specification, Pageable pageable);
}
