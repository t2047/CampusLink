/**
 * 失物招领（Lost &amp; Found）模块的核心用户侧业务服务。
 *
 * <p>本类承载失物招领用户侧几乎全部核心业务能力：</p>
 * <ul>
 *   <li>报告创建——普通 Web 创建 {@link #create} 与 Agent 暂存图确认创建 {@link #createFromStaged}；</li>
 *   <li>公开搜索 {@link #search}——基于 JPA {@code Specification} 动态拼装查询条件，
 *       支持报告类型/关键词/分类/颜色/地点/日期范围/状态/所有者多条件过滤与分页排序；</li>
 *   <li>报告详情 {@link #getById}、编辑 {@link #update}、关闭 {@link #close}、删除 {@link #delete}
 *       以及供管理员调用的 {@link #deleteAsAdmin}；</li>
 *   <li>为 Agent 匹配提供的候选搜索 {@link #searchCandidates}——除基础字段外还携带文本/视觉
 *       预训练向量（Base64 编码）与视觉指纹，供 Agent 端做相似度匹配。</li>
 * </ul>
 *
 * <p>被 {@code LostFoundReportController}（/api/lost-found/reports）以及 Agent 内部接口
 * （{@code LostFoundAgentInternalController} / Agent 网关）调用；管理端删除报告时通过
 * {@code LostFoundAdminService} 间接调用 {@link #deleteAsAdmin}。</p>
 *
 * <p>依赖的 Repository / Service / 外部系统：</p>
 * <ul>
 *   <li>{@code LostFoundReportRepository} — 报告实体增删改查与规格分页查询；</li>
 *   <li>{@code ObjectStorageService}（MinIO）— 报告图片的持久化存储；</li>
 *   <li>{@code LostFoundClaimRepository} / {@code LostFoundNotificationRepository} — 报告删除时的级联清理；</li>
 *   <li>{@code LostFoundAuditService} — 写操作的审计留痕（同一事务）；</li>
 *   <li>{@code LostFoundImageStagingService} — Agent 图片暂存与消费；</li>
 *   <li>{@code LostFoundEmbeddingClient} — 文本/图片预训练向量生成客户端，故障时降级为基础匹配。</li>
 * </ul>
 */
package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundAuditAction;
import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.LostFoundImageResponse;
import com.app.campusagent.lostfound.dto.LostFoundReportResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.UpdateLostFoundReportRequest;
import com.app.campusagent.lostfound.colour.ColourNormalizer;
import com.app.campusagent.lostfound.dto.agent.AgentCandidateResponse;
import com.app.campusagent.lostfound.embedding.LostFoundEmbeddingClient;
import com.app.campusagent.lostfound.embedding.StoredEmbedding;
import com.app.campusagent.lostfound.embedding.TextEmbeddingBundle;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundNotificationRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.lostfound.storage.StoredObject;
import com.app.campusagent.lostfound.visual.VisualFingerprintExtractor;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Base64;
import java.util.Optional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用户侧报告核心服务，职责详见文件头注释。
 *
 * <p>本类为无状态 Bean：所有状态都通过方法入参显式传入，当前用户 {@code currentUser}
 * 由 Spring Security 从认证信息解析后注入。所有写库方法均声明 {@code @Transactional}
 * 以保证业务写操作与审计、图片元数据落在同一事务；查询方法声明只读事务
 * {@code @Transactional(readOnly = true)} 以降低数据库负担并允许只读优化。</p>
 */
@Service
public class LostFoundReportService {

    /** 报告实体 Repository：报告的增删改查与基于 Specification 的分页规格查询。 */
    private final LostFoundReportRepository reportRepository;
    /** MinIO 对象存储服务：负责报告图片的上传、删除、下载。 */
    private final ObjectStorageService storageService;
    /** 认领 Repository：报告硬删除时级联删除其全部认领记录。 */
    private final LostFoundClaimRepository claimRepository;
    /** 通知 Repository：报告硬删除时级联删除关联的通知记录。 */
    private final LostFoundNotificationRepository notificationRepository;
    /** 审计服务：报告级写操作（创建/编辑/关闭/删除/下架等）写入审计日志。 */
    private final LostFoundAuditService auditService;
    /** Agent 图片暂存服务：暂存图的检索与消费（Agent 确认创建流程使用）。 */
    private final LostFoundImageStagingService stagingService;
    /** 预训练向量客户端：生成文本/图片 embedding；为 null 或服务故障时降级为基础匹配。 */
    private final LostFoundEmbeddingClient embeddingClient;

