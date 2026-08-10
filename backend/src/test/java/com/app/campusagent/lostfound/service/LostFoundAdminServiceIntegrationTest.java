package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundAuditAction;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.repository.LostFoundAuditLogRepository;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class LostFoundAdminServiceIntegrationTest {

    @Autowired
    private LostFoundAdminService adminService;

    @Autowired
    private LostFoundReportRepository reportRepository;

    @Autowired
    private LostFoundClaimRepository claimRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LostFoundAuditLogRepository auditLogRepository;

    @Test
    void returnsOverviewMetricsAndFilteredReports() {
        User owner = userRepository.save(new User("owner-admin-view@u.nus.edu", "encoded"));
        User claimant = userRepository.save(new User("claimant-admin-view@u.nus.edu", "encoded"));

        LostFoundReport found = reportRepository.save(report(
                ReportType.FOUND,
                "Black Headphones",
                ItemCategory.ELECTRONICS,
                "Black wireless headphones with a scratched case.",
                "Black",
                "Central Library",
                LocalDate.now().minusDays(1),
                owner));
        LostFoundReport lost = report(
                ReportType.LOST,
                "Blue Backpack",
                ItemCategory.BAG,
                "Blue backpack with a white keychain attached.",
                "Blue",
                "Engineering Block",
                LocalDate.now().minusDays(2),
                owner);
        lost.markClaimed();
        reportRepository.save(lost);
        claimRepository.save(new LostFoundClaim(
                found,
                claimant,
                "The left ear cup has a small silver ownership sticker."));
        reportRepository.flush();

        var overview = adminService.overview();
        assertThat(overview.totalReports()).isEqualTo(2);
        assertThat(overview.openReports()).isEqualTo(1);
        assertThat(overview.claimedReports()).isEqualTo(1);
        assertThat(overview.closedReports()).isZero();
        assertThat(overview.lostReports()).isEqualTo(1);
        assertThat(overview.foundReports()).isEqualTo(1);
        assertThat(overview.submittedClaims()).isEqualTo(1);
        assertThat(overview.hiddenReports()).isZero();

        var result = adminService.search(
                ReportType.FOUND,
                "headphones",
                ItemCategory.ELECTRONICS,
                "black",
                "library",
                LocalDate.now().minusDays(2),
                LocalDate.now(),
                ReportStatus.OPEN,
                null,
                PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().getFirst().itemName()).isEqualTo("Black Headphones");
        assertThat(result.content().getFirst().createdByEmail()).isEqualTo(owner.getEmail());
    }

    @Test
    void rejectsReversedDateRange() {
        assertThatThrownBy(() -> adminService.search(
                null,
                null,
                null,
                null,
                null,
                LocalDate.now(),
                LocalDate.now().minusDays(1),
                null,
                null,
                PageRequest.of(0, 25)))
                .extracting("code")
                .isEqualTo("INVALID_DATE_RANGE");
    }

    @Test
    void delistHidesReportAndRecordsAudit() {
        User owner = userRepository.save(new User("owner-delist@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-delist@campuslink.com"));
        LostFoundReport report = reportRepository.save(report(
                ReportType.FOUND,
                "Problem Poster",
                ItemCategory.OTHER,
                "A poster that should be taken down.",
                "White",
                "Hall A",
                LocalDate.now().minusDays(1),
                owner));

        var response = adminService.delist(report.getId(), "Repeated spamming content", admin);

        assertThat(response.adminHidden()).isTrue();
        assertThat(adminService.overview().hiddenReports()).isEqualTo(1);
        var hiddenOnly = adminService.search(
                null, null, null, null, null, null, null, null, true,
                PageRequest.of(0, 25));
        assertThat(hiddenOnly.content())
                .extracting(com.app.campusagent.lostfound.dto.admin.AdminLostFoundReportResponse::id)
                .contains(report.getId());

        var logs = adminService.auditLogs(
                report.getId(), LostFoundAuditAction.REPORT_DELISTED, null, null,
                PageRequest.of(0, 25));
        assertThat(logs.content()).singleElement().satisfies(log -> {
            assertThat(log.actorEmail()).isEqualTo(admin.getEmail());
            assertThat(log.reason()).isEqualTo("Repeated spamming content");
            assertThat(log.detail()).isEqualTo("adminHidden=false→true");
        });
    }

    @Test
    void delistAlreadyHiddenRejects() {
        User owner = userRepository.save(new User("owner-delist-twice@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-delist-twice@campuslink.com"));
        LostFoundReport report = reportRepository.save(report(
                ReportType.FOUND,
                "Hidden Again",
                ItemCategory.OTHER,
                "Already hidden.",
                "White",
                "Hall A",
                LocalDate.now().minusDays(1),
                owner));
        adminService.delist(report.getId(), "First delist", admin);

        assertThatThrownBy(() -> adminService.delist(report.getId(), "Second delist", admin))
                .extracting("code")
                .isEqualTo("REPORT_ALREADY_HIDDEN");
    }

    @Test
    void restoreUnhidesReportAndRecordsAudit() {
        User owner = userRepository.save(new User("owner-restore@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-restore@campuslink.com"));
        LostFoundReport report = reportRepository.save(report(
                ReportType.FOUND,
                "Mistaken Delist",
                ItemCategory.BAG,
                "Should be back online.",
                "Blue",
                "Library",
                LocalDate.now().minusDays(1),
                owner));
        adminService.delist(report.getId(), "Initial delist", admin);

        var response = adminService.restore(report.getId(), "Wrongly delisted", admin);

        assertThat(response.adminHidden()).isFalse();
        assertThat(adminService.overview().hiddenReports()).isZero();
        var logs = adminService.auditLogs(
                report.getId(), LostFoundAuditAction.REPORT_RESTORED, null, null,
                PageRequest.of(0, 25));
        assertThat(logs.content()).singleElement().satisfies(log -> {
            assertThat(log.reason()).isEqualTo("Wrongly delisted");
            assertThat(log.detail()).isEqualTo("adminHidden=true→false");
        });
    }

    @Test
    void restoreVisibleReportRejects() {
        User owner = userRepository.save(new User("owner-restore-visible@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-restore-visible@campuslink.com"));
        LostFoundReport report = reportRepository.save(report(
                ReportType.FOUND,
                "Visible Already",
                ItemCategory.OTHER,
                "Not hidden.",
                "White",
                "Hall A",
                LocalDate.now().minusDays(1),
                owner));

        assertThatThrownBy(() -> adminService.restore(report.getId(), "Nothing to do", admin))
                .extracting("code")
                .isEqualTo("REPORT_NOT_HIDDEN");
    }

    @Test
    void deleteReportCascadesAndKeepsAuditTrail() {
        User owner = userRepository.save(new User("owner-admin-delete@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-delete@campuslink.com"));
        LostFoundReport report = reportRepository.save(report(
                ReportType.FOUND,
                "To Be Removed",
                ItemCategory.ELECTRONICS,
                "Explicit content to be removed.",
                "Black",
                "Hall B",
                LocalDate.now().minusDays(1),
                owner));
        Long reportId = report.getId();

        adminService.deleteReport(reportId, "Community guidelines violation", admin);

        assertThat(reportRepository.findById(reportId)).isEmpty();
        assertThat(auditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getReportId()).isEqualTo(reportId);
            assertThat(log.getAction()).isEqualTo(LostFoundAuditAction.REPORT_DELETED_BY_ADMIN);
            assertThat(log.getItemName()).isEqualTo("To Be Removed");
            assertThat(log.getActorEmail()).isEqualTo(admin.getEmail());
            assertThat(log.getReason()).isEqualTo("Community guidelines violation");
        });
    }

    @Test
    void adminSearchCanFilterByKeywordOverAuditLogs() {
        User owner = userRepository.save(new User("owner-audit-search@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-audit-search@campuslink.com"));
        LostFoundReport report = reportRepository.save(report(
                ReportType.FOUND,
                "Audit Search Target",
                ItemCategory.BAG,
                "For audit filter test.",
                "Green",
                "Canteen",
                LocalDate.now().minusDays(1),
                owner));
        adminService.delist(report.getId(), "Reason with unique token 42", admin);

        var byReport = adminService.auditLogs(report.getId(), null, null, null, PageRequest.of(0, 25));
        assertThat(byReport.totalElements()).isEqualTo(1);

        var byKeyword = adminService.auditLogs(
                null, null, null, "audit search", PageRequest.of(0, 25));
        assertThat(byKeyword.content())
                .extracting(com.app.campusagent.lostfound.dto.admin.AdminAuditLogResponse::itemName)
                .contains("Audit Search Target");
    }

    private User adminUser(String email) {
        User admin = new User(email, "encoded");
        admin.setRole(Role.ADMIN);
        return userRepository.save(admin);
    }

    private LostFoundReport report(
            ReportType type,
            String name,
            ItemCategory category,
            String description,
            String colour,
            String location,
            LocalDate date,
            User owner) {
        return new LostFoundReport(
                type,
                name,
                category,
                description,
                colour,
                location,
                date,
                null,
                owner);
    }
}
