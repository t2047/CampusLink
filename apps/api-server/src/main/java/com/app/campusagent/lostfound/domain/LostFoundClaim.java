package com.app.campusagent.lostfound.domain;

import com.app.campusagent.domain.User;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "lost_found_claims", indexes = {
        @Index(name = "idx_lf_claim_report_status", columnList = "report_id,status"),
        @Index(name = "idx_lf_claim_claimant", columnList = "claimant_id")
})
public class LostFoundClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private LostFoundReport report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claimant_id", nullable = false)
    private User claimant;

    @Column(name = "proof_description", nullable = false, length = 1000)
    private String proofDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClaimStatus status = ClaimStatus.SUBMITTED;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LostFoundClaim() {
    }

    public LostFoundClaim(LostFoundReport report, User claimant, String proofDescription) {
        this.report = report;
        this.claimant = claimant;
        this.proofDescription = proofDescription;
    }

    public void approve(String note) {
        status = ClaimStatus.APPROVED;
        decisionNote = note;
    }

    public void reject(String note) {
        status = ClaimStatus.REJECTED;
        decisionNote = note;
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