    /**
     * Spring 自动装配的主构造器。
     *
     * <p>把 7 个依赖注入为 final 字段，后续所有方法均通过字段访问这些组件。
     * 当预训练模型服务在配置中不可用时，Spring 仍会注入一个正常客户端，
     * 由客户端内部的 {@code enabled()} 判断决定是否降级。</p>
     */
    @Autowired
    public LostFoundReportService(
            LostFoundReportRepository reportRepository,
            ObjectStorageService storageService,
            LostFoundClaimRepository claimRepository,
            LostFoundNotificationRepository notificationRepository,
            LostFoundAuditService auditService,
            LostFoundImageStagingService stagingService,
            LostFoundEmbeddingClient embeddingClient) {
        this.reportRepository = reportRepository;
        this.storageService = storageService;
        this.claimRepository = claimRepository;
        this.notificationRepository = notificationRepository;
        this.auditService = auditService;
        this.stagingService = stagingService;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 简化构造器，保留给不需要启动预训练模型的单元测试。
     *
     * <p>与主构造器相比仅缺少 {@link LostFoundEmbeddingClient}，内部以 null 转调主构造器。
     * 当 {@link #embeddingClient} 为 null 时，向量相关调用（{@link #applyTextEmbeddings}、
     * {@link #embedMultipartImages}）会自动降级，不产生对预训练服务的外部网络请求。</p>
     */
    public LostFoundReportService(
            LostFoundReportRepository reportRepository,
            ObjectStorageService storageService,
            LostFoundClaimRepository claimRepository,
            LostFoundNotificationRepository notificationRepository,
            LostFoundAuditService auditService,
            LostFoundImageStagingService stagingService) {
        this(reportRepository, storageService, claimRepository, notificationRepository,
                auditService, stagingService, null);
    }

    /**
     * 普通 Web 创建失物招领报告。
     *
     * @param request     创建请求（reportType、itemName、category、description、colour、
     *                    location、eventDate、timeDescription），字段在 DTO 上已做校验；
     * @param images      附件图片（multipart 列表），可为 null，最多 5 张；
     * @param currentUser 当前登录用户，将作为报告的发布者（createdBy）。
     * @return 创建成功后的报告响应 DTO（含按 sortOrder 排好序的图片列表与 createdByMe 等字段）。
     * @throws LostFoundApiException 图片非法（类型/大小/数量/尺寸）时抛出 422/415 等异常；
     *         图片对象存储写入失败时抛出 503。
     * @implNote 整个过程在一个事务中完成：校验图片 → 构造报告实体并生成文本向量 → 逐张上传图片
     *           到 MinIO 并建立 {@link LostFoundImage} 行、计算视觉指纹与向量 → 落库并审计。
     *           事务提交前通过 {@link #registerRollbackCleanup} 注册同步回调，任何一步失败时
     *           把已上传到 MinIO 的对象删除，保证"无图无记录"的一致状态。
     */
    @Transactional
    public LostFoundReportResponse create(
            CreateLostFoundReportRequest request,
            List<MultipartFile> images,
            User currentUser) {
        // 图片参数允许缺省：为 null 时归一为空列表，避免后续 NPE 与校验逻辑重复判断
        List<MultipartFile> safeImages = images == null ? List.of() : images;
        // 先做整体校验（数量 ≤5），每张图的类型/大小/尺寸/魔数校验在 validateSingle 中完成
        validateImages(safeImages);

        // 用请求字段构造报告实体（内部字段已 trim 清洗：物品名、描述、地点去首尾空白；
        // 颜色与时间描述可为空，用 trimToNull 把空白字符串归一为 null）
        LostFoundReport report = new LostFoundReport(
                request.reportType(),
                request.itemName().trim(),
                request.category(),
                request.description().trim(),
                trimToNull(request.colour()),
                request.location().trim(),
                request.eventDate(),
                trimToNull(request.timeDescription()),
                currentUser);
        // 生成文本向量（itemName+description）：预训练服务可用则写入语义/跨模态向量，
        // 否则标记 PENDING，由后台回填任务后续补齐
        applyTextEmbeddings(report);

        // 批量预训练图片向量：一次性把全部图片字节发给向量服务，返回与入参同序的结果列表。
        // 服务不可用/图片为空时返回空列表，后续 assignImageEmbedding 会跳过赋值
        List<StoredEmbedding> pretrainedImages = embedMultipartImages(safeImages);

        // 记录本事务内已上传到 MinIO 的对象，用于失败时的回滚清理
        List<StoredObject> uploaded = new ArrayList<>();
        // 注册事务同步回调：若事务最终未提交，afterCompletion 会删除已上传的全部对象
        registerRollbackCleanup(uploaded);
        try {
            // 逐张图片：按入参顺序作为 sortOrder，先算指纹再上传到 MinIO，最后建立图片实体行
            for (int index = 0; index < safeImages.size(); index++) {
                MultipartFile image = safeImages.get(index);
                // 视觉指纹：8x8 网格颜色直方图（VF1: 前缀），供 Agent 做"以图搜物"
                String fingerprint = visualFingerprint(image);
                StoredObject stored = storageService.upload(image);
                uploaded.add(stored);
                report.addImage(new LostFoundImage(
                        stored.objectKey(),
                        safeOriginalName(stored.originalName()),
                        stored.contentType(),
                        stored.size(),
                        index,
                        fingerprint));
                // 若预训练向量可用，把第 index 张图片的向量回填到刚建立的图片行上
                assignImageEmbedding(report.getImages().getLast(), pretrainedImages, index);
            }
            // 汇总文本+全部图片的向量就绪情况，刷新报告的 embeddingStatus（READY/PARTIAL/PENDING）
            report.refreshEmbeddingStatus();
            // saveAndFlush：立即落库并 flush，使报告拿到自增 id，便于审计与返回响应
            LostFoundReport saved = reportRepository.saveAndFlush(report);
            // 与业务同一事务记录审计：REPORT_CREATED，detail 记录图片数量
            auditService.record(
                    LostFoundAuditAction.REPORT_CREATED,
                    saved.getId(),
                    saved.getItemName(),
                    currentUser,
                    null,
                    "images=" + safeImages.size());
            return toResponse(saved, currentUser);
        } catch (RuntimeException ex) {
            // 主动清理本方法内已上传的对象（同步回调和这里的双保险），再向上抛出异常回滚事务
            uploaded.forEach(stored -> storageService.delete(stored.objectKey()));
            uploaded.clear();
            throw ex;
        }
    }

    /**
     * Agent 确认创建：把已暂存的 objectKey 关联为新报告的图片。
     *
     * <p>暂存图已存在于 MinIO（{@code lost-found-staging/} 前缀），此处只下载字节
     * 计算指纹并建立 {@link LostFoundImage} 行，objectKey 复用暂存键。行创建后该键
     * 被 DB 引用，TTL 清理任务会自动跳过；若任一暂存对象缺失（如已被 TTL 清理），
     * 整个创建回滚，不产生"有记录无图"或"有图无记录"的半态。</p>
     *
     * @param request         创建请求（同普通创建）；
     * @param stagedImageKeys Agent 会话中暂存的图片 objectKey 列表，数量 ≤5；
     * @param currentUser     当前登录用户，暂存图的拥有者，同时也是报告发布者。
     * @return 创建成功后的报告响应 DTO。
     * @throws LostFoundApiException 任一暂存键不存在、不属于当前用户或已过期时抛出
     *         NOT_FOUND（{@code STAGED_IMAGE_NOT_FOUND}），使整个创建回滚。
     */
    @Transactional
    public LostFoundReportResponse createFromStaged(
            CreateLostFoundReportRequest request,
            List<String> stagedImageKeys,
            User currentUser) {
        // null 归一为空列表，随后 validateCount(0) 通过（允许创建无图报告）
        List<String> keys = stagedImageKeys == null ? List.of() : stagedImageKeys;
        // 暂存图数量同样受 5 张上限约束，与普通创建保持一致
        LostFoundImageRules.validateCount(keys.size());

        // 与普通创建相同的字段清洗构造报告实体
        LostFoundReport report = new LostFoundReport(
                request.reportType(),
                request.itemName().trim(),
                request.category(),
                request.description().trim(),
                trimToNull(request.colour()),
                request.location().trim(),
                request.eventDate(),
                trimToNull(request.timeDescription()),
                currentUser);

        // 逐键重建图片行：检索当前用户拥有且未过期的暂存对象（含 MinIO 元数据与暂存时算好的向量）
        for (int index = 0; index < keys.size(); index++) {
            LostFoundImageStagingService.StagedImage staged =
                    stagingService.retrieveOwned(keys.get(index), currentUser);
            // 暂存时只在上传接口算了指纹，此处用下载到的字节重新计算一次并落库
            String fingerprint = VisualFingerprintExtractor.extract(
                    staged.content(), staged.contentType());
            // objectKey 复用暂存键，而不是重新上传，避免重复存储
            report.addImage(new LostFoundImage(
                    staged.objectKey(),
                    safeOriginalName(staged.originalName()),
                    staged.contentType(),
                    staged.fileSize(),
                    index,
                    fingerprint));
            // 暂存阶段若已生成视觉向量（存于 lost_found_staged_images 表），一并回填到图片行
            if (staged.visualEmbedding() != null) {
                report.getImages().getLast().assignVisualEmbedding(
                        staged.visualEmbedding(), staged.visualEmbeddingModel(),
                        staged.visualEmbeddingRevision());
            }
        }
        // 生成文本向量并刷新 embedding 汇总状态（同普通创建）
        applyTextEmbeddings(report);
        report.refreshEmbeddingStatus();
        LostFoundReport saved = reportRepository.saveAndFlush(report);
        // 事务提交成功后才"消费"暂存元数据（仅删暂存记录，MinIO 对象已被 DB 行引用保留）
        consumeStagedImagesAfterCommit(keys);
        // 与业务同一事务记录审计，detail 标注 staged=true 以区分创建来源
        auditService.record(
                LostFoundAuditAction.REPORT_CREATED,
                saved.getId(),
                saved.getItemName(),
                currentUser,
                null,
                "images=" + keys.size() + ", staged=true");
        return toResponse(saved, currentUser);
    }

    /**
     * 公开报告搜索（用户中心个人需求 §9.2 / §11.1）。
     *
     * <p>所有过滤条件均可选，组合使用；除了按业务字段过滤外还区分两种可见性模式：
     * 公开搜索（owner 缺省）只返回未被管理员下架的报告；owner=me 的个人中心搜索则返回
     * 当前用户发布的全部报告（含被下架的，因为"我的报告"要能看到自己发过的所有内容）。</p>
     *
     * @param reportType  报告类型（LOST/FOUND），可空；
     * @param keyword     物品名/描述模糊关键词，可空；
     * @param category    物品分类，可空；
     * @param colour      颜色过滤（会经 {@link ColourNormalizer} 扩展为同义表面形式 OR 条件），可空；
     * @param location    地点模糊匹配，可空；
     * @param dateFrom    事件日期下界（含），可空；
     * @param dateTo      事件日期上界（含），可空；
     * @param status      报告状态，可空；
     * @param owner       所有者过滤：null/空白表示公开搜索；"me" 表示只看自己的报告；
     * @param pageable    分页与排序参数（页号/每页条数/排序字段与方向）；
     * @param currentUser 当前登录用户，用于 owner=me 过滤与响应里的 createdByMe 标记。
     * @return 分页结果包装，content 为按 sortOrder 排好序、标注归属的报告 DTO 列表。
     * @throws LostFoundApiException owner 非 null/me 时抛 422 INVALID_OWNER_FILTER；
     *         日期范围非法时抛 422 INVALID_DATE_RANGE。
     */
    @Transactional(readOnly = true)
    public PageResponse<LostFoundReportResponse> search(
            ReportType reportType,
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            ReportStatus status,
            String owner,
            Pageable pageable,
            User currentUser) {
        // 解析 owner 参数：决定走"只看我的"（mine=true）还是公开搜索（mine=false）
        boolean mine = resolveOwnerFilter(owner);
        // 动态拼装 Specification 查询条件（见重载方法：mine 模式豁免 adminHidden 过滤）
        Specification<LostFoundReport> specification = specification(
                reportType, keyword, category, colour, location, dateFrom, dateTo, status,
                mine, currentUser);

        // 规格查询 + 分页排序，再把实体逐条映射为对外 DTO（createdByMe 依赖 currentUser）
        Page<LostFoundReportResponse> result = reportRepository.findAll(specification, pageable)
                .map(report -> toResponse(report, currentUser));
        return PageResponse.from(result);
    }

    /**
     * 解析 owner 查询参数：仅接受 null 或缺省为公开搜索、{@code me} 表示只看自己的报告。
     * 非法值返回 422（个人中心需求 §9.2 / §11.1）。
     *
     * @param owner URL 查询参数 owner 的值。
     * @return true 表示进入"我的报告"模式；false 表示公开搜索。
     * @throws LostFoundApiException 提供了非 "me" 的取值时抛 422 INVALID_OWNER_FILTER，
     *         避免前端传任意字段值造成越权查询的语义歧义。
     */
    private boolean resolveOwnerFilter(String owner) {
        // owner 未提供或为空白：公开搜索模式
        if (owner == null || owner.isBlank()) {
            return false;
        }
        // 只接受字面量 "me"，其余一律拒绝，防止拼入非预期的过滤语义
        if (!"me".equals(owner)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_OWNER_FILTER",
                    "owner must be 'me' when provided");
        }
        return true;
    }

    /**
     * Agent 匹配候选搜索（仅供 Agent 内部接口调用，不对外暴露）。
     *
     * <p>与公开搜索共享同一套条件构造逻辑，但额外做了两件事：只查 {@link ReportStatus#OPEN}
     * 的报告（候选必须可被认领），并把报告与图片的预训练向量（Base64）与视觉指纹一并返回，
     * 供 Agent 端做语义/视觉相似度排序。可见性固定为公开搜索语义（adminHidden=false）。</p>
     *
     * @param pageable 分页排序参数。
     * @return 分页的 {@link AgentCandidateResponse} 列表；向量字段在未生成时为 null，
     *         Agent 端据此跳过对应维度的匹配。
     */
    @Transactional(readOnly = true)
    public PageResponse<AgentCandidateResponse> searchCandidates(
            ReportType reportType,
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            Pageable pageable) {
        // 复用公开搜索的条件构造（8 参数重载），但固定 status=OPEN：候选必须是仍可认领的报告
        Specification<LostFoundReport> specification = specification(
            reportType,
            keyword,
            category,
            colour,
            location,
            dateFrom,
            dateTo,
            ReportStatus.OPEN);

        return PageResponse.from(reportRepository.findAll(specification, pageable)
                .map(report -> {
                    // 图片按 sortOrder 升序排列，保证 imageUrls/指纹/向量的顺序与图片列表一致
                    List<LostFoundImage> images = report.getImages().stream()
                            .sorted(Comparator.comparingInt(LostFoundImage::getSortOrder))
                            .toList();
                    return new AgentCandidateResponse(
                            report.getId(),
                            report.getReportType(),
                            report.getItemName(),
                            report.getCategory(),
                            report.getDescription(),
                            report.getColour(),
                            report.getLocation(),
                            report.getEventDate(),
                            report.getTimeDescription(),
                            report.getStatus(),
                            // 每张图对外访问 URL（同源代理端点），顺序与 images 一致
                            images.stream()
                                    .map(image -> LostFoundImageResponse.of(image).url())
                                    .toList(),
                            // 与 imageUrls 同序：无指纹的图片位置为 null，Agent 端跳过
                            images.stream()
                                    .map(LostFoundImage::getVisualFingerprint)
                                    .toList(),
                            // 文本语义/跨模态向量：DB 存原始 float32，这里编码为 Base64 传给 Agent
                            encode(report.getSemanticTextEmbedding()),
                            encode(report.getCrossModalTextEmbedding()),
                            // 每张图的视觉向量（Base64），与图片顺序一一对应
                            images.stream()
                                    .map(image -> encode(image.getVisualEmbedding()))
                                    .toList(),
                            // 报告级向量就绪状态，Agent 端据此判断哪些维度可信
                            report.getEmbeddingStatus().name());
                }));
}

    /**
     * 报告详情查询。
     *
     * <p>对被管理员下架的报告做可见性拦截：只有发布者本人或管理员/超管能查看，
     * 其余用户一律返回 NOT_FOUND（而非 403），避免泄露"该 id 确实存在但被下架"的信息。</p>
     *
     * @param reportId    报告 id；
     * @param currentUser 当前登录用户。
     * @return 报告详情 DTO。
     * @throws LostFoundApiException 报告不存在或无权查看时抛 404 LOST_FOUND_REPORT_NOT_FOUND。
     */
    @Transactional(readOnly = true)
    public LostFoundReportResponse getById(Long reportId, User currentUser) {
        // 先取报告实体，不存在直接 404
        LostFoundReport report = requireReport(reportId);
        // 命中下架标记：进一步按身份判断是否放行
        if (report.isAdminHidden()) {
            // 发布者本人可查看自己的报告（"我的报告"始终可见）
            boolean owner = report.getCreatedBy().getId().equals(currentUser.getId());
            // 管理员/超管可查看任意报告（管理端排障需要）
            boolean admin = currentUser.getRole() == Role.ADMIN
                    || currentUser.getRole() == Role.SUPER_ADMIN;
            if (!owner && !admin) {
                // 统一用 404 掩盖"下架"事实，避免暴露报告存在性
                throw new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist");
            }
        }
        return toResponse(report, currentUser);
    }

    /**
     * 按 id 加载报告实体，不存在时抛 404。
     *
     * @param reportId 报告 id。
     * @return 已加载的报告实体（处于持久化上下文中，可由调用方继续修改）。
     * @throws LostFoundApiException 报告不存在时抛 404 LOST_FOUND_REPORT_NOT_FOUND。
     */
    @Transactional(readOnly = true)
    public LostFoundReport requireReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist"));
    }

    /**
     * 编辑报告（发布者本人，且报告必须仍为 OPEN）。
     *
     * <p>可选地整体替换图片（传了非空 images 才替换）；文本字段始终更新，更新后重新生成
     * 文本向量并刷新 embedding 汇总状态。图片替换为"先上传新图、删除旧图"的流程，
     * 任一步失败会连同事务一起回滚。</p>
     *
     * @param reportId    待编辑的报告 id；
     * @param request     新的报告字段（DTO 已校验）；
     * @param images      可选的新图片列表；null/空列表表示保留原图；
     * @param currentUser 当前登录用户，必须是报告发布者。
     * @return 编辑后的报告 DTO。
     * @throws LostFoundApiException 非发布者抛 403 REPORT_EDIT_FORBIDDEN；
     *         非 OPEN 状态抛 409 REPORT_NOT_EDITABLE；报告不存在抛 404。
     */
    @Transactional
    public LostFoundReportResponse update(
            Long reportId,
            UpdateLostFoundReportRequest request,
            List<MultipartFile> images,
            User currentUser) {
        LostFoundReport report = requireReport(reportId);
        // 越权校验：只允许发布者本人编辑
        assertOwner(report, currentUser, "REPORT_EDIT_FORBIDDEN",
                "Only the report creator can edit this report");
        // 状态机约束：CLOSED/CLAIMED 的报告不再允许编辑
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_EDITABLE", "Only open reports can be edited");
        }

        // 只有显式提供了非空图片列表才触发替换；否则沿用原有图片
        boolean imagesReplaced = images != null && !images.isEmpty();
        if (imagesReplaced) {
            replaceImages(report, images);
        }
        // 更新文本字段（实体内部会把 embedding 状态重置为 PENDING，等待下面重新生成）
        report.updateDetails(
                request.itemName().trim(),
                request.category(),
                request.description().trim(),
                trimToNull(request.colour()),
                request.location().trim(),
                request.eventDate(),
                trimToNull(request.timeDescription()));
        // 基于新文本重新生成向量；随后刷新汇总状态
        applyTextEmbeddings(report);
        report.refreshEmbeddingStatus();
        // 保存（不强制 flush，交由事务提交时统一刷库）
        LostFoundReport saved = reportRepository.save(report);
        // 审计记录本次编辑，标注是否发生了图片替换
        auditService.record(
                LostFoundAuditAction.REPORT_UPDATED,
                reportId,
                saved.getItemName(),
                currentUser,
                null,
                "imagesReplaced=" + imagesReplaced);
        return toResponse(saved, currentUser);
    }

