package com.sap.abap.ai.completion.preferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置的模型厂商（AI 服务提供商）及其默认 API Base URL 与推荐模型。
 *
 * <p>“AI 连接”配置页的“模型厂商”下拉列表由 {@link #all()} 填充。选择某个厂商后，
 * 页面会自动把该厂商的 {@link #baseUrl} / {@link #model} 写入右侧表单；
 * 用户仍可手工修改 URL 与模型名称，此时厂商选择会切换为 {@link #CUSTOM}（Custom）。</p>
 *
 * <p>厂商名称同时作为持久化标识写入 AI 连接条目的 {@code vendor} 字段。</p>
 */
public final class AIProvider {

    /** 表示“手动填写”，不自动套用任何默认 URL / 模型。 */
    public static final String CUSTOM = "Custom";

    /** 厂商名称（持久化标识）。 */
    public final String name;
    /** 该厂商的默认 API Base URL（OpenAI 兼容端点）。 */
    public final String baseUrl;
    /** 该厂商的推荐模型名称。 */
    public final String model;

    private AIProvider(String name, String baseUrl, String model) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /** 是否为“手动填写”项。 */
    public boolean isCustom() {
        return CUSTOM.equals(name);
    }

    /** 下拉列表中显示的名称（Custom 按插件语言本地化，其余为厂商品牌名）。 */
    public String displayName() {
        return isCustom() ? Messages.get("connPage.vendor.custom") : name;
    }

    private static final List<AIProvider> ALL = buildList();

    private static List<AIProvider> buildList() {
        List<AIProvider> list = new ArrayList<>();
        list.add(new AIProvider("Agnes",
                "https://api.agnes-ai.cn/v1", "agnes-2.5-flash"));
        list.add(new AIProvider("Anthropic (Claude)",
                "https://api.anthropic.com/v1", "claude-sonnet-4-6"));
        list.add(new AIProvider("Cerebras",
                "https://api.cerebras.ai/v1", "gpt-oss-120b"));
        list.add(new AIProvider("DeepSeek",
                "https://api.deepseek.com", "deepseek-flash"));
        list.add(new AIProvider("Google (Gemini)",
                "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.5-flash-lite"));
        list.add(new AIProvider("Groq",
                "https://api.groq.com/openai/v1", "openai/gpt-oss-20b"));
        list.add(new AIProvider("MiniMax",
                "https://api.minimax.cn/v1", "MiniMax-M3"));
        list.add(new AIProvider("Mistral",
                "https://codestral.mistral.ai/v1", "codestral-latest"));
        list.add(new AIProvider("Moonshot (Kimi)",
                "https://api.moonshot.ai/v1", "kimi-k2.7-code"));
        list.add(new AIProvider("OpenAI",
                "https://api.openai.com/v1", "gpt-5-codex"));
        list.add(new AIProvider("OpenRouter",
                "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5"));
        list.add(new AIProvider("SiliconFlow",
                "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-Coder-32B-Instruct"));
        list.add(new AIProvider("Together AI",
                "https://api.together.ai/v1", "openai/gpt-oss-120b"));
        list.add(new AIProvider("xAI (Grok)",
                "https://api.x.ai/v1", "grok-4.7"));
        list.add(new AIProvider("智谱 AI (GLM)",
                "https://open.bigmodel.cn/api/coding/paas/v4", "glm-5.2"));
        list.add(new AIProvider(CUSTOM, "", ""));
        return Collections.unmodifiableList(list);
    }

    /** 全部厂商（含末尾的 Custom）。 */
    public static List<AIProvider> all() {
        return ALL;
    }

    /** 按持久化名称查找厂商；找不到时返回 {@link #CUSTOM} 项。 */
    public static AIProvider byName(String name) {
        if (name != null) {
            for (AIProvider p : ALL) {
                if (p.name.equals(name)) return p;
            }
        }
        return byName(CUSTOM);
    }

    /**
     * 依据 Base URL 推断厂商（用于尚未保存过 vendor 的旧连接条目）。
     * 未匹配到任何内置厂商时返回 {@link #CUSTOM}。
     */
    public static AIProvider infer(String baseUrl) {
        String normalized = normalizeUrl(baseUrl);
        if (!normalized.isEmpty()) {
            for (AIProvider p : ALL) {
                if (!p.isCustom() && normalized.equals(normalizeUrl(p.baseUrl))) {
                    return p;
                }
            }
        }
        return byName(CUSTOM);
    }

    private static String normalizeUrl(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
