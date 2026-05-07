package com.napcat.core.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.event.EventDecoder;
import com.napcat.core.event.OB11Event;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * 统一消息路由器，所有 BotAdapter 的原始消息均汇入此处。
 * 
 * 判定顺序：post_type 存在 → 事件；echo 存在 → API 响应。
 * 两者可同时存在（罕见但合法），各自独立分发。
 */
@Slf4j
public class MessageRouter implements Consumer<String> {

    private final ObjectMapper mapper;
    private final EventDecoder eventDecoder;
    private final NapCatApi api;

    private Consumer<OB11Event> eventConsumer;

    public MessageRouter(ObjectMapper mapper, NapCatApi api) {
        this.mapper = mapper;
        this.eventDecoder = new EventDecoder(mapper);
        this.api = api;
    }

    public void setEventConsumer(Consumer<OB11Event> consumer) {
        this.eventConsumer = consumer;
    }

    @Override
    public void accept(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(rawMessage);

            boolean isEvent = root.has("post_type");
            boolean isApiResponse = root.has("echo");

            if (isEvent) {
                dispatchEvent(root);
            }
            if (isApiResponse) {
                dispatchApiResponse(rawMessage);
            }
            if (!isEvent && !isApiResponse) {
                log.warn("Unrecognized message (no post_type nor echo): {}",
                        rawMessage.length() > 200 ? rawMessage.substring(0, 200) + "..." : rawMessage);
            }
        } catch (Exception e) {
            log.error("MessageRouter failed to parse message: {}",
                    rawMessage.length() > 200 ? rawMessage.substring(0, 200) + "..." : rawMessage, e);
        }
    }

    private void dispatchEvent(JsonNode root) {
        try {
            OB11Event event = eventDecoder.decode(root);
            if (event != null && eventConsumer != null) {
                log.debug("Dispatching event: post_type={}, class={}",
                        event.getPostType(), event.getClass().getSimpleName());
                eventConsumer.accept(event);
            }
        } catch (Exception e) {
            log.error("Failed to decode event", e);
        }
    }

    private void dispatchApiResponse(String rawMessage) {
        try {
            api.onResponse(rawMessage);
        } catch (Exception e) {
            log.error("Failed to route API response", e);
        }
    }
}
