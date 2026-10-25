package com.example.mromirror.api;

import com.example.mromirror.db.Database;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminControllerTest {
    static class FakeDatabase extends Database {
        boolean reset;

        FakeDatabase() {
            super(null);
        }

        @Override
        public void reset() {
            reset = true;
        }
    }

    @Test
    void resetIsOffWithoutAdminToken() {
        var db = new FakeDatabase();
        var e = assertThrows(ApiException.class, () -> new AdminController(db, "").reset("anything"));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertFalse(db.reset);
    }

    @Test
    void resetRejectsMissingOrWrongToken() {
        var db = new FakeDatabase();
        var c = new AdminController(db, "s3cret-token");
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class, () -> c.reset(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class, () -> c.reset("")).status());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class, () -> c.reset("s3cret")).status());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class, () -> c.reset("s3cret-token ")).status());
        assertFalse(db.reset);
    }

    @Test
    void resetRunsWithMatchingToken() {
        var db = new FakeDatabase();
        assertEquals("reset", new AdminController(db, "s3cret-token").reset("s3cret-token").get("status"));
        assertTrue(db.reset);
    }
}
