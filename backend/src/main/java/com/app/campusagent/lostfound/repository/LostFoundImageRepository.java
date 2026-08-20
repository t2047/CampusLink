/**
 * 失物招领（Lost & Found）模块的「报告图片」仓储接口。
 *
 * <p>负责 {@link LostFoundImage}（报告关联的图片元数据 + 视觉指纹/视觉向量信息）实体的
 * 持久化访问，基于 {@code Long} 主键继承 {@link JpaRepository} 的标准 CRUD 能力。
 * 除常规查询外，还提供了面向「视觉指纹回填、视觉向量计算、暂存清理」等后台任务的检索方法。</p>
 */
package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundImage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 报告图片仓储。
 *
 * <p>图片实体上的 {@code visualFingerprint}（视觉指纹）与 {@code visualEmbedding}
 * （视觉向量）字段由后台任务异步计算。本仓储中相关方法的作用是从海量图片中分页筛出
 * "需要回填 / 需要重算 / 可能被引用"的记录，供批处理任务逐批消费。</p>
 */
public interface LostFoundImageRepository extends JpaRepository<LostFoundImage, Long> {

    /**
     * 分页查询尚未生成视觉指纹（{@code visualFingerprint} 为 {@code null}）的图片。
     *
     * <p>用于视觉指纹的批量回填任务：以分页方式逐批取出缺失指纹的图片进行处理。
     * 生成 SQL 语义：{@code WHERE visual_fingerprint IS NULL}。</p>
     *
     * @param pageable 分页参数
     * @return 缺指纹的图片分页结果
     */
    Page<LostFoundImage> findByVisualFingerprintIsNull(Pageable pageable);

    /**
     * 分页查询「需要计算视觉向量」的图片。
     *
     * <p>视觉向量依赖某个版本的模型（{@code visualEmbeddingRevision}）。下列任一情况都
     * 意味着当前图片的向量缺失或过期，需要重新计算：</p>
     * <ul>
     *   <li>{@code visualEmbedding} 为 {@code null}——从未计算过向量；</li>
     *   <li>{@code visualEmbeddingRevision} 为 {@code null}——旧数据未记录模型版本；</li>
     *   <li>{@code visualEmbeddingRevision} 不等于目标 {@code revision}——模型已升级，旧版本向量作废。</li>
     * </ul>
     *
     * <p>JPQL 逐行语义（文本块内部不得插入注释，故在此说明）：
     * {@code from LostFoundImage i} 遍历全部图片；三个 {@code or} 分支分别对应上述三种
     * 缺失/过期情形，命中其一即入选；最后 {@code order by i.id} 按 id 升序返回，
     * 保证分批处理时结果稳定、不重不漏。</p>
     *
     * @param revision 当前生效的向量模型版本号，用于识别过期向量
     * @param pageable 分页参数
     * @return 需要计算视觉向量的图片分页结果（按 id 升序）
     */
    @Query("""
            select i from LostFoundImage i
            where i.visualEmbedding is null
               or i.visualEmbeddingRevision is null
               or i.visualEmbeddingRevision <> :revision
            order by i.id
            """)
    Page<LostFoundImage> findNeedingVisualEmbedding(
            @Param("revision") String revision, Pageable pageable);

    /**
     * 暂存 TTL 清理时判断 objectKey 是否已被报告引用（引用的键需跳过）。
     *
     * <p>暂存图片在用户提交报告后会被正式挂载为 {@link LostFoundImage} 并写入本表。
     * 后台清理任务对每条过期暂存记录调用本方法：仅当返回 {@code false}（未被引用）时
     * 才允许从对象存储中删除该图片，从而避免误删正式报告正在使用的图片。</p>
     *
     * @param objectKey MinIO 对象键
     * @return 已被报告引用返回 {@code true}，否则返回 {@code false}
     */
    boolean existsByObjectKey(String objectKey);
}
