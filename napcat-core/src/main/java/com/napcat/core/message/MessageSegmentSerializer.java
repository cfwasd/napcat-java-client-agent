package com.napcat.core.message;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Map;

public class MessageSegmentSerializer extends JsonSerializer<MessageSegment> {

    @Override
    public void serialize(MessageSegment value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", value.getType());
        gen.writeObjectFieldStart("data");
        for (Map.Entry<String, Object> entry : value.getData().entrySet()) {
            gen.writeObjectField(entry.getKey(), entry.getValue());
        }
        gen.writeEndObject();
        gen.writeEndObject();
    }
}
