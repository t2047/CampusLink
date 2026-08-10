package com.app.campusagent.lostfound.domain;

import com.app.campusagent.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Table(name = "lost_found_reports", indexes = {
        @Index(name = "idx_lf_report_type_status", columnList = "report_type,status"),
        @Index(name = "idx_lf_category", columnList = "category"),
        @Index(name = "idx_lf_event_date", columnList = "event_date"),
        @Index(name = "idx_lf_created_by", columnList = "created_by")
})
public class LostFoundReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 16)
    private ReportType reportType;

    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ItemCategory category;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(length = 50)
    private String colour;

    @Column(nullable = false, length = 200)
    private String location;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "time_description", length = 100)
    private String timeDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportStatus status = ReportStatus.OPEN;

    /** 管理员下架标记：隐藏的记录不进入公开搜索、候选匹配和非 owner/非管理员详情。 */
    @Column(name = "admin_hidden", nullable = false)
    private boolean adminHidden;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @org.hibernate.annotations.BatchSize(size = 50)
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<LostFoundImage> images = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LostFoundReport() {
    }

    public LostFoundReport(ReportType reportType,
                           String itemName,
                           ItemCategory category,
                           String description,
                           String colour,
                           String location,
                           LocalDate eventDate,
                           String timeDescription,
                           User createdBy) {
        this.reportType = reportType;
        this.itemName = itemName;
        this.category = category;
        this.description = description;
        this.colour = colour;
        this.location = location;
        this.eventDate = eventDate;
        this.timeDescription = timeDescription;
        this.createdBy = createdBy;
    }

    public void addImage(LostFoundImage image) {
        images.add(image);
        image.attachTo(this);
    }

    public void markClaimed() {
        status = ReportStatus.CLAIMED;
    }

    public void markClosed() {
        status = ReportStatus.CLOSED;
    }

    /** 管理员下架：从公开搜索与候选匹配中隐藏。 */
    public void hide() {
        adminHidden = true;
    }

    /** 管理员恢复：重新对公开可见。 */
    public void show() {
        adminHidden = false;
    }

    public void updateDetails(String itemName,
                              ItemCategory category,
                              String description,
                              String colour,
                              String location,
                              LocalDate eventDate,
                              String timeDescription) {
        this.itemName = itemName;
        this.category = category;
        this.description = description;
        this.colour = colour;
        this.location = location;
        this.eventDate = eventDate;
        this.timeDescription = timeDescription;
    }

    /** 报告删除前收集其全部图片的 MinIO 对象键，供 service 层清理存储。 */
    public List<String> imageObjectKeys() {
        return images.stream().map(LostFoundImage::getObjectKey).toList();
    }

    /** 整体替换图片集合（编辑时使用）：旧图随 orphanRemoval 删除，新图按序挂载。 */
    public void replaceImages(List<LostFoundImage> newImages) {
        images.clear();
        newImages.forEach(this::addImage);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
