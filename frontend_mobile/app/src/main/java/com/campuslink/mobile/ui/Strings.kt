package com.campuslink.mobile.ui

import com.campuslink.mobile.core.settings.AppLanguage

data class UiStrings(
    val welcome: String,
    val subtitle: String,
    val email: String,
    val password: String,
    val confirmPassword: String,
    val login: String,
    val register: String,
    val switchToRegister: String,
    val switchToLogin: String,
    val conversations: String,
    val newChat: String,
    val noConversations: String,
    val typeMessage: String,
    val send: String,
    val stop: String,
    val settings: String,
    val logout: String,
    val clearHistory: String,
    val darkMode: String,
    val language: String,
    val confirmAction: String,
    val approve: String,
    val cancel: String,
    val agentSteps: String,
    val matches: String,
    val delete: String,
    val retry: String,
    val dismiss: String,
    val switchLanguage: String,
    val showActivity: String,
    val hideActivity: String,
)

fun strings(language: AppLanguage): UiStrings = if (language == AppLanguage.CHINESE) {
    UiStrings(
        "欢迎使用 CampusLink", "登录后使用校园 AI 助手和服务", "邮箱", "密码", "确认密码",
        "登录", "注册", "没有账号？注册", "已有账号？登录", "对话", "新对话", "还没有对话",
        "输入消息…", "发送", "停止", "设置", "退出登录", "清除本地记录", "深色模式", "语言",
        "需要确认操作", "确认", "取消", "Agent 执行过程", "匹配结果", "删除", "重试", "关闭",
        "English", "展开执行过程", "收起执行过程",
    )
} else {
    UiStrings(
        "Welcome to CampusLink", "Sign in to use the campus AI assistant and services", "Email", "Password",
        "Confirm password", "Login", "Register", "New here? Register", "Already registered? Login",
        "Conversations", "New chat", "No conversations yet", "Type a message…", "Send", "Stop", "Settings",
        "Logout", "Clear local history", "Dark mode", "Language", "Action confirmation required", "Confirm",
        "Cancel", "Agent activity", "Match results", "Delete", "Retry", "Dismiss",
        "中文", "Show activity", "Hide activity",
    )
}
