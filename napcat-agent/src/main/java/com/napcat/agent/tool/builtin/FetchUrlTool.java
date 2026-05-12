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
import java.util.Locale;

/**
 * 内置 HTTP 抓取工具，获取指定 URL 的内容。
 * LLM 可调用此工具读取网页、下载图片/文件/视频等资源。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "napcat.agent.builtin.fetch-url", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FetchUrlTool {

    private static final int MAX_CONTENT_LENGTH = 4000;
    private static final int MAX_BINARY_SIZE_MB = 50;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 获取指定 URL 的内容。支持网页文本、图片、视频、文件等任意资源。
     *
     * @param url 要请求的 URL
     * @return 网页文本内容（截断到 {@value #MAX_CONTENT_LENGTH} 字符）或资源描述信息
     */
    @Tool(
        name = "fetch_url",
        description = "获取指定 URL 的内容。支持网页、图片、文件、视频等任意资源。当用户消息中包含任何链接时必须调用此工具，禁止自行推测链接内容。"
    )
    public String fetch(
        @ToolParam(value = "url", description = "URL地址", required = true) String url
    ) {
        if (!isAllowedUrl(url)) {
            return "获取失败：仅支持访问公开的 HTTP/HTTPS 网址，禁止访问内网或本地地址。";
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return "获取失败：HTTP " + response.statusCode();
            }

            byte[] bodyBytes = response.body();
            if (bodyBytes == null || bodyBytes.length == 0) {
                return "获取的内容为空。";
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);

            // 二进制资源：图片、视频、音频、文件等
            if (isBinaryContent(contentType)) {
                return describeBinaryResource(url, contentType, bodyBytes.length);
            }

            // 文本资源：网页、JSON、XML、纯文本等
            String charset = extractCharset(contentType);
            String body = charset != null ? new String(bodyBytes, charset) : new String(bodyBytes);

            if (body.isBlank()) {
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
     * 校验 URL 是否允许访问（仅限公开 HTTP/HTTPS，禁止内网/本地地址）。
     */
    private boolean isAllowedUrl(String urlStr) {
        try {
            URI uri = URI.create(urlStr);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) return false;
            String lowerHost = host.toLowerCase();
            if ("localhost".equals(lowerHost)) return false;
            boolean isIp = lowerHost.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$");
            if (isIp) {
                if (lowerHost.startsWith("127.") || lowerHost.startsWith("10.")
                        || lowerHost.startsWith("192.168.") || lowerHost.startsWith("169.254.")) return false;
                if (lowerHost.equals("0.0.0.0")) return false;
                if (lowerHost.startsWith("172.")) {
                    String[] parts = lowerHost.split("\\.");
                    if (parts.length >= 2) {
                        try {
                            int second = Integer.parseInt(parts[1]);
                            if (second >= 16 && second <= 31) return false;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (lowerHost.equals("::1") || lowerHost.startsWith("fe80:")
                    || lowerHost.startsWith("fc") || lowerHost.startsWith("fd")) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断 Content-Type 是否为二进制资源
     */
    private boolean isBinaryContent(String contentType) {
        return contentType.startsWith("image/")
                || contentType.startsWith("video/")
                || contentType.startsWith("audio/")
                || contentType.equals("application/octet-stream")
                || contentType.equals("application/pdf")
                || contentType.equals("application/zip")
                || contentType.equals("application/x-zip-compressed")
                || contentType.equals("application/gzip")
                || contentType.equals("application/x-rar-compressed")
                || contentType.equals("application/msword")
                || contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || contentType.equals("application/vnd.ms-excel")
                || contentType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || contentType.equals("application/vnd.ms-powerpoint")
                || contentType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    /**
     * 生成二进制资源的描述信息
     */
    private String describeBinaryResource(String url, String contentType, int sizeBytes) {
        double sizeMb = sizeBytes / (1024.0 * 1024.0);
        String type = contentType.split(";")[0].trim();
        String typeDesc;
        
        if (type.startsWith("image/")) {
            typeDesc = "图片";
        } else if (type.startsWith("video/")) {
            typeDesc = "视频";
        } else if (type.startsWith("audio/")) {
            typeDesc = "音频";
        } else if (type.equals("application/pdf")) {
            typeDesc = "PDF 文档";
        } else if (type.equals("application/zip") || type.equals("application/x-zip-compressed")) {
            typeDesc = "ZIP 压缩包";
        } else if (type.equals("application/gzip")) {
            typeDesc = "GZIP 压缩包";
        } else if (type.equals("application/x-rar-compressed")) {
            typeDesc = "RAR 压缩包";
        } else if (type.equals("application/msword") || type.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            typeDesc = "Word 文档";
        } else if (type.equals("application/vnd.ms-excel") || type.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
            typeDesc = "Excel 表格";
        } else if (type.equals("application/vnd.ms-powerpoint") || type.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")) {
            typeDesc = "PPT 演示文稿";
        } else {
            typeDesc = "文件";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📎 这是一个 ").append(typeDesc).append("。\n");
        sb.append("URL：").append(url).append("\n");
        sb.append("Content-Type：").append(contentType).append("\n");
        sb.append("大小：");
        if (sizeMb >= 1) {
            sb.append(String.format("%.2f MB", sizeMb));
        } else if (sizeBytes >= 1024) {
            sb.append(String.format("%.2f KB", sizeBytes / 1024.0));
        } else {
            sb.append(sizeBytes).append(" B");
        }

        if (sizeMb > MAX_BINARY_SIZE_MB) {
            sb.append("\n⚠️ 文件过大（超过 ").append(MAX_BINARY_SIZE_MB).append(" MB），不建议下载。");
        }

        if (contentType.startsWith("image/")) {
            sb.append("\n💡 提示：该资源为图片，当前模型无法直接查看图片内容。如需分析图片，请使用支持多模态的模型。");
        } else if (contentType.startsWith("video/")) {
            sb.append("\n💡 提示：该资源为视频文件，当前模型无法直接播放或分析视频内容。");
        } else if (contentType.startsWith("audio/")) {
            sb.append("\n💡 提示：该资源为音频文件，当前模型无法直接播放或转录音频内容。");
        }

        return sb.toString();
    }

    /**
     * 从 Content-Type 中提取字符集
     */
    private String extractCharset(String contentType) {
        if (contentType == null || contentType.isEmpty()) return null;
        int idx = contentType.indexOf("charset=");
        if (idx == -1) return null;
        String charset = contentType.substring(idx + 8).trim();
        if (charset.startsWith("\"") && charset.endsWith("\"")) {
            charset = charset.substring(1, charset.length() - 1);
        }
        return charset.isEmpty() ? null : charset;
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
