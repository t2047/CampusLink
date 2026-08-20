/**
 * 失物招领（Lost & Found）模块的「审计日志」仓储接口。
 *
 * <p>负责 {@link LostFoundAuditLog}（报告级写操作的审计日志）实体的持久化访问。
 * 该接口继承两个 Spring Data JPA 接口，从而获得两套能力：</p>
 * <ul>
 *   <li>{@link JpaRepository}：基于 {@code Long} 主键的标准 CRUD、分页与排序；</li>
 *   <li>{@link JpaSpecificationExecutor}：基于 {@link Specification} 的动态组合查询，
 *       允许在运行时按操作人、动作类型、时间范围等条件拼接过滤条件。</li>
 * </ul>
 */
package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

/**
 * 审计日志仓储。
 *
 * <p>审计日志实体通过 {@code @ManyToOne} 懒加载关联了操作人 {@code actor}。
 * 若按默认方式分页查询 {@link LostFoundAuditLog} 后再逐条访问 {@code actor}，
 * 每一行记录都会触发一次额外的关联查询，产生 N+1 查询问题。</p>
 *
 * <p>因此这里覆写了 {@code findAll(Specification, Pageable)}，并配合
 * {@link EntityGraph}（属性路径为 {@code actor}）让 JPA 在分页查询的同一条 SQL 中
 * 通过 JOIN 一次性取出操作人信息，从而消除 N+1 开销。</p>
 */
public interface LostFoundAuditLogRepository extends
        JpaRepository<LostFoundAuditLog, Long>,
        JpaSpecificationExecutor<LostFoundAuditLog> {

    /**
     * 分页查询审计日志，并同时抓取操作人 {@code actor}。
     *
     * @param specification 动态过滤条件（可为空，表示不过滤）
     * @param pageable      分页与排序参数
     * @return 审计日志分页结果，其中每条记录的 actor 关联已一并加载，避免逐条懒加载查询
     */
    @Override
    @EntityGraph(attributePaths = "actor")
    Page<LostFoundAuditLog> findAll(Specification<LostFoundAuditLog> specification, Pageable pageable);
}
