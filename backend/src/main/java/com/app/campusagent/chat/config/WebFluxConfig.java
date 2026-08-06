package com.app.campusagent.chat.config;

/**
 * Minimal WebFlux configuration.
 *
 * <p>This application uses Spring MVC as the primary web stack with
 * reactive return types ({@code Flux}, {@code Mono}) for the SSE Chat API.
 * No {@code @EnableWebFlux} is required — the reactive return types are
 * natively supported by Spring MVC 6.x when spring-webflux is on the
 * classpath.</p>
 *
 * <p>This class exists as a documentation anchor.  If the project migrates
 * to full WebFlux in the future, add {@code @EnableWebFlux} here.</p>
 */
public final class WebFluxConfig {
    private WebFluxConfig() {
        // utility class — no instances
    }
}
