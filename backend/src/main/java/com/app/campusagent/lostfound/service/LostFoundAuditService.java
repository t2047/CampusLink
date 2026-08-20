/**
 * 失物招领模块的"审计服务"（Audit Service）。
 *
 * <p>职责：将失物招领报告（report）上的关键写操作（如删除、恢复、分类/状态变更等）
 * 记录成一条审计日志（{@link com.app.campusagent.lostfound.domain.LostFoundAuditLog}），
 * 供管理员回溯"谁在何时对哪条报告做了什么、原因与详情"。</p>
 *
 * <p>调用方式：本服务被报告管理类业务服务在写操作的同时调用 record 方法。
 * 它本身不是定时任务，无 @Scheduled 配置，只在业务写路径上被动触发。</p>
 *
 * <p>事务特性：record 方法标注 @Transactional（默认采用 REQUIRED 传播，加入调用方事务），
 * 审计写入与业务写操作处于同一事务 —— 业务变更与审计日志要么一起提交、要么一起回滚，
 * 从根本上保证"有业务变更就必然有对应审计记录"的可追溯性。</p>
 */
package com.app.campusagent.lostfound.service;

// User：用户实体，审计日志中的"操作人"（actor），记录当前登录用户
import com.app.campusagent.domain.User;
// LostFoundAuditAction：审计动作枚举，枚举出可被审计的操作类型
import com.app.campusagent.lostfound.domain.LostFoundAuditAction;
// LostFoundAuditLog：审计日志实体，对应数据库 lost_found_audit_log 表
import com.app.campusagent.lostfound.domain.LostFoundAuditLog;
// LostFoundAuditLogRepository：审计日志仓库，负责把日志写入数据库
import com.app.campusagent.lostfound.repository.LostFoundAuditLogRepository;
// Service：Spring 组件注解，声明该类为业务服务 bean
import org.springframework.stereotype.Service;
// Transactional：声明方法/类的事务边界
import org.springframework.transaction.annotation.Transactional;

/** 报告级写操作审计：与业务写操作处于同一事务，保证可追溯性。 */
@Service
public class LostFoundAuditService {

    // 审计日志仓库：本服务的唯一依赖，审计日志的落库入口
    private final LostFoundAuditLogRepository auditLogRepository;

    /**
     * 构造器注入审计日志仓库（Spring 自动装配单例 bean）。
     *
     * @param auditLogRepository 审计日志仓库
     */
    public LostFoundAuditService(LostFoundAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 追加记录一条报告级审计日志。
     *
     * @param action   审计动作枚举，标识本次操作类型（如删除、恢复、状态变更等）
     * @param reportId 被操作的报告 ID
     * @param itemName 被操作物品名称的快照，便于检索与展示
     * @param actor    操作人（当前登录用户）
     * @param reason   操作原因（可选，管理端填写的备注说明）
     * @param detail   操作详情（可选，更细粒度的变更说明，如变更前后值）
     * @throws org.springframework.dao.DataAccessException 持久化失败时抛出，触发事务回滚
     * 事务特性：@Transactional，默认加入调用方所在事务，随调用方一起提交/回滚；
     * 若独立被调用则自行开启一个事务。
     */
    @Transactional
    public void record(
            LostFoundAuditAction action,
            Long reportId,
            String itemName,
            User actor,
            String reason,
            String detail) {
        // 组装审计日志实体并交给仓库保存（INSERT），参数与实体字段一一对应
        auditLogRepository.save(new LostFoundAuditLog(
                action,
                reportId,
                itemName,
                actor,
                reason,
                detail));
    }
}
