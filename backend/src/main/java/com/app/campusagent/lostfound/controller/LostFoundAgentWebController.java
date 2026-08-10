package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.agent.AgentWebInvokeRequest;
import com.app.campusagent.lostfound.service.LostFoundAgentGateway;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 为已登录 Web 用户提供不暴露共享密钥的 Agent 测试入口。 */
@RestController
@RequestMapping("/api/lost-found/agent")
public class LostFoundAgentWebController {

    private final LostFoundAgentGateway agentGateway;

    public LostFoundAgentWebController(LostFoundAgentGateway agentGateway) {
        this.agentGateway = agentGateway;
    }

    @PostMapping("/invoke")
    public Map<String, Object> invoke(
            @Valid @RequestBody AgentWebInvokeRequest request,
            @AuthenticationPrincipal User currentUser) {
        return agentGateway.invoke(request, currentUser);
    }
}
