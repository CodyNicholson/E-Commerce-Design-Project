package com.example.mromirror.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Number and string helpers that reproduce the Go standard library behaviour the
 * API was first written against, so prices, messages and documents come out
 * byte-for-byte the same.
 */
public final class Go {
    private Go() {}

    private static final Pattern SPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern EDGE_SPACE = Pattern.compile("(?U)^\\s+|\\s+$");

    /** math.Round: half away from zero. */
    public static double round(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        double a = Math.abs(x);
        double t = Math.floor(a);
        if (a - t >= 0.5) {
            t += 1;
        }
        return Math.copySign(t, x);
    }

    /** fmt's %.Nf: the exact binary value rounded half to even. */
    public static String fixed(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return Double.toString(v);
        }
        return new BigDecimal(v).setScale(places, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** strings.TrimSpace. */
    public static String trimSpace(String s) {
        return s == null ? "" : EDGE_SPACE.matcher(s).replaceAll("");
    }

    /** strings.Fields: split around runs of whitespace, dropping empties. */
    public static List<String> fields(String s) {
        List<String> out = new ArrayList<>();
        for (String f : SPACE.split(trimSpace(s))) {
            if (!f.isEmpty()) {
                out.add(f);
            }
        }
        return out;
    }

    /** strconv.Atoi, returning 0 where Go's caller ignored the error. */
    public static int atoiOrZero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
