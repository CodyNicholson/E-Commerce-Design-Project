package com.example.mromirror.api;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Reads request bodies whatever their Content-Type (the EDI and PunchOut
 * simulators post text/plain), capped at 1 MiB.
 */
@Component
public class Bodies {
    private static final int MAX_BYTES = 1 << 20;

    private final ObjectMapper mapper;

    public Bodies(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private static byte[] readBytes(HttpServletRequest r) throws IOException {
        try (InputStream in = r.getInputStream()) {
            byte[] b = in.readNBytes(MAX_BYTES + 1);
            if (b.length > MAX_BYTES) {
                throw new IOException("http: request body too large");
            }
            return b;
        }
    }

    public String text(HttpServletRequest r) {
        try {
            return new String(readBytes(r), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiException.badRequest("could not read body");
        }
    }

    /** Decodes a JSON body; a literal null decodes to the empty value. */
    public <T> T json(HttpServletRequest r, Class<T> type, Supplier<T> empty) {
        try {
            byte[] b = readBytes(r);
            if (b.length == 0) {
                throw ApiException.badRequest("invalid JSON body: EOF");
            }
            T v = mapper.readValue(b, type);
            return v == null ? empty.get() : v;
        } catch (JacksonException e) {
            throw ApiException.badRequest("invalid JSON body: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("invalid JSON body: " + e.getMessage());
        }
    }

    /** Decodes a JSON body when there is a valid one, otherwise returns the empty value. */
    public <T> T jsonOrEmpty(HttpServletRequest r, Class<T> type, Supplier<T> empty) {
        try {
            return json(r, type, empty);
        } catch (ApiException e) {
            return empty.get();
        }
    }
}
