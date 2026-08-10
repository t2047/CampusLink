package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
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
                reportRepository, mock(ObjectStorageService.class));
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
    void rejectsReversedDateRange() {
        LostFoundReportService service = new LostFoundReportService(
                reportRepository, mock(ObjectStorageService.class));
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
