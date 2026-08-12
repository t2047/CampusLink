package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.StagedImageResponse;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyResponse;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyWebRequest;
import com.app.campusagent.lostfound.dto.agent.AgentWebInvokeRequest;
import com.app.campusagent.lostfound.dto.agent.AgentWebSearchRequest;
import com.app.campusagent.lostfound.service.LostFoundAgentGateway;
import com.app.campusagent.lostfound.service.LostFoundImageStagingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 为已登录 Web 用户提供不暴露共享密钥的 Agent 测试入口。 */
@RestController
@RequestMapping("/api/lost-found/agent")
public class LostFoundAgentWebController {

    private final LostFoundAgentGateway agentGateway;
    private final LostFoundImageStagingService stagingService;

    public LostFoundAgentWebController(
            LostFoundAgentGateway agentGateway,
            LostFoundImageStagingService stagingService) {
        this.agentGateway = agentGateway;
        this.stagingService = stagingService;
    }

    /** Agent 面板图片暂存：登录用户上传单张图片，返回 objectKey / 指纹 / 回显 URL。 */
    @PostMapping("/upload-image")
    public StagedImageResponse uploadImage(@RequestParam("image") MultipartFile file) {
        return stagingService.upload(file);
    }

    @PostMapping("/invoke")
    public Map<String, Object> invoke(
            @Valid @RequestBody AgentWebInvokeRequest request,
            @AuthenticationPrincipal User currentUser) {
        return agentGateway.invoke(request, currentUser);
    }

    @PostMapping("/classify")
    public AgentClassifyResponse classify(
            @Valid @RequestBody AgentClassifyWebRequest request,
            @AuthenticationPrincipal User currentUser) {
        return agentGateway.classify(request, currentUser);
    }

    @PostMapping("/search")
    public Map<String, Object> search(
            @Valid @RequestBody AgentWebSearchRequest request,
            @AuthenticationPrincipal User currentUser) {
        return agentGateway.search(request, currentUser);
    }
}
