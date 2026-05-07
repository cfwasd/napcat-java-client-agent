package com.napcat.core.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EventDecoder {

    private final ObjectMapper mapper;

    public EventDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 从已解析的 JsonNode 解码事件，与 {@link #decode(String)} 逻辑一致但避免重复解析。
     */
    public OB11Event decode(JsonNode root) {
        try {
            String postType = getString(root, "post_type");
            if (postType == null) {
                return null;
            }
            return decodeByPostType(root, postType);
        } catch (Exception e) {
            log.error("Failed to decode event from node", e);
            return null;
        }
    }

    public OB11Event decode(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String postType = getString(root, "post_type");
            if (postType == null) {
                log.debug("Decoded as non-event (no post_type): {}", json);
                return null;
            }
            return decodeByPostType(root, postType);
        } catch (Exception e) {
            log.error("Failed to decode event: {}", json, e);
            return null;
        }
    }

    private OB11Event decodeByPostType(JsonNode root, String postType) throws Exception {
        OB11Event event = switch (postType) {
            case "message" -> decodeMessage(root);
            case "notice" -> decodeNotice(root);
            case "request" -> decodeRequest(root);
            case "meta_event" -> decodeMeta(root);
            default -> {
                log.warn("Unknown post_type '{}', falling back to generic OB11Event", postType);
                yield mapper.treeToValue(root, OB11Event.class);
            }
        };
        if (event != null) {
            // 心跳事件使用 DEBUG 级别，其他元事件和生命周期事件也使用 DEBUG
            if (event instanceof HeartbeatEvent) {
                log.debug("Decoded heartbeat event: self_id={}", event.getSelfId());
            } else if (event instanceof MetaEvent || event instanceof LifecycleEvent) {
                log.debug("Decoded meta/lifecycle event: type={}, self_id={}", 
                        event.getClass().getSimpleName(), event.getSelfId());
            } else {
                // 消息、通知、请求等实际业务事件使用 INFO 级别
                log.info("Decoded event: type={}, self_id={}", 
                        event.getClass().getSimpleName(), event.getSelfId());
            }
        }
        return event;
    }

    private OB11Event decodeMessage(JsonNode root) throws Exception {
        String messageType = getString(root, "message_type");
        if ("group".equals(messageType)) {
            return mapper.treeToValue(root, GroupMessageEvent.class);
        } else if ("private".equals(messageType)) {
            return mapper.treeToValue(root, PrivateMessageEvent.class);
        } else {
            log.warn("Unknown message_type '{}', falling back to GroupMessageEvent", messageType);
            return mapper.treeToValue(root, GroupMessageEvent.class);
        }
    }

    private OB11Event decodeNotice(JsonNode root) throws Exception {
        String noticeType = getString(root, "notice_type");
        return switch (noticeType) {
            case "group_increase" -> mapper.treeToValue(root, GroupIncreaseEvent.class);
            case "group_decrease" -> mapper.treeToValue(root, GroupDecreaseEvent.class);
            case "group_admin" -> mapper.treeToValue(root, GroupAdminEvent.class);
            case "group_ban" -> mapper.treeToValue(root, GroupBanEvent.class);
            case "friend_add" -> mapper.treeToValue(root, FriendAddEvent.class);
            case "group_recall" -> mapper.treeToValue(root, GroupRecallEvent.class);
            case "friend_recall" -> mapper.treeToValue(root, FriendRecallEvent.class);
            case "group_upload" -> mapper.treeToValue(root, GroupUploadEvent.class);
            case "notify" -> mapper.treeToValue(root, NotifyEvent.class);
            case "lucky_king" -> mapper.treeToValue(root, LuckyKingEvent.class);
            case "honor" -> mapper.treeToValue(root, HonorEvent.class);
            case "title" -> mapper.treeToValue(root, GroupTitleEvent.class);
            default -> {
                log.warn("Unknown notice_type '{}', falling back to NoticeEvent", noticeType);
                yield mapper.treeToValue(root, NoticeEvent.class);
            }
        };
    }

    private OB11Event decodeRequest(JsonNode root) throws Exception {
        String requestType = getString(root, "request_type");
        if ("friend".equals(requestType)) {
            return mapper.treeToValue(root, FriendRequestEvent.class);
        } else if ("group".equals(requestType)) {
            return mapper.treeToValue(root, GroupRequestEvent.class);
        } else {
            log.warn("Unknown request_type '{}', falling back to RequestEvent", requestType);
            return mapper.treeToValue(root, RequestEvent.class);
        }
    }

    private OB11Event decodeMeta(JsonNode root) throws Exception {
        String metaType = getString(root, "meta_event_type");
        if ("lifecycle".equals(metaType)) {
            return mapper.treeToValue(root, LifecycleEvent.class);
        } else if ("heartbeat".equals(metaType)) {
            return mapper.treeToValue(root, HeartbeatEvent.class);
        } else {
            log.warn("Unknown meta_event_type '{}', falling back to MetaEvent", metaType);
            return mapper.treeToValue(root, MetaEvent.class);
        }
    }

    private String getString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null ? value.asText() : null;
    }
}
