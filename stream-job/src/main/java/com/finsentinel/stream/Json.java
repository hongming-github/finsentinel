package com.finsentinel.stream;

import tools.jackson.databind.json.JsonMapper;

final class Json {

    /** Jackson 3 mappers are immutable and thread-safe, so one instance per JVM is enough. */
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }
}
