/**
 * 失物招领 Agent 面板 Web 控制器（REST 控制器）。
 *
 * 主要作用与职责：
 *  1. 为已登录的 Web 用户提供"Agent 测试面板 / 调试入口"所需的一组 REST 端点，
 *     统一以 /api/lost-found/agent 作为前缀。与内部控制器（LostFoundAgentInternalController）
 *     的区别是：这里不要求 AGENT_LOST_FOUND 角色，也没有共享密钥校验，
 *     只要求用户已登录（@AuthenticationPrincipal 拿到当前登录用户），适合面板手动触发测试。
 *  2. 端点覆盖：
 *      - POST /upload-image    Agent 面板图片暂存上传（返回 objectKey / 指纹 / 回显 URL）
 *      - POST /invoke          触发 Agent 的一次对话/工具调用
 *      - POST /classify        对物品描述做智能分类
 *      - POST /search          触发联网/知识库搜索
 *  3. 具体逻辑委托给 LostFoundAgentGateway（Agent 网关）与 LostFoundImageStagingService（图片暂存）。
 */
package com.app.campusagent.lostfound.controller;

// —— 领域模型与 DTO ——
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.StagedImageResponse;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyResponse;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyWebRequest;
import com.app.campusagent.lostfound.dto.agent.AgentWebInvokeRequest;
import com.app.campusagent.lostfound.dto.agent.AgentWebSearchRequest;
// —— Agent 网关与图片暂存服务 ——
import com.app.campusagent.lostfound.service.LostFoundAgentGateway;
import com.app.campusagent.lostfound.service.LostFoundImageStagingService;
// —— 校验、登录用户注入与 Web 注解 ——
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
// —— JDK 工具类 ——
import java.util.Map;

/** 为已登录 Web 用户提供不暴露共享密钥的 Agent 测试入口。 */
@RestController
@RequestMapping("/api/lost-found/agent")
public class LostFoundAgentWebController {

    // Agent 网关：封装 Agent 的 invoke / classify / search 三类调用
    private final LostFoundAgentGateway agentGateway;
    // 图片暂存服务：负责 Agent 面板上传图片的暂存、指纹计算与回显
    private final LostFoundImageStagingService stagingService;

    // 构造器同时注入 Agent 网关与图片暂存服务（Spring 构造器依赖注入）
    public LostFoundAgentWebController(
            LostFoundAgentGateway agentGateway,
            LostFoundImageStagingService stagingService) {
        this.agentGateway = agentGateway;
        this.stagingService = stagingService;
    }

    /** Agent 面板图片暂存：登录用户上传单张图片，返回 objectKey / 指纹 / 回显 URL。 */
    @PostMapping("/upload-image")
    public StagedImageResponse uploadImage(
            @RequestParam("image") MultipartFile file, // multipart 表单中的图片文件
            @AuthenticationPrincipal User currentUser) { // 当前登录用户（用于归属校验）
        // 委托暂存服务上传图片，返回带 objectKey / 指纹 / 回显 URL 的响应
        return stagingService.upload(file, currentUser);
    }

    /**
     * POST /api/lost-found/agent/invoke
     * 触发 Agent 的一次调用（对话/工具执行）。
     * 入参：request 为 Agent 调用请求体（@Valid 校验）；currentUser 为当前登录用户。
     * 返回：Map<String,Object> 不固定结构的调用结果（供面板展示）。
     * 调用方：Agent 面板的"调用测试"入口。
     */
    @PostMapping("/invoke")
    public Map<String, Object> invoke(
            @Valid @RequestBody AgentWebInvokeRequest request, // Agent 调用请求体
            @AuthenticationPrincipal User currentUser) {       // 当前登录用户
        // 委托 Agent 网关执行调用并原样返回结果
        return agentGateway.invoke(request, currentUser);
    }

    /**
     * POST /api/lost-found/agent/classify
     * 对物品描述做智能分类（如识别物品种类、颜色等）。
     * 入参：request 为分类请求体（@Valid 校验）；currentUser 为当前登录用户。
     * 返回：AgentClassifyResponse 结构化分类结果。
     * 调用方：Agent 面板的"分类测试"入口。
     */
    @PostMapping("/classify")
    public AgentClassifyResponse classify(
            @Valid @RequestBody AgentClassifyWebRequest request, // 分类请求体
            @AuthenticationPrincipal User currentUser) {         // 当前登录用户
        // 委托 Agent 网关执行分类并返回结果
        return agentGateway.classify(request, currentUser);
    }

    /**
     * POST /api/lost-found/agent/search
     * 触发 Agent 的联网/知识库搜索。
     * 入参：request 为搜索请求体（@Valid 校验）；currentUser 为当前登录用户。
     * 返回：Map<String,Object> 搜索结果（来源列表等）。
     * 调用方：Agent 面板的"搜索测试"入口。
     */
    @PostMapping("/search")
    public Map<String, Object> search(
            @Valid @RequestBody AgentWebSearchRequest request, // 搜索请求体
            @AuthenticationPrincipal User currentUser) {       // 当前登录用户
        // 委托 Agent 网关执行搜索并返回结果
        return agentGateway.search(request, currentUser);
    }
}
