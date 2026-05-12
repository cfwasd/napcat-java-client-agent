package com.napcat.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.napcat.agent.session.SessionKey;
import com.napcat.core.annotation.Tool;
import com.napcat.core.annotation.ToolParam;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ToolRegistry {

    private final Map<String, ToolMethod> tools = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final ThreadLocal<SessionKey> currentSessionKey = new ThreadLocal<>();

    public void register(Object bean) {
        Class<?> clazz = bean.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            Tool toolAnn = method.getAnnotation(Tool.class);
            if (toolAnn == null) continue;

            method.setAccessible(true);
            ToolMethod tm = new ToolMethod(toolAnn.name(), toolAnn.description(), bean, method);
            tools.put(toolAnn.name(), tm);
            log.debug("Registered tool: {}", toolAnn.name());
        }
    }

    public List<ToolSchema> getSchemas() {
        List<ToolSchema> schemas = new ArrayList<>();
        for (ToolMethod tm : tools.values()) {
            schemas.add(buildSchema(tm));
        }
        return schemas;
    }

    /**
     * 调用工具（兼容旧接口，但不提供 SessionKey）
     * @deprecated 使用 {@link #invoke(String, String, SessionKey)}
     */
    @Deprecated
    public Object invoke(String name, String argumentsJson) {
        return invoke(name, argumentsJson, null);
    }

    /**
     * 调用工具（推荐方式，传入 SessionKey）
     */
    public Object invoke(String name, String argumentsJson, SessionKey sessionKey) {
        ToolMethod tm = tools.get(name);
        if (tm == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        
        if (sessionKey != null) {
            currentSessionKey.set(sessionKey);
        }
        
        try {
            if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
                log.warn("Empty arguments for tool: {}", name);
                return tm.getMethod().invoke(tm.getBean(), new Object[tm.getMethod().getParameterCount()]);
            }

            Map<String, Object> args;
            try {
                args = mapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception jsonEx) {
                log.error("Invalid JSON arguments for tool '{}': {}\nError: {}",
                        name, argumentsJson, jsonEx.getMessage());

                String fixedJson = tryFixJson(argumentsJson);
                if (fixedJson != null) {
                    try {
                        args = mapper.readValue(fixedJson, new TypeReference<Map<String, Object>>() {});
                        log.info("Successfully fixed JSON for tool '{}': {}", name, fixedJson);
                    } catch (Exception fixEx) {
                        log.error("Failed to fix JSON for tool '{}': {}", name, fixEx.getMessage());
                        return "Error: Invalid tool arguments format. Expected valid JSON but got: " + argumentsJson;
                    }
                } else {
                    args = extractAndMapParameters(name, argumentsJson, tm);
                    if (args == null) {
                        return "Error: Invalid tool arguments format. Expected valid JSON but got: " + argumentsJson;
                    }
                    log.info("Extracted and mapped parameters for tool '{}': {}", name, args);
                }
            }

            Parameter[] params = tm.getMethod().getParameters();
            Object[] argsArray = new Object[params.length];

            for (int i = 0; i < params.length; i++) {
                Parameter param = params[i];
                ToolParam tp = param.getAnnotation(ToolParam.class);
                if (tp != null) {
                    // 优先使用 paramKey（value() 或 description()）作为 JSON key
                    String key = paramKey(tp);
                    Object value = args.get(key);

                    // 如果通过 key 没找到，尝试使用 Java 参数名
                    if (value == null && param.isNamePresent() && !param.getName().equals(key)) {
                        value = args.get(param.getName());
                    }

                    // 仅在未显式设置 value() 时回退到模糊匹配（向后兼容）
                    if (value == null && (tp.value() == null || tp.value().isBlank())) {
                        value = findValueByFuzzyMatch(args, tp.description());
                    }

                    argsArray[i] = convertType(value, param.getType());
                } else {
                    argsArray[i] = null;
                }
            }

            return tm.getMethod().invoke(tm.getBean(), argsArray);
        } catch (Exception e) {
            log.error("Tool invocation error: {}", name, e);
            return "Error: " + e.getMessage();
        } finally {
            if (sessionKey != null) {
                currentSessionKey.remove();
            }
        }
    }
    
    public static SessionKey getCurrentSessionKey() {
        return currentSessionKey.get();
    }

    private Map<String, Object> extractAndMapParameters(String toolName, String invalidJson, ToolMethod tm) {
        try {
            java.util.regex.Matcher urlMatcher = java.util.regex.Pattern.compile("(https?://[^\\s\"'}\\]]+)").matcher(invalidJson);
            
            Parameter[] params = tm.getMethod().getParameters();
            if (params.length == 1) {
                if (urlMatcher.find()) {
                    String url = urlMatcher.group(1);
                    Map<String, Object> mappedArgs = new HashMap<>();
                    ToolParam tp = params[0].getAnnotation(ToolParam.class);
                    if (tp != null) {
                        mappedArgs.put(paramKey(tp), url);
                        if (params[0].isNamePresent()) {
                            mappedArgs.put(params[0].getName(), url);
                        }
                    }
                    return mappedArgs;
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            java.util.regex.Matcher kvMatcher = java.util.regex.Pattern.compile("\"([^\"]+)\":\\s*\"([^\"]+)\"").matcher(invalidJson);
            while (kvMatcher.find()) {
                result.put(kvMatcher.group(1), kvMatcher.group(2));
            }
            
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.debug("Failed to extract parameters from invalid JSON: {}", invalidJson);
            return null;
        }
    }

    private String tryFixJson(String invalidJson) {
        if (invalidJson == null || invalidJson.trim().isEmpty()) {
            return null;
        }

        String json = invalidJson.trim();

        try {
            mapper.readTree(json);
            return json;
        } catch (Exception e) {
            // 继续尝试修复
        }

        json = json.replaceAll("(?<=\\{|,)\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", " \"$1\":");
        json = json.replaceAll("\"([^\"]{0,50}?):\\s*\"([^\"]+)\"", "\"$1\": \"$2\"");
        
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"[^\"]*?(https?://[^\"}]+)\"").matcher(json);
        if (matcher.find()) {
            String url = matcher.group(1);
            json = "{\"url\": \"" + url + "\"}";
        }

        if (!json.startsWith("{")) {
            json = "{" + json;
        }
        if (!json.endsWith("}")) {
            json = json + "}";
        }

        try {
            mapper.readTree(json);
            return json;
        } catch (Exception e) {
            log.debug("Could not fix JSON: {}", invalidJson);
            return null;
        }
    }

    private Object findValueByFuzzyMatch(Map<String, Object> args, String targetKey) {
        if (args == null || targetKey == null) {
            return null;
        }

        String normalizedTarget = normalizeKey(targetKey);

        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey());

            if (normalizedTarget.equals(normalizedKey)) {
                return entry.getValue();
            }

            if (normalizedTarget.contains(normalizedKey) || normalizedKey.contains(normalizedTarget)) {
                return entry.getValue();
            }

            String simplifiedTarget = removeCommonWords(normalizedTarget);
            String simplifiedKey = removeCommonWords(normalizedKey);
            if (simplifiedTarget.equals(simplifiedKey) ||
                    simplifiedTarget.contains(simplifiedKey) ||
                    simplifiedKey.contains(simplifiedTarget)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private String normalizeKey(String key) {
        if (key == null) return "";
        return key.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }

    private String removeCommonWords(String text) {
        if (text == null) return "";

        String[] commonWords = {"指定", "要", "的", "如", "例如", "默认", "可选", "必填",
                "required", "optional", "default", "such as", "for example"};

        String result = text;
        for (String word : commonWords) {
            result = result.replace(word, "").replaceAll("\\s+", " ").trim();
        }

        return result;
    }

    /**
     * 获取 @ToolParam 的有效 JSON key。
     * 优先使用 value()（短标识符），未设置时退回 description()（向后兼容）。
     */
    private static String paramKey(ToolParam tp) {
        String v = tp.value();
        return (v != null && !v.isBlank()) ? v : tp.description();
    }

    private ToolSchema buildSchema(ToolMethod tm) {
        ToolSchema schema = new ToolSchema();
        schema.setName(tm.getName());
        schema.setDescription(tm.getDescription());

        Map<String, ToolSchema.ParameterSchema> params = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : tm.getMethod().getParameters()) {
            ToolParam tp = param.getAnnotation(ToolParam.class);
            if (tp == null) continue;

            ToolSchema.ParameterSchema ps = new ToolSchema.ParameterSchema();
            ps.setType(tp.type());
            ps.setDescription(tp.description());
            if (tp.enums().length > 0) {
                ps.setEnums(Arrays.asList(tp.enums()));
            }
            String key = paramKey(tp);
            params.put(key, ps);
            if (tp.required()) {
                required.add(key);
            }
        }

        schema.setParameters(params);
        schema.setRequired(required);
        return schema;
    }

    private Object convertType(Object value, Class<?> type) {
        if (value == null) return null;
        if (type.isInstance(value)) return value;
        if (type == String.class) return value.toString();
        if (type == int.class || type == Integer.class) {
            if (value instanceof Number) return ((Number) value).intValue();
            return Integer.parseInt(value.toString());
        }
        if (type == long.class || type == Long.class) {
            if (value instanceof Number) return ((Number) value).longValue();
            return Long.parseLong(value.toString());
        }
        if (type == double.class || type == Double.class) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            return Double.parseDouble(value.toString());
        }
        if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Boolean) return value;
            String s = value.toString().trim().toLowerCase();
            return "true".equals(s) || "1".equals(s) || "yes".equals(s);
        }
        return value;
    }
}
