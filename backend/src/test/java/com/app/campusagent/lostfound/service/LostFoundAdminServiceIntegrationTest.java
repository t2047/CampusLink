package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
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

        var result = adminService.search(
                ReportType.FOUND,
                "headphones",
                ItemCategory.ELECTRONICS,
                "black",
                "library",
                LocalDate.now().minusDays(2),
                LocalDate.now(),
                ReportStatus.OPEN,
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
                PageRequest.of(0, 25)))
                .extracting("code")
                .isEqualTo("INVALID_DATE_RANGE");
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
