package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundNotificationRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.lostfound.storage.ObjectStorageService;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class LostFoundSearchIntegrationTest {

    @Autowired
    private LostFoundReportRepository reportRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void combinesKeywordCategoryColourLocationDateTypeAndStatusFilters() {
        User owner = userRepository.save(new User("owner@u.nus.edu", "encoded"));
        reportRepository.save(report(
                ReportType.FOUND, "Black Headphones", ItemCategory.ELECTRONICS,
                "Wireless headphones with a scratched case.", "Black", "Central Library",
                LocalDate.now().minusDays(1), owner));
        reportRepository.save(report(
                ReportType.FOUND, "Blue Backpack", ItemCategory.BAG,
                "A blue canvas backpack near the entrance.", "Blue", "Engineering Library",
                LocalDate.now().minusDays(4), owner));
        reportRepository.save(report(
                ReportType.LOST, "Black Headphones", ItemCategory.ELECTRONICS,
                "Lost my wireless headphones and their case.", "Black", "Central Library",
                LocalDate.now().minusDays(1), owner));
        reportRepository.flush();

        LostFoundReportService service = new LostFoundReportService(
                reportRepository, mock(ObjectStorageService.class),
                mock(LostFoundClaimRepository.class), mock(LostFoundNotificationRepository.class),
                mock(LostFoundAuditService.class), mock(LostFoundImageStagingService.class));
        var result = service.search(
                ReportType.FOUND,
                "HEADPHONES",
                ItemCategory.ELECTRONICS,
                "black",
                "library",
                LocalDate.now().minusDays(2),
                LocalDate.now(),
                ReportStatus.OPEN,
                null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")),
                owner);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().getFirst().itemName()).isEqualTo("Black Headphones");
    }

    @Test
    void searchCandidatesExposesImageUrls() {
        User owner = userRepository.save(new User("owner2@u.nus.edu", "encoded"));
        LostFoundReport report = report(
                ReportType.FOUND,
                "Blue Water Bottle",
                ItemCategory.OTHER,
                "A blue water bottle left at the sports hall.",
                "Blue",
                "Sports Hall",
                LocalDate.now().minusDays(1),
                owner);

        report.addImage(new LostFoundImage(
                "lost-found/bottle.png",
                "bottle.png",
                "image/png",
                1024L,
                0,
                "VF1:expected-fingerprint"));

        LostFoundReport saved = reportRepository.saveAndFlush(report);
        Long imageId = saved.getImages().getFirst().getId();

        ObjectStorageService storageService = mock(ObjectStorageService.class);

        LostFoundReportService service = new LostFoundReportService(
                reportRepository,
                storageService,
                mock(LostFoundClaimRepository.class),
                mock(LostFoundNotificationRepository.class),
                mock(LostFoundAuditService.class),
                mock(LostFoundImageStagingService.class));

        var result = service.searchCandidates(
                ReportType.FOUND,
                "water",
                null,
                null,
                null,
                null,
                null,
                PageRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().reportType())
                .isEqualTo(ReportType.FOUND);
        assertThat(result.content().getFirst().imageUrls())
                .containsExactly("/api/lost-found/images/" + imageId);
        // 候选指纹与 imageUrls 同序，供 Agent 匹配打分使用
        assertThat(result.content().getFirst().visualFingerprints())
                .containsExactly("VF1:expected-fingerprint");
    }
    @Test
    void publicSearchAndCandidatesExcludeHiddenReports() {
        User owner = userRepository.save(new User("owner-hidden@u.nus.edu", "encoded"));
        LostFoundReport hidden = report(
                ReportType.FOUND, "Hidden Headphones", ItemCategory.ELECTRONICS,
                "A pair of headphones that should not be visible.", "Black", "Central Library",
                LocalDate.now().minusDays(1), owner);
        hidden.hide();
        reportRepository.saveAndFlush(hidden);
        reportRepository.saveAndFlush(report(
                ReportType.FOUND, "Visible Umbrella", ItemCategory.UMBRELLA,
                "A blue umbrella near the gate.", "Blue", "East Gate",
                LocalDate.now().minusDays(1), owner));

        LostFoundReportService service = new LostFoundReportService(
                reportRepository, mock(ObjectStorageService.class),
                mock(LostFoundClaimRepository.class), mock(LostFoundNotificationRepository.class),
                mock(LostFoundAuditService.class), mock(LostFoundImageStagingService.class));

        var search = service.search(
                null, null, null, null, null, null, null, null, null,
                PageRequest.of(0, 20), owner);
        assertThat(search.content())
                .extracting(com.app.campusagent.lostfound.dto.LostFoundReportResponse::itemName)
                .contains("Visible Umbrella")
                .doesNotContain("Hidden Headphones");

        var candidates = service.searchCandidates(
                ReportType.FOUND, null, null, null, null, null, null, PageRequest.of(0, 20));
        assertThat(candidates.content())
                .extracting(com.app.campusagent.lostfound.dto.agent.AgentCandidateResponse::itemName)
                .doesNotContain("Hidden Headphones");
    }

    @Test
    void ownerMeReturnsOnlyOwnReportsAndIncludesHiddenOnes() {
        User owner = userRepository.save(new User("owner-me@u.nus.edu", "encoded"));
        User other = userRepository.save(new User("other-me@u.nus.edu", "encoded"));
        LostFoundReport mineLost = report(
                ReportType.LOST, "My Wallet", ItemCategory.WALLET_PURSE,
                "A black leather wallet.", "Black", "Central Library",
                LocalDate.now().minusDays(1), owner);
        LostFoundReport mineHidden = report(
                ReportType.LOST, "Hidden Watch", ItemCategory.OTHER,
                "A silver wrist watch removed by an admin.", "Silver", "Sports Hall",
                LocalDate.now().minusDays(2), owner);
        mineHidden.hide();
        reportRepository.saveAndFlush(mineLost);
        reportRepository.saveAndFlush(mineHidden);
        reportRepository.saveAndFlush(report(
                ReportType.LOST, "Other Phone", ItemCategory.ELECTRONICS,
                "A phone posted by someone else.", "Black", "Engineering Library",
                LocalDate.now().minusDays(3), other));
        reportRepository.flush();

        LostFoundReportService service = new LostFoundReportService(
                reportRepository, mock(ObjectStorageService.class),
                mock(LostFoundClaimRepository.class), mock(LostFoundNotificationRepository.class),
                mock(LostFoundAuditService.class), mock(LostFoundImageStagingService.class));

        var result = service.search(
                ReportType.LOST, null, null, null, null, null, null, null, "me",
                PageRequest.of(0, 20), owner);

        assertThat(result.content()).hasSize(2);
        assertThat(result.content())
                .extracting(com.app.campusagent.lostfound.dto.LostFoundReportResponse::itemName)
                .containsExactlyInAnyOrder("My Wallet", "Hidden Watch");
        assertThat(result.content())
                .filteredOn(r -> r.itemName().equals("Hidden Watch"))
                .singleElement()
                .satisfies(r -> assertThat(r.adminHidden()).isTrue());
    }

    @Test
    void ownerMeCombinesWithStatusFilter() {
        User owner = userRepository.save(new User("owner-status@u.nus.edu", "encoded"));
        LostFoundReport openReport = report(
                ReportType.LOST, "Open Headphones", ItemCategory.ELECTRONICS,
                "Open status headphones.", "Black", "Central Library",
                LocalDate.now().minusDays(1), owner);
        LostFoundReport closedReport = report(
                ReportType.LOST, "Closed Umbrella", ItemCategory.UMBRELLA,
                "A closed status umbrella.", "Blue", "East Gate",
                LocalDate.now().minusDays(2), owner);
        closedReport.markClosed();
        reportRepository.saveAndFlush(openReport);
        reportRepository.saveAndFlush(closedReport);
        reportRepository.flush();

        LostFoundReportService service = new LostFoundReportService(
                reportRepository, mock(ObjectStorageService.class),
                mock(LostFoundClaimRepository.class), mock(LostFoundNotificationRepository.class),
                mock(LostFoundAuditService.class), mock(LostFoundImageStagingService.class));

        var result = service.search(
                ReportType.LOST, null, null, null, null, null, null, ReportStatus.CLOSED, "me",
                PageRequest.of(0, 20), owner);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().itemName()).isEqualTo("Closed Umbrella");
    }

    @Test
    void rejectsInvalidOwnerFilter() {
        LostFoundReportService service = new LostFoundReportService(
                reportRepository, mock(ObjectStorageService.class),
                mock(LostFoundClaimRepository.class), mock(LostFoundNotificationRepository.class),
                mock(LostFoundAuditService.class), mock(LostFoundImageStagingService.class));
        User currentUser = new User("reader@u.nus.edu", "encoded");

        assertThatThrownBy(() -> service.search(
                null, null, null, null, null,
                null, null, null, "someone",
                PageRequest.of(0, 20), currentUser))
                .extracting("code")
                .isEqualTo("INVALID_OWNER_FILTER");
    }

    @Test
    void rejectsReversedDateRange() {
        LostFoundReportService service = new LostFoundReportService(
                reportRepository, mock(ObjectStorageService.class),
                mock(LostFoundClaimRepository.class), mock(LostFoundNotificationRepository.class),
                mock(LostFoundAuditService.class), mock(LostFoundImageStagingService.class));
        User currentUser = new User("reader@u.nus.edu", "encoded");

        assertThatThrownBy(() -> service.search(
                null, null, null, null, null,
                LocalDate.now(), LocalDate.now().minusDays(1), null, null,
                PageRequest.of(0, 20), currentUser))
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
                type, name, category, description, colour, location, date, null, owner);
    }
}
