package com.app.campusagent.facilities;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FacilitiesSchemaIsolationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void facilitiesTablesExistOnlyInFacilitySchema() {
        List<String> facilityTables = jdbcTemplate.queryForList("""
                select table_name
                from information_schema.tables
                where table_schema = 'facility'
                """, String.class);

        assertThat(facilityTables).containsExactlyInAnyOrder(
                "spaces", "space_equipment", "bookings", "maintenance_tickets");
        assertThat(facilityTables).doesNotContain("users");
    }

    @Test
    void userRemainsInMainSchemaAndLegacyTablesAreAbsent() {
        List<String> userSchemas = jdbcTemplate.queryForList("""
                select table_schema
                from information_schema.tables
                where table_name = 'users'
                  and table_schema <> 'information_schema'
                """, String.class);

        Integer legacyTableCount = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_name in (
                    'facility_spaces',
                    'facility_space_equipment',
                    'facility_bookings',
                    'facility_maintenance_tickets'
                )
                """, Integer.class);

        assertThat(userSchemas).containsExactly("public");
        assertThat(legacyTableCount).isZero();
    }
}
