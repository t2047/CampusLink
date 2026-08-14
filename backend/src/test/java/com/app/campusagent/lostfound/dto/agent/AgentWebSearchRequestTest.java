package com.app.campusagent.lostfound.dto.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentWebSearchRequestTest {

    @Test
    void mapsCamelCaseToSnakeCaseAndOmitsEmptyFields() {
        AgentWebSearchRequest request = new AgentWebSearchRequest(
                "FOUND",
                " 耳机 ",
                null,
                "black",
                null,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 11),
                List.of(new AgentWebInvokeRequest.AgentImage(
                        "lost-found-staging/k.png",
                        "VF1:fp",
                        "/api/lost-found/images/staging/k.png")));

        Map<String, Object> payload = request.toAgentPayload();

        assertEquals("FOUND", payload.get("report_type"));
        assertEquals("耳机", payload.get("keyword"));
        assertEquals("black", payload.get("colour"));
        assertEquals("2026-08-01", payload.get("date_from"));
        assertEquals("2026-08-11", payload.get("date_to"));
        assertFalse(payload.containsKey("category"));
        assertFalse(payload.containsKey("location"));

        List<?> images = (List<?>) payload.get("images");
        assertEquals(1, images.size());
        Map<?, ?> image = (Map<?, ?>) images.get(0);
        assertEquals("lost-found-staging/k.png", image.get("object_key"));
        assertEquals("VF1:fp", image.get("visual_fingerprint"));
    }
}
