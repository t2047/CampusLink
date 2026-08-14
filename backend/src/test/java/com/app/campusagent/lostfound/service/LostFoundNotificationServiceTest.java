package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundNotification;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.NotificationType;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.repository.LostFoundNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LostFoundNotificationServiceTest {

    @Mock
    private LostFoundNotificationRepository repository;

    private LostFoundNotificationService service;
    private User owner;
    private User claimant;

    @BeforeEach
    void setUp() throws Exception {
        service = new LostFoundNotificationService(repository);
        owner = user(1L, "owner@u.nus.edu");
        claimant = user(2L, "claimant@u.nus.edu");
    }

    @Test
    void claimSubmittedCreatesOwnerNotification() throws Exception {
        LostFoundClaim claim = claim(report(owner, 10L), claimant, 20L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.claimSubmitted(claim);

        verify(repository).save(any(LostFoundNotification.class));
    }

    @Test
    void mineReturnsReadStateForRecipient() throws Exception {
        LostFoundClaim claim = claim(report(owner, 10L), claimant, 20L);
        LostFoundNotification notification = notification(claimant, NotificationType.CLAIM_APPROVED, claim, 30L);
        notification.markRead();
        var pageable = PageRequest.of(0, 20);
        when(repository.findByRecipientId(2L, pageable))
                .thenReturn(new PageImpl<>(java.util.List.of(notification), pageable, 1));

        var result = service.mine(claimant, pageable, false);

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().getFirst().read()).isTrue();
        assertThat(result.content().getFirst().reportId()).isEqualTo(10L);
        assertThat(result.content().getFirst().claimId()).isEqualTo(20L);
    }

    @Test
    void mineFiltersToUnreadWhenUnreadOnly() throws Exception {
        LostFoundClaim claim = claim(report(owner, 10L), claimant, 20L);
        LostFoundNotification notification = notification(claimant, NotificationType.CLAIM_APPROVED, claim, 30L);
        var pageable = PageRequest.of(0, 20);
        when(repository.findByRecipientIdAndReadAtIsNull(2L, pageable))
                .thenReturn(new PageImpl<>(java.util.List.of(notification), pageable, 1));

        var result = service.mine(claimant, pageable, true);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().read()).isFalse();
    }

    @Test
    void unreadCountForRecipient() {
        when(repository.countByRecipientIdAndReadAtIsNull(2L)).thenReturn(3L);

        assertThat(service.unreadCount(claimant)).isEqualTo(3L);
    }

    @Test
    void markReadUpdatesOwnedNotification() throws Exception {
        LostFoundClaim claim = claim(report(owner, 10L), claimant, 20L);
        LostFoundNotification notification = notification(claimant, NotificationType.CLAIM_APPROVED, claim, 30L);
        when(repository.findByIdAndRecipientId(30L, 2L)).thenReturn(Optional.of(notification));
        when(repository.save(notification)).thenReturn(notification);

        var result = service.markRead(30L, claimant);

        assertThat(result.read()).isTrue();
        verify(repository).save(notification);
    }

    private LostFoundNotification notification(
            User recipient,
            NotificationType type,
            LostFoundClaim claim,
            Long id) throws Exception {
        LostFoundNotification notification = new LostFoundNotification(
                recipient,
                type,
                claim.getReport(),
                claim,
                "Title",
                "Message");
        setField(notification, "id", id);
        setField(notification, "createdAt", Instant.now());
        return notification;
    }

    private User user(Long id, String email) throws Exception {
        User user = new User(email, "encoded");
        setField(user, "id", id);
        return user;
    }

    private LostFoundReport report(User creator, Long id) throws Exception {
        LostFoundReport report = new LostFoundReport(
                ReportType.FOUND,
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
        setField(claim, "id", id);
        setField(claim, "createdAt", Instant.now());
        setField(claim, "updatedAt", Instant.now());
        return claim;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
