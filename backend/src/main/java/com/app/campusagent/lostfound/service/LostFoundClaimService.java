/**
 * 失物招领"认领"业务服务：处理用户对已捡到物品（FOUND 报告）发起认领的完整流程。
 *
 * <p><b>职责</b>：
 * <ul>
 *     <li>{@link #create} —— 用户对一条 FOUND 报告提交认领申请（含证明材料描述）；</li>
 *     <li>{@link #mine} / {@link #received} —— 分别查询"我发起的认领"与"我收到的认领"；</li>
 *     <li>{@link #approve} / {@link #reject} —— 认领审核（已收归管理员，普通端点一律 403）。</li>
 * </ul>
 *
 * <p><b>被谁调用</b>：{@code LostFoundClaimController}（Web 控制器）。
 *
 * <p><b>依赖</b>：{@code LostFoundClaimRepository} / {@code LostFoundReportRepository}
 * 两个仓库，以及 {@code LostFoundNotificationService}（认领提交时通知失主）。
 *
 * <p><b>关键约束</b>：只能认领他人发布的、公开（未被管理员隐藏）的、状态为 OPEN 的
 * FOUND 报告；同一用户对同一报告不能存在 SUBMITTED 或 APPROVED 状态的重复认领。</p>
 */
package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.ClaimDecisionRequest;
import com.app.campusagent.lostfound.dto.ClaimReportSummary;
import com.app.campusagent.lostfound.dto.CreateClaimRequest;
import com.app.campusagent.lostfound.dto.LostFoundClaimResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LostFoundClaimService {

    /** 认领记录仓库：负责认领的增删查与状态判断。 */
    private final LostFoundClaimRepository claimRepository;

    /** 报告仓库：负责定位被认领的报告并校验其状态。 */
    private final LostFoundReportRepository reportRepository;

    /** 通知服务：认领提交后通知报告发布者（失主）。 */
    private final LostFoundNotificationService notificationService;

    public LostFoundClaimService(
            LostFoundClaimRepository claimRepository,
            LostFoundReportRepository reportRepository,
            LostFoundNotificationService notificationService) {
        this.claimRepository = claimRepository;
        this.reportRepository = reportRepository;
        this.notificationService = notificationService;
    }

    /**
     * 用户对一条 FOUND 报告提交认领申请。
     *
     * <p>事务特性：{@code @Transactional}，认领落库与通知在同一个事务中；
     * 任一校验失败都会抛异常回滚。</p>
     *
     * @param reportId    目标报告 id
     * @param request     认领请求（含证明材料描述）
     * @param currentUser 当前登录用户（认领人）
     * @return 创建成功的认领响应
     * @throws LostFoundApiException 报告不存在(404) / 报告被隐藏或非 OPEN 或非 FOUND(409) /
     *                               认领自己的报告(409) / 已有重复认领(409) 时抛出
     */
    @Transactional
    public LostFoundClaimResponse create(
            Long reportId,
            CreateClaimRequest request,
            User currentUser) {
        // 先定位报告，不存在则抛 404
        LostFoundReport report = requireReport(reportId);
        // 管理员隐藏的报告不接受认领
        if (report.isAdminHidden()) {
            throw conflict("REPORT_HIDDEN", "This report is not open for claims");
        }
        // 只有"捡到物品"(FOUND) 的报告才能被认领；丢失物品(LOST) 无法认领
        if (report.getReportType() != ReportType.FOUND) {
            throw conflict("ONLY_FOUND_REPORTS_CAN_BE_CLAIMED", "Only found-item reports can be claimed");
        }
        // 只有处于 OPEN（开启）状态的报告才能被认领
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_OPEN", "This report is no longer open for claims");
        }
        // 禁止认领自己发布的报告，防止自导自演
        if (report.getCreatedBy().getId().equals(currentUser.getId())) {
            throw conflict("CANNOT_CLAIM_OWN_REPORT", "You cannot claim an item that you reported");
        }
        // 防重复：同一用户对同一报告不得存在 SUBMITTED 或 APPROVED 状态的认领
        boolean duplicate = claimRepository.existsByReportIdAndClaimantIdAndStatusIn(
                reportId,
                currentUser.getId(),
                List.of(ClaimStatus.SUBMITTED, ClaimStatus.APPROVED));
        if (duplicate) {
            throw conflict("CLAIM_ALREADY_EXISTS", "You already have an active claim for this item");
        }

        // 保存认领记录，证明材料描述去除首尾空白
        LostFoundClaim claim = claimRepository.save(new LostFoundClaim(
                report,
                currentUser,
                request.proofDescription().trim()));
        // 通知报告发布者（失主）有人提交了认领
        notificationService.claimSubmitted(claim);
        return toResponse(claim, currentUser);
    }

    /**
     * 查询当前用户发起的全部认领，按创建时间倒序。
     *
     * <p>事务特性：只读事务（{@code readOnly = true}），适合查询。</p>
     *
     * @param currentUser 当前用户
     * @return 认领响应列表（新→旧）
     */
    @Transactional(readOnly = true)
    public List<LostFoundClaimResponse> mine(User currentUser) {
        return claimRepository.findByClaimantIdOrderByCreatedAtDesc(currentUser.getId()).stream()
                .map(claim -> toResponse(claim, currentUser))
                .toList();
    }

    /**
     * 查询当前用户收到的认领（即当前用户发布的报告被他人认领的记录），按创建时间倒序。
     *
     * <p>事务特性：只读事务。</p>
     *
     * @param currentUser 当前用户（作为报告发布者）
     * @return 收到的认领响应列表（新→旧）
     */
    @Transactional(readOnly = true)
    public List<LostFoundClaimResponse> received(User currentUser) {
        return claimRepository.findByReportCreatedByIdOrderByCreatedAtDesc(currentUser.getId()).stream()
                .map(claim -> toResponse(claim, currentUser))
                .toList();
    }

    /**
     * 认领审核已收归管理员：普通用户侧端点保留但一律返回 403，
     * 实际审核逻辑见 {@link LostFoundAdminService}。
     *
     * <p>该方法永远抛 403，不会执行任何审核操作，仅保留端点以兼容前端路由。</p>
     */
    @Transactional
    public LostFoundClaimResponse approve(
            Long claimId,
            ClaimDecisionRequest request,
            User currentUser) {
        throw adminOnly();
    }

    /**
     * 认领驳回同样已收归管理员，普通用户侧端点一律返回 403。
     * 实际审核逻辑见 {@link LostFoundAdminService}。
     */
    @Transactional
    public LostFoundClaimResponse reject(
            Long claimId,
            ClaimDecisionRequest request,
            User currentUser) {
        throw adminOnly();
    }

    /**
     * 按 id 查询报告，不存在时抛出带 LOST_FOUND_REPORT_NOT_FOUND 错误码的 404。
     *
     * @param reportId 报告 id
     * @return 找到的报告实体
     */
    private LostFoundReport requireReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist"));
    }

    /**
     * 构造"仅管理员可审核"的 403 异常（CLAIM_REVIEW_ADMIN_ONLY）。
     */
    private LostFoundApiException adminOnly() {
        return new LostFoundApiException(
                HttpStatus.FORBIDDEN,
                "CLAIM_REVIEW_ADMIN_ONLY",
                "Only administrators can review claims");
    }

    /**
     * 把认领实体转换为对外响应 DTO。
     *
     * @param claim       认领实体
     * @param currentUser 当前用户，用于判断认领人是否就是当前用户（isOwn）
     * @return 含报告摘要、证明材料、状态、决策备注、时间等字段的响应对象
     */
    private LostFoundClaimResponse toResponse(LostFoundClaim claim, User currentUser) {
        LostFoundReport report = claim.getReport();
        return new LostFoundClaimResponse(
                claim.getId(),
                // 内嵌报告摘要：id、物品名、类别、地点、状态
                new ClaimReportSummary(
                        report.getId(),
                        report.getItemName(),
                        report.getCategory(),
                        report.getLocation(),
                        report.getStatus()),
                claim.getProofDescription(),
                claim.getStatus(),
                claim.getDecisionNote(),
                // 判断当前用户是否为认领人，供前端区分"我的/收到的"
                claim.getClaimant().getId().equals(currentUser.getId()),
                claim.getCreatedAt(),
                claim.getUpdatedAt());
    }

    /** 构造 409 冲突异常（携带指定错误码与消息）。 */
    private LostFoundApiException conflict(String code, String message) {
        return new LostFoundApiException(HttpStatus.CONFLICT, code, message);
    }
}
