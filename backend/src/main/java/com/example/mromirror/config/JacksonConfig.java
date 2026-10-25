package com.example.mromirror.config;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer mroMirrorJackson() {
        return builder -> builder
                // DATE columns go out as UTC midnight timestamps (2026-12-31T00:00:00Z),
                // the shape the web app already parses.
                .serializerByType(LocalDate.class, new JsonSerializer<LocalDate>() {
                    @Override
                    public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                        gen.writeString(value + "T00:00:00Z");
                    }
                })
                // An explicit null in a request body keeps the field's default ("" or 0).
                .postConfigurer(mapper -> mapper.setDefaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.SKIP)));
    }
}
