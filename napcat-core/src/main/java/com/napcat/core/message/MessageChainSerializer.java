package com.napcat.core.message;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class MessageChainSerializer extends JsonSerializer<MessageChain> {

    @Override
    public void serialize(MessageChain value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartArray();
        for (MessageSegment seg : value) {
            serializers.defaultSerializeValue(seg, gen);
        }
        gen.writeEndArray();
    }
}
