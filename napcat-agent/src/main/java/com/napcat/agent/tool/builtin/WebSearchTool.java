package com.napcat.agent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.napcat.core.annotation.Tool;
import com.napcat.core.annotation.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置联网搜索工具，基于自建的 SearxNG 搜索引擎（免费、无需 Key）。
 * LLM 可调用此工具搜索实时信息。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "napcat.agent.builtin.web-search", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebSearchTool {

    private static final int MAX_RESULTS = 5;

    @Value("${napcat.agent.builtin.web-search.instance:http://154.201.84.30:18080}")
    private String searxInstance;

    @Value("${napcat.agent.builtin.web-search.result-count:5}")
    private int resultCount;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 搜索互联网，返回相关结果摘要。
     *
     * @param query 搜索关键词
     * @return 格式化的搜索结果
     */
    @Tool(
            name = "web_search",
            description = "搜索互联网获取最新信息。当需要实时数据、新闻或 LLM 训练截止后的信息时使用。"
    )
    public String search(
            @ToolParam(value = "query", description = "搜索关键词或问题", required = true) String query
    ) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = searxInstance + "/search?q=" + encoded
                    + "&format=json"
                    + "&categories=general"
                    + "&language=zh-CN"
                    + "&pageno=1"
                    + "&per_page=" + Math.min(resultCount, MAX_RESULTS);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("SearxNG returned status code: {}", response.statusCode());
                return "搜索失败：HTTP " + response.statusCode();
            }

            String body = response.body();

            if (body == null || body.trim().isEmpty()) {
                return "未找到关于 \"" + query + "\" 的搜索结果。";
            }

            if (body.trim().startsWith("<")) {
                log.error("SearxNG returned HTML instead of JSON");
                return "搜索接口返回异常，请稍后重试";
            }

            JsonNode root = mapper.readTree(body);
            List<String> results = new ArrayList<>();

            JsonNode resultsArray = root.path("results");

            if (!resultsArray.isArray() || resultsArray.size() == 0) {
                return "未找到关于 \"" + query + "\" 的搜索结果。";
            }

            // ... existing code ...
            int count = 0;
            for (JsonNode result : resultsArray) {
                if (count >= MAX_RESULTS) break;

                String title = result.path("title").asText("");
                String content = result.path("content").asText("");
                String resultUrl = result.path("url").asText("");
                String engine = result.path("engine").asText("");

                if (!title.isEmpty()) {
                    count++;
                    StringBuilder resultStr = new StringBuilder();
                    resultStr.append(String.format("%d. **%s**", count, title));

                    if (!content.isEmpty()) {
                        resultStr.append("\n   ").append(content);
                    }

                    if (!resultUrl.isEmpty()) {
                        resultStr.append("\n   🔗 ").append(resultUrl);
                    }

                    if (!engine.isEmpty()) {
                        resultStr.append("\n   📊 来源: ").append(engine);
                    }

                    results.add(resultStr.toString());
                }
            }

            if (results.isEmpty()) {
                return "未找到关于 \"" + query + "\" 的搜索结果。";
            }

            return "🔍 搜索 \"" + query + "\" 的结果：\n\n" + String.join("\n\n", results);

        } catch (Exception e) {
            log.error("Web search failed for query: {}", query, e);
            return "搜索出错：" + e.getMessage();
        }
    }
}
