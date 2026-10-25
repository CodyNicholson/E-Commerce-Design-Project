package com.example.mromirror.api;

import com.example.mromirror.db.Database;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AdminController {
    private final Database db;
    private final String adminToken;

    public AdminController(Database db, @Value("${mromirror.admin-token:}") String adminToken) {
        this.db = db;
        this.adminToken = adminToken == null ? "" : adminToken;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * Drops and reseeds every table. /api is public through the tunnel, so the caller must send
     * X-Admin-Token equal to ADMIN_TOKEN; with ADMIN_TOKEN empty the endpoint is switched off (404).
     */
    @PostMapping("/admin/reset")
    public Map<String, String> reset(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(adminToken, token);
        db.reset();
        return Map.of("status", "reset");
    }

    static void requireAdmin(String configured, String given) {
        if (configured.isEmpty()) {
            throw ApiException.notFound("not found");
        }
        // Constant-time comparison, so response timing does not leak how much of the token matched
        if (given == null || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "admin token required");
        }
    }
}
