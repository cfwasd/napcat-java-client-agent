package com.napcat.core.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UnknownSegment extends MessageSegment {

    public UnknownSegment(String type) {
        super(type);
    }
}
