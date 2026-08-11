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
                mock(LostFoundAuditService.class));
        var result = service.search(
                ReportType.FOUND,
                "HEADPHONES",
                ItemCategory.ELECTRONICS,
                "black",
                "library",
                LocalDate.now().minusDays(2),
                LocalDate.now(),
                ReportStatus.OPEN,
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
                mock(LostFoundAuditService.class));

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
                mock(LostFoundAuditService.class));

        var search = service.search(
                null, null, null, null, null, null, null, null,
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
    void rejectsReversedDateRange() {
        LostFoundReportService service = new LostFoundReportService(
                reportRepository, mock(ObjectStorageService.class),
                mock(LostFoundClaimRepository.class), mock(LostFoundNotificationRepository.class),
                mock(LostFoundAuditService.class));
        User currentUser = new User("reader@u.nus.edu", "encoded");

        assertThatThrownBy(() -> service.search(
                null, null, null, null, null,
                LocalDate.now(), LocalDate.now().minusDays(1), null,
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
