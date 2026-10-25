package com.example.mromirror.api;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Wall-clock time in the server's zone. */
@Component
public class Clock {
    public OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    /** The current calendar date, comparable with DATE columns. */
    public LocalDate today() {
        return LocalDate.now();
    }
}