    /**
     * 发布者主动关闭报告（不再寻找失物/失主）。仅 OPEN 状态可关闭。
     *
     * @param reportId    报告 id；
     * @param currentUser 当前登录用户，必须是发布者。
     * @return 关闭后的报告 DTO（status=CLOSED）。
     * @throws LostFoundApiException 非发布者抛 403 REPORT_CLOSE_FORBIDDEN；
     *         已关闭/已认领抛 409 REPORT_NOT_OPEN。
     */
    @Transactional
    public LostFoundReportResponse close(Long reportId, User currentUser) {
        LostFoundReport report = requireReport(reportId);
        // 越权校验：仅发布者本人可关闭
        assertOwner(report, currentUser, "REPORT_CLOSE_FORBIDDEN",
                "Only the report creator can close this report");
        // 状态机约束：只有 OPEN 能转 CLOSED
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_OPEN", "This report is no longer open");
        }
        // 实体标记关闭，保存并审计
        report.markClosed();
        LostFoundReport saved = reportRepository.save(report);
        auditService.record(
                LostFoundAuditAction.REPORT_CLOSED,
                reportId,
                saved.getItemName(),
                currentUser,
                null,
                "status=OPEN→CLOSED");
        return toResponse(saved, currentUser);
    }

    /**
     * 发布者删除自己的报告（硬删除）。仅 OPEN 状态可删除。
     *
     * <p>删除是级联操作：同时清理认领、通知与 MinIO 上的图片对象；审计日志单独写入且
     * 不随报告删除（reportId 为无外键普通列，历史可追溯）。</p>
     *
     * @param reportId    报告 id；
     * @param currentUser 当前登录用户，必须是发布者。
     * @throws LostFoundApiException 非发布者抛 403 REPORT_DELETE_FORBIDDEN；
     *         非 OPEN 状态抛 409 REPORT_NOT_DELETABLE。
     */
    @Transactional
    public void delete(Long reportId, User currentUser) {
        LostFoundReport report = requireReport(reportId);
        // 越权校验：仅发布者本人可删除
        assertOwner(report, currentUser, "REPORT_DELETE_FORBIDDEN",
                "Only the report creator can delete this report");
        // 状态机约束：已认领/已关闭的报告不允许发布者删除（避免破坏认领凭据）
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_DELETABLE", "Only open reports can be deleted");
        }
        // 删除前快照物品名与图片数量，供审计 detail 使用（实体随后被删除）
        String itemName = report.getItemName();
        int imageCount = report.imageObjectKeys().size();
        // 级联删除报告、认领、通知与 MinIO 对象
        deleteReportAndCleanup(report);
        // 审计行在报告删除后写入，reportId 无外键约束故仍然保留
        auditService.record(
                LostFoundAuditAction.REPORT_DELETED,
                reportId,
                itemName,
                currentUser,
                null,
                "status=OPEN→DELETED, images=" + imageCount);
    }

    /**
     * 管理员删除：不校验 owner 与状态（由管理接口的 ADMIN/SUPER_ADMIN 权限兜底），
     * 复用 owner 删除的级联清理。审计行在 {@code deleteReportAndCleanup} 之后
     * 由调用方写入，reportId 为无外键普通列，报告删除后仍保留。
     *
     * @param reportId 报告 id；不存在时由 {@link #requireReport} 抛 404。
     */
    @Transactional
    public void deleteAsAdmin(Long reportId) {
        // 直接加载并删除，跳过 owner/状态校验——管理端接口已在 Controller 层做角色鉴权
        deleteReportAndCleanup(requireReport(reportId));
    }

    /**
     * 硬删除报告并级联清理通知、认领与 MinIO 对象；审计日志不入级联。
     *
     * <p>级联顺序有讲究：先删外键引用方（通知、认领），再删报告主行并 flush 让删除立即
     * 生效，最后才删除 MinIO 上的图片对象——对象删除放在 flush 之后，确保一旦 DB 删除
     * 失败，MinIO 对象不会先被误删（同一事务，抛异常会整体回滚）。</p>
     *
     * @param report 待删除的报告实体。
     */
    private void deleteReportAndCleanup(LostFoundReport report) {
        // 先收集全部图片的 MinIO objectKey（实体删除后这些 key 就取不到了）
        List<String> objectKeys = report.imageObjectKeys();
        // 先清理外键引用方：通知与认领
        notificationRepository.deleteByReportId(report.getId());
        claimRepository.deleteByReportId(report.getId());
        // 再删报告主行并立即 flush，确认 DB 层删除成功
        reportRepository.delete(report);
        reportRepository.flush();
        // DB 删除已落定，最后删除 MinIO 上的图片对象，避免残留孤儿对象
        objectKeys.forEach(storageService::delete);
    }

    /**
     * 整体替换报告的图片集合（编辑时使用）。
     *
     * <p>流程与创建时一致：校验 → 预训练向量 → 逐张上传新图建立新行 → 替换实体图片集合
     * （旧图随 orphanRemoval 从 DB 删除）→ 删除旧图在 MinIO 上的对象。任一步失败即回滚，
     * 已上传的新对象被清理，旧图保持不变，保证编辑失败不损坏原报告图片。</p>
     *
     * @param report 待替换图片的报告实体；
     * @param images 新图片列表（非空，已由调用方确认）。
     */
    private void replaceImages(LostFoundReport report, List<MultipartFile> images) {
        // 与创建共用同一套图片合法性校验
        validateImages(images);
        // 一次性批量生成新图的预训练视觉向量
        List<StoredEmbedding> pretrainedImages = embedMultipartImages(images);
        // 快照旧图的 MinIO 键，用于替换成功后删除
        List<String> oldKeys = report.imageObjectKeys();
        // 暂存新图行与已上传对象，失败时清理
        List<LostFoundImage> newImages = new ArrayList<>();
        List<StoredObject> uploaded = new ArrayList<>();
        registerRollbackCleanup(uploaded);
        try {
            // 逐张上传并建立新图片行（sortOrder 按新列表顺序）
            for (int index = 0; index < images.size(); index++) {
                MultipartFile image = images.get(index);
                String fingerprint = visualFingerprint(image);
                StoredObject stored = storageService.upload(image);
                uploaded.add(stored);
                newImages.add(new LostFoundImage(
                        stored.objectKey(),
                        safeOriginalName(stored.originalName()),
                        stored.contentType(),
                        stored.size(),
                        index,
                        fingerprint));
                assignImageEmbedding(newImages.getLast(), pretrainedImages, index);
            }
            // 用新集合整体替换实体图片（旧图 orphanRemoval 删除）
            report.replaceImages(newImages);
            // 替换成功后删除旧图在 MinIO 上的对象
            oldKeys.forEach(storageService::delete);
        } catch (RuntimeException ex) {
            // 失败：清掉本次已上传的新对象并重抛，事务回滚使旧图保持不变
            uploaded.forEach(stored -> storageService.delete(stored.objectKey()));
            uploaded.clear();
            throw ex;
        }
    }

    /**
     * 为报告生成文本语义/跨模态向量。
     *
     * <p>输入为 itemName + 换行 + description 的拼接文本；预训练服务不可用或调用失败时
     * 把报告标记为 PENDING（向量置空），由 {@code LostFoundEmbeddingBackfillJob} 后台回填。
     * 跨模态向量可选：服务支持才写入，否则标记 PARTIAL。</p>
     *
     * @param report 待写入向量的报告实体（实体方法内部会同步更新 embeddingStatus 与时间戳）。
     */
    private void applyTextEmbeddings(LostFoundReport report) {
        // 客户端为 null（测试构造器）时无法生成向量，直接标记 PENDING
        if (embeddingClient == null) {
            report.markEmbeddingsPending();
            return;
        }
        // 请求文本向量：内部自带容错，任何异常都返回 empty 而不会向上抛
        Optional<TextEmbeddingBundle> bundle = embeddingClient.embedDocument(
                report.getItemName() + "\n" + report.getDescription());
        // 空结果或连语义向量都没有：降级为 PENDING，等后台回填
        if (bundle.isEmpty() || bundle.get().semantic() == null) {
            report.markEmbeddingsPending();
            return;
        }
        StoredEmbedding semantic = bundle.get().semantic();
        StoredEmbedding crossModal = bundle.get().crossModal();
        // 写入报告实体；crossModal 可为 null（服务不支持该空间），此时状态为 PARTIAL
        report.assignTextEmbeddings(
                semantic.vector(), semantic.model(), semantic.revision(),
                crossModal == null ? null : crossModal.vector(),
                crossModal == null ? null : crossModal.model(),
                crossModal == null ? null : crossModal.revision());
    }

    /**
     * 批量生成图片预训练视觉向量，返回与入参同序的列表。
     *
     * <p>读取字节失败或向量服务异常时返回空列表（调用方 {@link #assignImageEmbedding}
     * 会跳过赋值），不影响报告创建的其余流程——向量只是增强匹配维度。</p>
     *
     * @param images 图片 multipart 列表。
     * @return 与 images 一一对应的向量列表；可能比入参短（缺失位置用 null 占位由调用方判断）。
     */
    private List<StoredEmbedding> embedMultipartImages(List<MultipartFile> images) {
        // 客户端为 null 或没有图片时直接返回空列表
        if (embeddingClient == null || images.isEmpty()) {
            return List.of();
        }
        try {
            // 把每张图转换为字节+类型+文件名的输入记录，一次性批量调用向量服务
            List<LostFoundEmbeddingClient.ImageInput> inputs = new ArrayList<>();
            for (MultipartFile image : images) {
                inputs.add(new LostFoundEmbeddingClient.ImageInput(
                        image.getBytes(), image.getContentType(), image.getOriginalFilename()));
            }
            return embeddingClient.embedImages(inputs);
        } catch (IOException exception) {
            // 读取图片字节失败：降级为空列表，不阻断创建
            return List.of();
        }
    }

    /**
     * 把第 index 张图片对应的预训练向量赋给图片行。
     *
     * @param image      目标图片行；
     * @param embeddings 批量向量结果（与上传顺序同序，可能比图片数少）；
     * @param index      图片在列表中的位置。
     */
    private static void assignImageEmbedding(
            LostFoundImage image, List<StoredEmbedding> embeddings, int index) {
        // 向量缺失（服务返回较少/为 null）时直接跳过，图片保持无向量状态
        if (index >= embeddings.size() || embeddings.get(index) == null) {
            return;
        }
        StoredEmbedding embedding = embeddings.get(index);
        // 写入向量值、模型名与修订号，供回填/校验判断是否需要重算
        image.assignVisualEmbedding(embedding.vector(), embedding.model(), embedding.revision());
    }

    /** 向量字节编码为 Base64 字符串供接口传输；null 原样返回 null。 */
    private static String encode(byte[] vector) {
        return vector == null ? null : Base64.getEncoder().encodeToString(vector);
    }

    /**
     * 越权校验：确认当前用户是报告发布者。
     *
     * @param report      报告实体；
     * @param currentUser 当前登录用户；
     * @param code        越权时的业务错误码（区分编辑/关闭/删除等场景）；
     * @param message     越权时的提示文案。
     * @throws LostFoundApiException 非发布者时抛 403 FORBIDDEN。
     */
    private void assertOwner(LostFoundReport report, User currentUser,
                             String code, String message) {
        if (!report.getCreatedBy().getId().equals(currentUser.getId())) {
            throw new LostFoundApiException(HttpStatus.FORBIDDEN, code, message);
        }
    }

    /** 构造 409 冲突异常（用于状态机约束，如报告已关闭不可再编辑/关闭）。 */
    private LostFoundApiException conflict(String code, String message) {
        return new LostFoundApiException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 8 参数简化重载：固定以公开搜索语义构造条件（mine=false、currentUser=null）。
     *
     * <p>供 {@link #searchCandidates} 使用，可见性统一为"非下架、公开可见"。</p>
     */
    private Specification<LostFoundReport> specification(
            ReportType reportType,
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            ReportStatus status) {
        // 转发到完整重载，公开搜索不启用 owner 模式
        return specification(
                reportType, keyword, category, colour, location, dateFrom, dateTo, status,
                false, null);
    }

    /**
     * 完整版 Specification 构造：公开搜索与 owner 模式共用的查询条件。
     *
     * <p>关键差异：</p>
     * <ul>
     *   <li>owner 模式（mine=true）按当前用户 id 过滤 createdBy，并豁免 adminHidden
     *       （用户能看到自己发布的报告，含被管理员下架的，见个人中心需求 §9.2）；</li>
     *   <li>公开搜索保持 {@code adminHidden = false} 过滤不变。</li>
     * </ul>
     *
     * @return 一个 JPA {@link Specification} 函数式对象；每次查询都会把可选条件
     *         AND 在一起。条件全部为 null 时只保留可见性谓词。
     * @throws LostFoundApiException 日期范围非法（from 晚于 to）时抛 422 INVALID_DATE_RANGE。
     */
    private Specification<LostFoundReport> specification(
            ReportType reportType,
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            ReportStatus status,
            boolean mine,
            User currentUser) {
        // 提前校验日期范围合法性，避免生成一条永假或语义错误的 SQL
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_DATE_RANGE",
                    "dateFrom must be on or before dateTo");
        }
        // 返回谓词构造函数：root=查询根实体，builder=CriteriaBuilder，predicates 累积条件
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            // 可见性过滤二选一：owner 模式按 createdBy.id 精确匹配；公开搜索过滤掉下架记录
            if (mine) {
                predicates.add(builder.equal(root.get("createdBy").get("id"), currentUser.getId()));
            } else {
                predicates.add(builder.isFalse(root.get("adminHidden")));
            }
            // 报告类型精确匹配（LOST/FOUND）
            if (reportType != null) {
                predicates.add(builder.equal(root.get("reportType"), reportType));
            }
            // 物品分类精确匹配
            if (category != null) {
                predicates.add(builder.equal(root.get("category"), category));
            }
            // 报告状态精确匹配
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            // 关键词模糊搜索：同时匹配物品名与描述，转小写避免大小写差异，OR 组合
            if (StringUtils.hasText(keyword)) {
                String pattern = likePattern(keyword);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("itemName")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern)));
            }
            if (StringUtils.hasText(colour)) {
                // P0：颜色跨语言/同义词不一致 — 命中 canonical 表时扩展为同义表面形式
                // 的 OR（white 能命中数据库里的 白色/ivory/cream），否则回退原始 LIKE。
                // 扩展保证中英文颜色词可以互相命中，避免 lower(colour) like %white% 永远
                // 匹配不到"白色"导致候选被误丢弃
                List<String> expandedColours = ColourNormalizer.expand(colour);
                if (expandedColours.isEmpty()) {
                    // 未命中 canonical 表（未知颜色词）：退化为普通子串模糊匹配
                    predicates.add(builder.like(builder.lower(root.get("colour")), likePattern(colour)));
                } else {
                    // 命中：把这组全部表面形式做成 OR 条件，任一命中即通过
                    predicates.add(builder.or(expandedColours.stream()
                            .map(synonym -> builder.like(builder.lower(root.get("colour")), likePattern(synonym)))
                            .toArray(Predicate[]::new)));
                }
            }
            // 地点模糊匹配
            if (StringUtils.hasText(location)) {
                predicates.add(builder.like(builder.lower(root.get("location")), likePattern(location)));
            }
            // 事件日期范围：下界含当天（大于等于）
            if (dateFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("eventDate"), dateFrom));
            }
            // 事件日期范围：上界含当天（小于等于）
            if (dateTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("eventDate"), dateTo));
            }
            // 所有谓词 AND 拼接；没有其他条件时仅保留可见性谓词
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * 报告实体 → 对外响应 DTO 映射。
     *
     * @param report      报告实体；
     * @param currentUser 当前用户，用于计算 createdByMe 归属标记。
     * @return 对外 DTO，图片按 sortOrder 升序排列，url 指向同源代理端点。
     */
    private LostFoundReportResponse toResponse(LostFoundReport report, User currentUser) {
        // 图片按 sortOrder 升序排列后映射为图片响应（url=同源代理端点 /api/lost-found/images/{id}）
        List<LostFoundImageResponse> images = report.getImages().stream()
                .sorted(Comparator.comparingInt(LostFoundImage::getSortOrder))
                .map(LostFoundImageResponse::of)
                .toList();

        return new LostFoundReportResponse(
                report.getId(),
                report.getReportType(),
                report.getItemName(),
                report.getCategory(),
                report.getDescription(),
                report.getColour(),
                report.getLocation(),
                report.getEventDate(),
                report.getTimeDescription(),
                report.getStatus(),
                images,
                // createdByMe：前端据此判断是否展示"编辑/删除"等归属操作
                report.getCreatedBy().getId().equals(currentUser.getId()),
                report.isAdminHidden(),
                report.getCreatedAt(),
                report.getUpdatedAt());
    }

    /**
     * 图片列表整体校验：数量 ≤5，且每张满足类型白名单/大小/尺寸/魔数要求。
     *
     * @param images 待校验图片列表。
     * @throws LostFoundApiException 校验不通过时抛出对应 4xx 异常。
     */
    private void validateImages(List<MultipartFile> images) {
        LostFoundImageRules.validateAll(images);
    }

    /** 收集报告全部图片的视觉指纹（按 sortOrder 排序，过滤掉为空的）。 */
    private List<String> visualFingerprints(LostFoundReport report) {
        return report.getImages().stream()
                .sorted(Comparator.comparingInt(LostFoundImage::getSortOrder))
                .map(LostFoundImage::getVisualFingerprint)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 计算单张图片的视觉指纹。
     *
     * @param image 图片 multipart。
     * @return VF1: 前缀的直方图指纹字符串。
     * @throws LostFoundApiException 图片字节读取失败时抛 422 IMAGE_READ_FAILED。
     */
    private String visualFingerprint(MultipartFile image) {
        try {
            // 读取字节交给提取器：JPEG/PNG 走颜色直方图，WebP 走 SHA-256 回退
            return VisualFingerprintExtractor.extract(image.getBytes(), image.getContentType());
        } catch (IOException ex) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IMAGE_READ_FAILED",
                    "The uploaded image could not be read",
                    ex);
        }
    }

    /**
     * 净化原始文件名：只保留路径最后一段并限制长度，防止把客户端传来的路径/超长名
     * 直接落库造成注入或超列宽问题。
     *
     * @param name 原始文件名。
     * @return 安全文件名；解析失败时回退为 "image"，超 255 字符时截取末尾。
     */
    private String safeOriginalName(String name) {
        String safe;
        try {
            // 取路径最后一段，避免 Windows/Unix 路径字符混入文件名
            safe = Path.of(name).getFileName().toString();
        } catch (RuntimeException ex) {
            // 非法路径（如空串/非法字符）时回退默认名
            safe = "image";
        }
        // 列宽上限 255：超长时保留末尾部分（扩展名通常在后）
        return safe.length() <= 255 ? safe : safe.substring(safe.length() - 255);
    }

    /**
     * 注册事务回滚清理回调：事务最终未提交（回滚/异常）时删除列表中的全部 MinIO 对象。
     *
     * <p>由于图片上传（外部 MinIO 写入）不在 JPA 事务管控内，必须借助事务同步回调兜底：
     * 数据库回滚时把已上传的对象也删掉，保证"有对象必有 DB 记录"。同步不活跃时（无事务），
     * 直接跳过——调用方自身的 try/catch 已经处理清理。</p>
     *
     * @param uploaded 本事务内已上传的 {@link StoredObject} 列表（可变，随后会被填充）。
     */
    private void registerRollbackCleanup(List<StoredObject> uploaded) {
        // 当前线程没有活跃事务：跳过注册（由调用方兜底清理）
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        // 注册同步回调；afterCompletion 在所有提交/回滚完成后触发
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // 只要不是 COMMITTED（即 ROLLED_BACK/UNKNOWN），就清理已上传对象
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    uploaded.forEach(stored -> storageService.delete(stored.objectKey()));
                }
            }
        });
    }

    /**
     * 事务提交成功后消费暂存图（仅删除暂存元数据行，MinIO 对象已由报告图片行引用保留）。
     *
     * <p>放在 afterCommit 里执行，避免"报告创建失败却提前把暂存元数据删掉"，导致用户无法
     * 重试；同时也不在事务内执行，防止占用数据库事务资源。同步不活跃时（无事务）直接消费。</p>
     *
     * @param objectKeys 已被报告引用的暂存 objectKey 列表。
     */
    private void consumeStagedImagesAfterCommit(List<String> objectKeys) {
        // 无活跃事务：直接执行消费
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            stagingService.consume(objectKeys);
            return;
        }
        // 事务提交成功后才消费：afterCommit 内执行
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stagingService.consume(objectKeys);
            }
        });
    }

    /** 空白字符串归一为 null（空白字段不落库），非空白时去首尾空白后返回。 */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 构造 SQL LIKE 模式：转小写 + 两侧通配符，配合 lower(col) 使用实现大小写不敏感模糊匹配。 */
    private String likePattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
