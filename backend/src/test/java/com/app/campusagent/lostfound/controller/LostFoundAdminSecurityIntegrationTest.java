package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import com.app.campusagent.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LostFoundAdminSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LostFoundReportRepository reportRepository;

    @Autowired
    private LostFoundClaimRepository claimRepository;

    @Test
    void rejectsAnonymousUsers() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void rejectsStudents() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/overview"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void allowsAdministratorsToViewOverview() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReports").isNumber())
                .andExpect(jsonPath("$.submittedClaims").isNumber())
                .andExpect(jsonPath("$.hiddenReports").isNumber());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void allowsSuperAdministratorsToViewReports() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/reports")
                        .param("page", "0")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void rejectsAnonymousUsersFromAdminWriteActions() throws Exception {
        mockMvc.perform(post("/api/admin/lost-found/reports/1/delist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/lost-found/reports/1/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void rejectsStudentsFromAdminWriteActions() throws Exception {
        mockMvc.perform(post("/api/admin/lost-found/reports/1/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsAdministratorToDelistReport() throws Exception {
        User owner = userRepository.save(new User("owner-sec@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-sec@campuslink.com"));
        LostFoundReport report = reportRepository.save(report(owner));

        mockMvc.perform(post("/api/admin/lost-found/reports/{id}/delist", report.getId())
                        .with(authentication(principal(admin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Inappropriate content\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminHidden").value(true));
    }

    @Test
    void allowsSuperAdministratorToDeleteReport() throws Exception {
        User owner = userRepository.save(new User("owner-sec-delete@u.nus.edu", "encoded"));
        User superAdmin = userRepository.save(adminUser("super-sec@campuslink.com"));
        superAdmin.setRole(Role.SUPER_ADMIN);
        LostFoundReport report = reportRepository.save(report(owner));

        mockMvc.perform(post("/api/admin/lost-found/reports/{id}/delete", report.getId())
                        .with(authentication(principal(superAdmin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Community guidelines violation\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsDelistWithBlankReason() throws Exception {
        User owner = userRepository.save(new User("owner-sec-blank@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-sec-blank@campuslink.com"));
        LostFoundReport report = reportRepository.save(report(owner));

        mockMvc.perform(post("/api/admin/lost-found/reports/{id}/delist", report.getId())
                        .with(authentication(principal(admin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void exposesAuditLogsToAdministrator() throws Exception {
        User admin = userRepository.save(adminUser("admin-audit-sec@campuslink.com"));

        mockMvc.perform(get("/api/admin/lost-found/audit-logs")
                        .param("page", "0")
                        .param("size", "25")
                        .with(authentication(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void rejectsAnonymousUsersFromClaimsEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/claims"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void rejectsStudentsFromClaimsEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/lost-found/claims"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsAdministratorToBrowseClaims() throws Exception {
        User admin = userRepository.save(adminUser("admin-claims-sec@campuslink.com"));

        mockMvc.perform(get("/api/admin/lost-found/claims")
                        .param("page", "0")
                        .param("size", "25")
                        .with(authentication(principal(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void allowsAdministratorToApproveClaim() throws Exception {
        User owner = userRepository.save(new User("owner-sec-claim@u.nus.edu", "encoded"));
        User admin = userRepository.save(adminUser("admin-sec-claim@campuslink.com"));
        User claimant = userRepository.save(new User("claimant-sec-claim@u.nus.edu", "encoded"));
        LostFoundReport report = reportRepository.save(report(owner));
        LostFoundClaim claim = claimRepository.save(new LostFoundClaim(
                report, claimant, "The item has a private identifying mark."));
        claimRepository.flush();

        mockMvc.perform(post("/api/admin/lost-found/claims/{id}/approve", claim.getId())
                        .with(authentication(principal(admin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNote\":\"Verified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.report.status").value("CLAIMED"));
    }

    @Test
    void rejectsStudentsFromApprovingClaim() throws Exception {
        User owner = userRepository.save(new User("owner-sec-approve@u.nus.edu", "encoded"));
        User claimant = userRepository.save(new User("claimant-sec-approve@u.nus.edu", "encoded"));
        LostFoundReport report = reportRepository.save(report(owner));
        LostFoundClaim claim = claimRepository.save(new LostFoundClaim(
                report, claimant, "My proof."));
        claimRepository.flush();

        mockMvc.perform(post("/api/admin/lost-found/claims/{id}/approve", claim.getId())
                        .with(authentication(principal(claimant)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionNote\":\"Verified\"}"))
                .andExpect(status().isForbidden());
    }

    private LostFoundReport report(User owner) {
        return new LostFoundReport(
                ReportType.FOUND,
                "Black Headphones",
                ItemCategory.ELECTRONICS,
                "Wireless headphones with a scratched case.",
                "Black",
                "Central Library",
                LocalDate.now().minusDays(1),
                "Afternoon",
                owner);
    }

    private User adminUser(String email) {
        User admin = new User(email, "encoded");
        admin.setRole(Role.ADMIN);
        return userRepository.save(admin);
    }

    private UsernamePasswordAuthenticationToken principal(User user) {
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }
}
