package com.app.campusagent.facilities.config;

import com.app.campusagent.facilities.domain.Space;
import com.app.campusagent.facilities.domain.SpaceStatus;
import com.app.campusagent.facilities.domain.SpaceType;
import com.app.campusagent.facilities.repository.SpaceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

@Component
public class FacilityDataInitializer implements CommandLineRunner {

    private final SpaceRepository spaceRepository;

    public FacilityDataInitializer(SpaceRepository spaceRepository) {
        this.spaceRepository = spaceRepository;
    }

    /** Adds deterministic demo spaces without overwriting spaces maintained by other modules. */
    @Override
    public void run(String... args) {
        seedSpaces().stream()
                .filter(space -> !spaceRepository.existsByName(space.getName()))
                .forEach(spaceRepository::save);
    }

    private List<Space> seedSpaces() {
        return List.of(
                space("COM2-03-12 Study Room", "COM2", "03", "03-12", SpaceType.STUDY_ROOM, 4,
                        Set.of("projector", "whiteboard", "power_socket")),
                space("COM2-03-13 Study Room", "COM2", "03", "03-13", SpaceType.STUDY_ROOM, 6,
                        Set.of("monitor", "whiteboard", "power_socket")),
                space("COM2-04-01 Seminar Room", "COM2", "04", "04-01", SpaceType.SEMINAR_ROOM, 12,
                        Set.of("projector", "whiteboard", "video_conf", "power_socket")),
                space("COM3-01-20 Project Room", "COM3", "01", "01-20", SpaceType.STUDY_ROOM, 8,
                        Set.of("projector", "monitor", "whiteboard", "power_socket")),
                space("COM3-02-15 Computer Lab", "COM3", "02", "02-15", SpaceType.LAB, 30,
                        Set.of("computer", "projector", "whiteboard", "power_socket")),
                space("Central Library Discussion Pod A", "CENTRAL_LIBRARY", "03", "POD-A", SpaceType.STUDY_ROOM, 4,
                        Set.of("monitor", "whiteboard", "power_socket")),
                space("Central Library Discussion Pod B", "CENTRAL_LIBRARY", "03", "POD-B", SpaceType.STUDY_ROOM, 6,
                        Set.of("projector", "whiteboard", "power_socket")),
                space("Central Library Seminar Room", "CENTRAL_LIBRARY", "05", "05-01", SpaceType.SEMINAR_ROOM, 16,
                        Set.of("projector", "video_conf", "whiteboard", "power_socket")),
                space("UTown Study Room 1", "UTOWN", "02", "SR-1", SpaceType.STUDY_ROOM, 5,
                        Set.of("whiteboard", "power_socket")),
                space("UTown Study Room 2", "UTOWN", "02", "SR-2", SpaceType.STUDY_ROOM, 10,
                        Set.of("projector", "whiteboard", "power_socket")),
                space("Engineering Auditorium", "EA", "01", "AUD", SpaceType.LECTURE_ROOM, 120,
                        Set.of("projector", "microphone", "video_conf", "power_socket")),
                space("Science Teaching Lab", "S16", "03", "03-05", SpaceType.LAB, 24,
                        Set.of("computer", "monitor", "projector", "power_socket")),
                space("MPSH Basketball Court 1", "MPSH", "01", "COURT-1", SpaceType.SPORTS_VENUE, 20,
                        Set.of("scoreboard")),
                space("Sports Hall Badminton Court", "USC", "01", "COURT-3", SpaceType.SPORTS_VENUE, 8,
                        Set.of("scoreboard")),
                new Space("COM1-02-02 Renovation Room", "COM1", "02", "02-02", SpaceType.SEMINAR_ROOM, 10,
                        Set.of("projector", "whiteboard"), LocalTime.of(8, 0), LocalTime.of(22, 0),
                        SpaceStatus.OUT_OF_SERVICE)
        );
    }

    private Space space(String name, String building, String floor, String roomNumber,
                        SpaceType type, int capacity, Set<String> equipment) {
        return new Space(name, building, floor, roomNumber, type, capacity, equipment,
                LocalTime.of(8, 0), LocalTime.of(22, 0), SpaceStatus.AVAILABLE);
    }
}
