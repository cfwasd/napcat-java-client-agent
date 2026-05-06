package com.napcat.core.handler;

import com.napcat.core.annotation.*;
import com.napcat.core.config.BotProperties;
import com.napcat.core.event.*;
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
    private final Map<String, CommandEntry> commands = new ConcurrentHashMap<>();
    private final Map<Class<? extends OB11Event>, List<Consumer<OB11Event>>> eventHandlers = new ConcurrentHashMap<>();

    public HandlerRegistry(BotProperties properties) {
        this.properties = properties;
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
        commands.put(template, new CommandEntry(template, handler, filter));
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
            String template = properties.getCommandPrefix() + commandOpt.get().value();
            CommandEntry entry = new CommandEntry(template, (event, args) -> invokeMethod(bean, method, event, args), createFilter(annotations));
            commands.put(template, entry);
            log.debug("Registered command handler: {} on method {}", template, method.getName());
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
                if (OB11Event.class.isAssignableFrom(param.getType())) {
                    argsArray[i] = event;
                } else if (CommandHandler.CommandArgs.class.isAssignableFrom(param.getType())) {
                    argsArray[i] = args;
                } else if (param.isAnnotationPresent(Param.class)) {
                    String key = param.getAnnotation(Param.class).value();
                    String value = args.get(key);
                    argsArray[i] = convertType(value, param.getType());
                } else {
                    argsArray[i] = null;
                }
            }
            Object result = method.invoke(bean, argsArray);
            handleReturnValue(result, event);
        } catch (Exception e) {
            log.error("Method invoke error: {}.{}", bean.getClass().getName(), method.getName(), e);
        }
    }

    private void invokeMethod(Object bean, Method method, OB11Event event) {
        try {
            Parameter[] params = method.getParameters();
            Object[] argsArray = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (params[i].getType().isInstance(event)) {
                    argsArray[i] = event;
                } else {
                    argsArray[i] = null;
                }
            }
            Object result = method.invoke(bean, argsArray);
            handleReturnValue(result, event);
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
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == long.class || type == Long.class) return Long.parseLong(value);
        if (type == double.class || type == Double.class) return Double.parseDouble(value);
        if (type == boolean.class || type == Boolean.class) {
            return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public List<HandlerResult> dispatch(OB11Event event) {
        List<HandlerResult> results = new ArrayList<>();

        // 1. 命令匹配（消息事件）
        if (event instanceof MessageEvent msgEvent) {
            String plainText = msgEvent.getPlainText();
            for (CommandEntry entry : commands.values()) {
                if (entry.filter != null && !entry.filter.test(msgEvent)) continue;
                CommandHandler.CommandArgs args = matchCommand(entry.template, plainText);
                if (args != null) {
                    try {
                        entry.handler.accept(msgEvent, args);
                        results.add(new HandlerResult(true, null));
                    } catch (Exception e) {
                        log.error("Command handler error", e);
                        results.add(new HandlerResult(false, e));
                    }
                    return results;
                }
            }
        }

        // 2. 注解 handler 匹配
        handlers.stream()
                .filter(h -> h.eventType.isInstance(event))
                .filter(h -> h.condition == null || h.condition.test(event))
                .sorted(Comparator.comparingInt(HandlerEntry::priority))
                .forEach(h -> {
                    try {
                        ((Consumer<OB11Event>) h.executor).accept(event);
                        results.add(new HandlerResult(true, null));
                    } catch (Exception e) {
                        log.error("Handler error", e);
                        results.add(new HandlerResult(false, e));
                    }
                });

        // 3. 接口 handler 匹配
        List<Consumer<OB11Event>> consumers = eventHandlers.get(event.getClass());
        if (consumers != null) {
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

        Optional<RoleFilter> roleOpt = annotations.stream()
                .filter(a -> a instanceof RoleFilter)
                .map(a -> (RoleFilter) a)
                .findFirst();
        if (roleOpt.isPresent()) {
            RoleFilter.Role role = roleOpt.get().value();
            filters.add(e -> {
                if (!(e instanceof GroupMessageEvent ge)) return false;
                Sender sender = ge.getSender();
                return switch (role) {
                    case OWNER -> sender.isOwner();
                    case ADMIN -> sender.isAdmin();
                    case SUPERUSER -> properties.getSuperUsers().contains(ge.getUserId());
                    default -> true;
                };
            });
        }

        return filters.isEmpty() ? null : e -> filters.stream().allMatch(f -> f.test(e));
    }

    private Predicate<MessageEvent> createFilter(Set<Annotation> annotations) {
        return e -> true;
    }

    private int calculatePriority(Set<Annotation> annotations) {
        if (annotations.stream().anyMatch(a -> a instanceof Command)) return 1;
        if (annotations.stream().anyMatch(a -> a instanceof MentionFilter)) return 10;
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
    }

    public record HandlerResult(boolean success, Throwable error) {}
}
