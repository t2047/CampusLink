package com.app.campusagent.config;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.super-admin.email}")
    private String superAdminEmail;

    @Value("${app.super-admin.password}")
    private String superAdminPassword;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(superAdminEmail)) {
            log.info("SUPER_ADMIN account already exists: {}", superAdminEmail);
            return;
        }

        User superAdmin = new User(superAdminEmail, passwordEncoder.encode(superAdminPassword));
        superAdmin.setRole(Role.SUPER_ADMIN);
        userRepository.save(superAdmin);

        log.info("SUPER_ADMIN account created: {}", superAdminEmail);
    }
}
