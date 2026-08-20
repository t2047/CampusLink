/**
 * 失物招领（Lost & Found）模块的「暂存图片」仓储接口。
 *
 * <p>负责 {@link LostFoundStagedImage}（用户上传但尚未提交报告、暂时存放在对象存储中的
 * 图片元数据）的持久化访问。与其它仓储不同，该实体以 {@code String} 类型的
 * {@code objectKey}（MinIO 对象键）作为主键，因此继承的是
 * {@code JpaRepository<LostFoundStagedImage, String>}。</p>
 */
package com.app.campusagent.lostfound.repository;

import com.app.campusagent.lostfound.domain.LostFoundStagedImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 暂存图片仓储。
 *
 * <p>暂存图片在用户提交报告时会被"认领"并转正为正式的 {@code LostFoundImage}，
 * 其余未被认领且过期的暂存记录由 TTL 清理任务删除。本仓储提供按「对象键 + 创建人」
 * 的归属查询，以及按过期时间批量取数两种能力。</p>
 */
public interface LostFoundStagedImageRepository
        extends JpaRepository<LostFoundStagedImage, String> {

    /**
     * 按「对象键 + 创建人」查询暂存图片。
     *
     * <p>用于提交报告时把暂存图片转正：需同时校验该暂存记录确实属于当前用户
     * （归属校验），防止拿他人上传的暂存键冒充自己的图片。
     * 生成 SQL 语义：{@code WHERE object_key = ? AND created_by = ?}。</p>
     *
     * @param objectKey MinIO 对象键
     * @param userId    暂存图片的创建人（用户）主键
     * @return 匹配的暂存记录（若存在），否则为空
     */
    Optional<LostFoundStagedImage> findByObjectKeyAndCreatedById(String objectKey, Long userId);

    /**
     * 查询前 100 条已过期（{@code expiresAt} 早于给定时刻）的暂存图片。
     *
     * <p>供 TTL 清理任务使用：{@code findTop100} 限定每次最多取 100 条，配合
     * {@code expires_at < ?} 条件分批清除过期暂存数据，避免一次性加载过多记录。
     * 生成 SQL 语义：{@code WHERE expires_at < ? LIMIT 100}。</p>
     *
     * @param now 当前时间，用于判断暂存记录是否已超过有效期
     * @return 已过期暂存图片的列表（最多 100 条）
     */
    List<LostFoundStagedImage> findTop100ByExpiresAtBefore(Instant now);
}
