package com.app.campusagent.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat Core 配置属性（前缀 app.chat）。
 *
 * <p>对应 application.properties 中的 {@code app.chat.*} 配置段。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

    /** 编排层（Python LangGraph）基础 URL。 */
    private String orchestrationBaseUrl;

    /** 编排层共享密钥（HMAC 签名，来自 .env → AGENT_SHARED_SECRET）。 */
    private String sharedSecret;

    /** Delegation Token 有效期（秒）。 */
    private long delegationTokenTtlSeconds = 30;

    /** RSA 密钥持久化目录。 */
    private String delegationKeyDir = "./keys";

    /** 服务注册表（agentName → baseUrl）。 */
    private Map<String, String> agents = new LinkedHashMap<>();

    public String getOrchestrationBaseUrl() {
        return orchestrationBaseUrl;
    }

    public void setOrchestrationBaseUrl(String orchestrationBaseUrl) {
        this.orchestrationBaseUrl = orchestrationBaseUrl;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public long getDelegationTokenTtlSeconds() {
        return delegationTokenTtlSeconds;
    }

    public void setDelegationTokenTtlSeconds(long delegationTokenTtlSeconds) {
        this.delegationTokenTtlSeconds = delegationTokenTtlSeconds;
    }

    public String getDelegationKeyDir() {
        return delegationKeyDir;
    }

    public void setDelegationKeyDir(String delegationKeyDir) {
        this.delegationKeyDir = delegationKeyDir;
    }

    public Map<String, String> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, String> agents) {
        this.agents = agents;
    }
}
