package com.napcat.core.handler;

import com.napcat.core.annotation.*;
import com.napcat.core.config.BotProperties;
import com.napcat.core.event.*;
import com.napcat.core.exception.StopRoutingException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class HandlerRegistry implements BotDispatcher {

    private final BotProperties properties;
    private final List<HandlerEntry<?>> handlers = new ArrayList<>();
    private final Map<String, List<CommandEntry>> commands = new ConcurrentHashMap<>();
    private final Map<Class<? extends OB11Event>, List<Consumer<OB11Event>>> eventHandlers = new ConcurrentHashMap<>();
    private final List<CommandHelp> commandHelps = new ArrayList<>();

    /** 当事件未被任何 handler 处理时的兜底回调（用于 at-me-trigger 等场景） */
    private Consumer<OB11Event> fallbackHandler;

    public HandlerRegistry(BotProperties properties) {
        this.properties = properties;
    }

    /**
     * 设置兜底 handler。当事件经过命令/注解/接口全部匹配后无任何 handler 命中时调用。
     */
    public void setFallbackHandler(Consumer<OB11Event> fallbackHandler) {
        this.fallbackHandler = fallbackHandler;
    }

    @Override
    public void onGroupMessage(Consumer<GroupMessageEvent> handler) {
        eventHandlers.computeIfAbsent(GroupMessageEvent.class, k -> new ArrayList<>())
                .add(e -> handler.accept((GroupMessageEvent) e));
    }

    @Override
    public void onPrivateMessage(Consumer<PrivateMessageEvent> handler) {
        eventHandlers.computeIfAbsent(PrivateMessageEvent.class, k -> new ArrayList<>())
                .add(e -> handler.accept((PrivateMessageEvent) e));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onEvent(Class<? extends OB11Event> type, Consumer<OB11Event> handler) {
        eventHandlers.computeIfAbsent((Class<? extends OB11Event>) type, k -> new ArrayList<>())
                .add(handler);
    }

    @Override
    public void registerCommand(String template, BiConsumer<MessageEvent, CommandHandler.CommandArgs> handler) {
        registerCommand(template, handler, e -> true);
    }

    @Override
    public void registerCommand(String template, BiConsumer<MessageEvent, CommandHandler.CommandArgs> handler, Predicate<MessageEvent> filter) {
        commands.computeIfAbsent(template, k -> new ArrayList<>())
                .add(new CommandEntry(template, handler, filter, null));
    }

    public void registerBean(Object bean) {
        registerBean(bean, bean.getClass());
    }

    public void registerBean(Object bean, Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            registerAnnotatedMethod(bean, method);
        }
    }

    @SuppressWarnings("unchecked")
    public void registerEventHandler(EventHandler<?> handler) {
        Class<?> eventType = handler.getEventType();
        onEvent((Class<? extends OB11Event>) eventType, e -> {
            try {
                ((EventHandler<OB11Event>) handler).handle(e);
            } catch (Exception ex) {
                log.error("Event handler error", ex);
            }
        });
    }

    public void registerCommandHandler(CommandHandler handler) {
        registerCommand(handler.getCommand(), (event, args) -> {
            if (handler instanceof CommandHandler.FilterableCommandHandler) {
                if (!((CommandHandler.FilterableCommandHandler) handler).filter(event)) {
                    return;
                }
            }
            handler.handle(event, args);
        });
    }

    public void registerInitializer(BotInitializer initializer) {
        initializer.initialize(this);
    }

    private void registerAnnotatedMethod(Object bean, Method method) {
        // 收集所有注解（包括元注解）
        Set<Annotation> annotations = getMergedAnnotations(method);

        boolean hasGroupMessage = annotations.stream().anyMatch(a -> a instanceof OnGroupMessage);
        boolean hasPrivateMessage = annotations.stream().anyMatch(a -> a instanceof OnPrivateMessage);
        boolean hasNotice = annotations.stream().anyMatch(a -> a instanceof OnNotice);
        boolean hasRequest = annotations.stream().anyMatch(a -> a instanceof OnRequest);
        boolean hasMeta = annotations.stream().anyMatch(a -> a instanceof OnMetaEvent);

        Optional<Command> commandOpt = annotations.stream()
                .filter(a -> a instanceof Command)
                .map(a -> (Command) a)
                .findFirst();

        boolean hasMention = annotations.stream().anyMatch(a -> a instanceof MentionFilter);

        if (!hasGroupMessage && !hasPrivateMessage && !hasNotice && !hasRequest && !hasMeta) {
            return;
        }

        method.setAccessible(true);

        if (commandOpt.isPresent()) {
            Command cmd = commandOpt.get();
            String template = properties.getCommandPrefix() + cmd.value();
            Predicate<Object> eventFilter = createEventFilter(annotations);
            Predicate<MessageEvent> msgFilter = eventFilter == null ? null :
                    e -> eventFilter.test(e);
            Class<? extends MessageEvent> eventType = null;
            if (hasGroupMessage && !hasPrivateMessage) eventType = GroupMessageEvent.class;
            if (hasPrivateMessage && !hasGroupMessage) eventType = PrivateMessageEvent.class;
            CommandEntry entry = new CommandEntry(template,
                    (event, args) -> invokeMethod(bean, method, event, args), msgFilter, eventType);
            commands.computeIfAbsent(template, k -> new ArrayList<>()).add(entry);
            commandHelps.add(new CommandHelp(template, cmd.description(), cmd.adminOnly()));
            log.debug("Registered command handler: {} on method {} type={}", template, method.getName(), eventType);
            return;
        }

        int priority = calculatePriority(annotations);
        Predicate<Object> condition = createEventFilter(annotations);

        if (hasGroupMessage) {
            handlers.add(new HandlerEntry<>(GroupMessageEvent.class, priority, condition, e -> invokeMethod(bean, method, e)));
        }
        if (hasPrivateMessage) {
            handlers.add(new HandlerEntry<>(PrivateMessageEvent.class, priority, condition, e -> invokeMethod(bean, method, e)));
        }
    }

    private void invokeMethod(Object bean, Method method, OB11Event event, CommandHandler.CommandArgs args) {
        try {
            Parameter[] params = method.getParameters();
            Object[] argsArray = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Parameter param = params[i];
                Class<?> paramType = param.getType();
                
                if (OB11Event.class.isAssignableFrom(paramType)) {
                    if (paramType.isInstance(event)) {
                        argsArray[i] = event;
                    } else {
                        log.warn("Event type mismatch: expected {}, got {}. Setting to null.", 
                                paramType.getSimpleName(), event.getClass().getSimpleName());
                        argsArray[i] = null;
                    }
                } else if (CommandHandler.CommandArgs.class.isAssignableFrom(paramType)) {
                    argsArray[i] = args;
                } else if (param.isAnnotationPresent(Param.class)) {
                    String key = param.getAnnotation(Param.class).value();
                    String value = args.get(key);
                    try {
                        argsArray[i] = convertType(value, paramType);
                    } catch (Exception e) {
                        log.error("Failed to convert parameter '{}' with value '{}' to type {}: {}", 
                                key, value, paramType.getSimpleName(), e.getMessage());
                        argsArray[i] = null;
                    }
                } else {
                    argsArray[i] = null;
                }
            }
            Object result = method.invoke(bean, argsArray);
            handleReturnValue(result, event);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof StopRoutingException) {
                throw (StopRoutingException) cause;
            }
            log.error("Method invoke error: {}.{}", bean.getClass().getName(), method.getName(), ite);
        } catch (Exception e) {
            log.error("Method invoke error: {}.{}", bean.getClass().getName(), method.getName(), e);
        }
    }

    private void invokeMethod(Object bean, Method method, OB11Event event) {
        try {
            Parameter[] params = method.getParameters();
            Object[] argsArray = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Parameter param = params[i];
                Class<?> paramType = param.getType();
                
                if (OB11Event.class.isAssignableFrom(paramType)) {
                    if (paramType.isInstance(event)) {
                        argsArray[i] = event;
                    } else {
                        log.warn("Event type mismatch: expected {}, got {}. Setting to null.", 
                                paramType.getSimpleName(), event.getClass().getSimpleName());
                        argsArray[i] = null;
                    }
                } else {
                    argsArray[i] = null;
                }
            }
            Object result = method.invoke(bean, argsArray);
            handleReturnValue(result, event);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof StopRoutingException) {
                throw (StopRoutingException) cause;
            }
            log.error("Method invoke error: {}.{}", bean.getClass().getName(), method.getName(), ite);
        } catch (Exception e) {
            log.error("Method invoke error: {}.{}", bean.getClass().getName(), method.getName(), e);
        }
    }

    private void handleReturnValue(Object result, OB11Event event) {
        if (result == null) return;
        if (event instanceof MessageEvent msgEvent) {
            if (result instanceof String text) {
                msgEvent.reply(text);
            } else if (result instanceof com.napcat.core.message.MessageChain chain) {
                msgEvent.reply(chain);
            }
        }
    }

    private Object convertType(String value, Class<?> type) {
        if (value == null) return null;
        if (type == String.class) return value;
        if (type == int.class || type == Integer.class) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse integer from '{}': {}", value, e.getMessage());
                return null;
            }
        }
        if (type == long.class || type == Long.class) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse long from '{}': {}", value, e.getMessage());
                return null;
            }
        }
        if (type == double.class || type == Double.class) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse double from '{}': {}", value, e.getMessage());
                return null;
            }
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public List<HandlerResult> dispatch(OB11Event event) {
        List<HandlerResult> results = new ArrayList<>();
        
        // 只记录非心跳事件的调度信息，且使用 DEBUG 级别
        if (!(event instanceof com.napcat.core.event.HeartbeatEvent)) {
            log.debug("Dispatching event: class={}, handlers={}, commands={}",
                    event.getClass().getSimpleName(), handlers.size(), commands.size());
        }

        // 1. 命令匹配（消息事件）
        if (event instanceof MessageEvent msgEvent) {
            String plainText = msgEvent.getPlainText().trim();
            
            if (log.isDebugEnabled()) {
                if (msgEvent instanceof GroupMessageEvent groupEvent) {
                    String senderName = groupEvent.getSender() != null ? groupEvent.getSender().getNickname() : "未知";
                    log.debug("群聊 [{}] [{}({})] {}", groupEvent.getGroupId(), senderName, groupEvent.getUserId(), plainText);
                } else if (msgEvent instanceof com.napcat.core.event.PrivateMessageEvent privateEvent) {
                    String senderName = privateEvent.getSender() != null ? privateEvent.getSender().getNickname() : "未知";
                    log.debug("私聊 [{}({})] {}", senderName, privateEvent.getUserId(), plainText);
                }
            }

            // 命令匹配逻辑
            boolean commandMatched = false;
            for (List<CommandEntry> entries : commands.values()) {
                for (CommandEntry entry : entries) {
                    CommandHandler.CommandArgs args = matchCommand(entry.template, plainText);
                    if (args != null) {
                        log.debug("命令匹配: 模板='{}', 消息='{}'", entry.template, plainText);
                        commandMatched = true;
                        try {
                            entry.handler.accept(msgEvent, args);
                            results.add(new HandlerResult(true, null));
                        } catch (StopRoutingException sre) {
                            results.add(new HandlerResult(true, null));
                            return results;
                        } catch (Exception e) {
                            log.error("Command handler error", e);
                            results.add(new HandlerResult(false, e));
                        }
                        // 命令匹配成功，默认阻止后续 handler
                        return results;
                    }
                }
            }

        }

        // 2. 注解 handler 匹配
        List<HandlerEntry<?>> matchedHandlers = handlers.stream()
                .filter(h -> h.eventType.isInstance(event))
                .filter(h -> h.condition == null || h.condition.test(event))
                .sorted(Comparator.comparingInt(HandlerEntry::priority))
                .toList();
        
        if (!matchedHandlers.isEmpty() && !(event instanceof com.napcat.core.event.HeartbeatEvent)) {
            log.debug("Matched annotation handlers: count={}", matchedHandlers.size());
        }
        
        for (HandlerEntry<?> h : matchedHandlers) {
            try {
                if (!(event instanceof com.napcat.core.event.HeartbeatEvent)) {
                    log.debug("Executing annotation handler: eventType={}, priority={}", 
                            h.eventType.getSimpleName(), h.priority);
                }
                ((Consumer<OB11Event>) h.executor).accept(event);
                results.add(new HandlerResult(true, null));
            } catch (StopRoutingException sre) {
                log.debug("Annotation handler stopped routing");
                results.add(new HandlerResult(true, null));
                break;
            } catch (Exception e) {
                log.error("Handler error", e);
                results.add(new HandlerResult(false, e));
            }
        }

        // 3. 接口 handler 匹配
        List<Consumer<OB11Event>> consumers = eventHandlers.get(event.getClass());
        if (consumers != null && !consumers.isEmpty()) {
            if (!(event instanceof com.napcat.core.event.HeartbeatEvent)) {
                log.debug("Matched interface handlers: count={}", consumers.size());
            }
            consumers.forEach(c -> {
                try {
                    c.accept(event);
                    results.add(new HandlerResult(true, null));
                } catch (Exception e) {
                    log.error("Interface handler error", e);
                    results.add(new HandlerResult(false, e));
                }
            });
        }

        // 4. 兜底 handler（at-me-trigger 等）
        if (results.isEmpty() && fallbackHandler != null) {
            if (!(event instanceof com.napcat.core.event.HeartbeatEvent)) {
                log.debug("No handler matched, invoking fallback handler");
            }
            try {
                fallbackHandler.accept(event);
                results.add(new HandlerResult(true, null));
            } catch (Exception e) {
                log.error("Fallback handler error", e);
                results.add(new HandlerResult(false, e));
            }
        }

        if (!(event instanceof com.napcat.core.event.HeartbeatEvent) && !results.isEmpty()) {
            log.debug("Dispatch completed: successCount={}, totalHandlers={}",
                    results.stream().filter(HandlerResult::success).count(), results.size());
        }
        return results;
    }

    private CommandHandler.CommandArgs matchCommand(String template, String input) {
        // 解析模板：/天气 {city} {days}
        List<String> paramNames = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf('{', i);
            if (start == -1) {
                regex.append(Pattern.quote(template.substring(i)));
                break;
            }
            regex.append(Pattern.quote(template.substring(i, start)));
            int end = template.indexOf('}', start);
            if (end == -1) return null;
            String paramName = template.substring(start + 1, end);
            paramNames.add(paramName);
            regex.append("(.+)");
            i = end + 1;
        }
        regex.append("$");

        Pattern pattern = Pattern.compile(regex.toString());
        Matcher matcher = pattern.matcher(input);
        if (!matcher.matches()) return null;

        Map<String, String> args = new HashMap<>();
        for (int j = 0; j < paramNames.size(); j++) {
            args.put(paramNames.get(j), matcher.group(j + 1).trim());
        }
        return new CommandHandler.CommandArgs(args);
    }

    private Set<Annotation> getMergedAnnotations(Method method) {
        Set<Annotation> result = new HashSet<>();
        for (Annotation ann : method.getAnnotations()) {
            collectMeta(ann, result);
        }
        return result;
    }

    private void collectMeta(Annotation ann, Set<Annotation> result) {
        if (!result.add(ann)) return;
        for (Annotation meta : ann.annotationType().getAnnotations()) {
            if (meta.annotationType().getName().startsWith("java.lang.annotation")) continue;
            collectMeta(meta, result);
        }
    }

    private Predicate<Object> createEventFilter(Set<Annotation> annotations) {
        List<Predicate<Object>> filters = new ArrayList<>();

        Optional<MentionFilter> mentionOpt = annotations.stream()
                .filter(a -> a instanceof MentionFilter)
                .map(a -> (MentionFilter) a)
                .findFirst();
        if (mentionOpt.isPresent()) {
            filters.add(e -> e instanceof MessageEvent msg &&
                    msg.getMessage().isAt(properties.getSelfId()));
        }

        Optional<WakeFilter> wakeOpt = annotations.stream()
                .filter(a -> a instanceof WakeFilter)
                .map(a -> (WakeFilter) a)
                .findFirst();
        if (wakeOpt.isPresent()) {
            filters.add(e -> e instanceof MessageEvent msg &&
                    properties.matchesWakeWord(msg.getPlainText()));
        }

        Optional<RoleFilter> roleOpt = annotations.stream()
                .filter(a -> a instanceof RoleFilter)
                .map(a -> (RoleFilter) a)
                .findFirst();
        if (roleOpt.isPresent()) {
            RoleFilter.Role role = roleOpt.get().value();
            filters.add(e -> {
                if (!(e instanceof MessageEvent msg)) return false;
                long userId = msg.getUserId();
                if (e instanceof GroupMessageEvent ge) {
                    Sender sender = ge.getSender();
                    return switch (role) {
                        case OWNER -> sender.isOwner();
                        case ADMIN -> sender.isAdmin();
                        case SUPERUSER -> properties.getSuperUsers().contains(userId);
                        default -> true;
                    };
                }
                // 私聊场景：仅 SUPERUSER 生效
                return role == RoleFilter.Role.SUPERUSER
                        && properties.getSuperUsers().contains(userId);
            });
        }

        return filters.isEmpty() ? null : e -> filters.stream().allMatch(f -> f.test(e));
    }

    private int calculatePriority(Set<Annotation> annotations) {
        int min = 100;
        for (Annotation ann : annotations) {
            int p = extractPriority(ann);
            if (p < min) {
                min = p;
            }
        }
        return min;
    }

    private int extractPriority(Annotation ann) {
        try {
            Method m = ann.annotationType().getMethod("priority");
            if (m.getReturnType() == int.class) {
                return (int) m.invoke(ann);
            }
        } catch (Exception ignored) {
        }
        return 100;
    }

    @Data
    private static class HandlerEntry<E> {
        private final Class<E> eventType;
        private final int priority;
        private final Predicate<Object> condition;
        private final Consumer<E> executor;

        public int priority() {
            return priority;
        }
    }

    @Data
    private static class CommandEntry {
        private final String template;
        private final BiConsumer<MessageEvent, CommandHandler.CommandArgs> handler;
        private final Predicate<MessageEvent> filter;
        /** 命令绑定的事件类型；null 表示不限制（同时标注了 @OnGroupMessage 和 @OnPrivateMessage 时也视为 null） */
        private final Class<? extends MessageEvent> eventType;
    }

    public record HandlerResult(boolean success, Throwable error) {}

    /**
     * 获取已注册的命令帮助列表。
     * @param isAdmin true 返回全部命令（含管理员命令），false 仅返回普通命令
     */
    public List<CommandHelp> getHelpCommands(boolean isAdmin) {
        return commandHelps.stream()
                .filter(c -> isAdmin || !c.adminOnly())
                .toList();
    }

    public record CommandHelp(String template, String description, boolean adminOnly) {}
}
