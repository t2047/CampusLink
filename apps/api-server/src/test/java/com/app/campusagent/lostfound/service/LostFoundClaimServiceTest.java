package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.ClaimDecisionRequest;
import com.app.campusagent.lostfound.dto.CreateClaimRequest;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LostFoundClaimServiceTest {

    @Mock
    private LostFoundClaimRepository claimRepository;

    @Mock
    private LostFoundReportRepository reportRepository;

    private LostFoundClaimService service;
    private User owner;
    private User claimant;

    @BeforeEach
    void setUp() throws Exception {
        service = new LostFoundClaimService(claimRepository, reportRepository);
        owner = user(1L, "owner@u.nus.edu");
        claimant = user(2L, "claimant@u.nus.edu");
    }

    @Test
    void createsClaimForOpenFoundReport() throws Exception {
        LostFoundReport report = report(ReportType.FOUND, owner, 20L);
        when(reportRepository.findById(20L)).thenReturn(Optional.of(report));
        when(claimRepository.existsByReportIdAndClaimantIdAndStatusIn(any(), any(), any()))
                .thenReturn(false);
        when(claimRepository.save(any())).thenAnswer(invocation -> {
            LostFoundClaim claim = invocation.getArgument(0);
            setClaimFields(claim, 30L);
            return claim;
        });

        var response = service.create(
                20L,
                new CreateClaimRequest("The case has my initials engraved inside."),
                claimant);

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(response.submittedByMe()).isTrue();
    }

    @Test
    void rejectsClaimForLostReport() throws Exception {
        LostFoundReport report = report(ReportType.LOST, owner, 20L);
        when(reportRepository.findById(20L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.create(
                20L,
                new CreateClaimRequest("This is definitely my missing item."),
                claimant))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("ONLY_FOUND_REPORTS_CAN_BE_CLAIMED");
    }

    @Test
    void rejectsClaimByReportOwner() throws Exception {
        LostFoundReport report = report(ReportType.FOUND, owner, 20L);
        when(reportRepository.findById(20L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.create(
                20L,
                new CreateClaimRequest("Trying to claim my own report."),
                owner))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("CANNOT_CLAIM_OWN_REPORT");
    }

    @Test
    void rejectsDuplicateActiveClaim() throws Exception {
        LostFoundReport report = report(ReportType.FOUND, owner, 20L);
        when(reportRepository.findById(20L)).thenReturn(Optional.of(report));
        when(claimRepository.existsByReportIdAndClaimantIdAndStatusIn(any(), any(), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(
                20L,
                new CreateClaimRequest("My initials are engraved inside the case."),
                claimant))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("CLAIM_ALREADY_EXISTS");
    }

    @Test
    void rejectsClaimWhenReportIsAlreadyClaimed() throws Exception {
        LostFoundReport report = report(ReportType.FOUND, owner, 20L);
        report.markClaimed();
        when(reportRepository.findById(20L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.create(
                20L,
                new CreateClaimRequest("My initials are engraved inside the case."),
                claimant))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("REPORT_NOT_OPEN");
    }

    @Test
    void ownerApprovalClaimsReportAndRejectsOtherPendingClaims() throws Exception {
        LostFoundReport report = report(ReportType.FOUND, owner, 20L);
        LostFoundClaim selected = claim(report, claimant, 31L);
        LostFoundClaim other = claim(report, user(3L, "other@u.nus.edu"), 32L);
        when(claimRepository.findById(31L)).thenReturn(Optional.of(selected));
        when(claimRepository.findByReportIdAndStatus(20L, ClaimStatus.SUBMITTED))
                .thenReturn(List.of(selected, other));
        when(claimRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.approve(31L, new ClaimDecisionRequest("Proof verified"), owner);

        assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.CLAIMED);
        assertThat(other.getStatus()).isEqualTo(ClaimStatus.REJECTED);
    }

    @Test
    void nonOwnerCannotReviewClaim() throws Exception {
        LostFoundReport report = report(ReportType.FOUND, owner, 20L);
        LostFoundClaim claim = claim(report, claimant, 31L);
        when(claimRepository.findById(31L)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service.reject(
                31L,
                new ClaimDecisionRequest("No"),
                user(4L, "stranger@u.nus.edu")))
                .isInstanceOf(LostFoundApiException.class)
                .extracting("code")
                .isEqualTo("CLAIM_REVIEW_FORBIDDEN");
    }

    private User user(Long id, String email) throws Exception {
        User user = new User(email, "encoded");
        setField(user, "id", id);
        return user;
    }

    private LostFoundReport report(ReportType type, User creator, Long id) throws Exception {
        LostFoundReport report = new LostFoundReport(
                type,
                "Black AirPods",
                ItemCategory.ELECTRONICS,
                "Black AirPods found near the library entrance.",
                "Black",
                "Central Library",
                LocalDate.now().minusDays(1),
                "Afternoon",
                creator);
        setField(report, "id", id);
        setField(report, "createdAt", Instant.now());
        setField(report, "updatedAt", Instant.now());
        return report;
    }

    private LostFoundClaim claim(LostFoundReport report, User user, Long id) throws Exception {
        LostFoundClaim claim = new LostFoundClaim(
                report,
                user,
                "The item contains a private identifying mark.");
        setClaimFields(claim, id);
        return claim;
    }

    private void setClaimFields(LostFoundClaim claim, Long id) throws Exception {
        setField(claim, "id", id);
        setField(claim, "createdAt", Instant.now());
        setField(claim, "updatedAt", Instant.now());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
