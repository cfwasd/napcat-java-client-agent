package com.napcat.agent.tool.builtin;

import com.napcat.core.annotation.Tool;
import com.napcat.core.annotation.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 内置 HTTP 抓取工具，获取指定 URL 的文本内容。
 * LLM 可调用此工具读取网页内容。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "napcat.agent.builtin.fetch-url", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FetchUrlTool {

    private static final int MAX_CONTENT_LENGTH = 4000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 获取指定 URL 的内容（仅文本）。
     *
     * @param url 要请求的 URL
     * @return 网页文本内容（截断到 {@value #MAX_CONTENT_LENGTH} 字符）
     */
    @Tool(
        name = "fetch_url",
        description = "获取指定 URL 的网页文本内容。当需要阅读某个链接的详细内容时使用。"
    )
    public String fetch(
        @ToolParam(description = "URL地址", required = true) String url
    ) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "获取失败：HTTP " + response.statusCode();
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                return "获取的内容为空。";
            }

            // 检测是否为 WAF 防护页面或验证码页面
            if (isWafOrChallengePage(body)) {
                return "⚠️ 该网站启用了安全防护（WAF），无法直接获取内容。\n建议：\n1. 尝试其他信息来源\n2. 使用搜索引擎查找相关内容摘要\n3. 手动访问该链接查看内容";
            }

            // 简单去 HTML 标签，提取纯文本
            String plainText = body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                    .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("&quot;", "\"")
                    .replaceAll("\\s+", " ")
                    .trim();

            // 检测提取后是否有有效内容
            if (plainText.length() < 50 || isMeaninglessContent(plainText)) {
                return "⚠️ 该页面没有可提取的有效文本内容，可能是动态加载页面或受保护页面。\n建议使用 web_search 工具搜索相关信息。";
            }

            if (plainText.length() > MAX_CONTENT_LENGTH) {
                plainText = plainText.substring(0, MAX_CONTENT_LENGTH) + "\n…(内容过长，已截断)";
            }

            return "📄 " + url + " 的内容：\n\n" + plainText;
        } catch (Exception e) {
            log.error("Fetch URL failed: {}", url, e);
            return "获取 URL 出错：" + e.getMessage();
        }
    }

    /**
     * 检测是否为 WAF 防护页面或挑战页面
     */
    private boolean isWafOrChallengePage(String html) {
        if (html == null || html.isEmpty()) {
            return false;
        }

        String lowerHtml = html.toLowerCase();

        // 检测常见的 WAF 特征
        String[] wafIndicators = {
            "_waf_",
            "cloudflare",
            "incapsula",
            "akamai",
            "f5 networks",
            "citrix",
            "imperva",
            "ddos protection",
            "security check",
            "verify you are human",
            "checking your browser",
            "please wait while we verify",
            "javascript challenge",
            "__cf_chl",
            "cf-browser-verification"
        };

        for (String indicator : wafIndicators) {
            if (lowerHtml.contains(indicator)) {
                log.debug("Detected WAF/challenge page by indicator: {}", indicator);
                return true;
            }
        }

        // 检测是否包含大量 Base64 编码数据（通常是 WAF 挑战代码）
        int base64PatternCount = countBase64Patterns(html);
        if (base64PatternCount > 3) {
            log.debug("Detected WAF page by Base64 patterns count: {}", base64PatternCount);
            return true;
        }

        // 检测是否几乎没有可见文本内容（大部分是脚本）
        double scriptRatio = calculateScriptRatio(html);
        if (scriptRatio > 0.8) {
            log.debug("Detected challenge page by script ratio: {}", scriptRatio);
            return true;
        }

        return false;
    }

    /**
     * 计算 Base64 模式的数量
     */
    private int countBase64Patterns(String html) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[A-Za-z0-9+/]{100,}={0,2}");
        java.util.regex.Matcher matcher = pattern.matcher(html);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 计算脚本内容占比
     */
    private double calculateScriptRatio(String html) {
        String withoutScripts = html.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "");
        int originalLength = html.replaceAll("<[^>]+>", "").length();
        int withoutScriptsLength = withoutScripts.replaceAll("<[^>]+>", "").length();
        
        if (originalLength == 0) {
            return 0;
        }
        
        return 1.0 - ((double) withoutScriptsLength / originalLength);
    }

    /**
     * 检测是否为无意义内容
     */
    private boolean isMeaninglessContent(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }

        // 检测是否主要是特殊字符或编码数据
        int specialCharCount = 0;
        int totalChars = Math.min(text.length(), 500);
        
        for (int i = 0; i < totalChars; i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c) &&
                    !("。，、；：！？【】（）《》「」—…".indexOf(c) == -1)) {
                specialCharCount++;
            }
        }

        double specialCharRatio = (double) specialCharCount / totalChars;
        return specialCharRatio > 0.5;
    }
}
