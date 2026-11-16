package com.motonav.companion;

import java.nio.charset.StandardCharsets;

/** Optional HUD data from the same trusted SDK instruction as a NAV4 snapshot. */
public record NavigationDetails(String currentRoad, String nextRoad, int etaMinute, int speedLimit) {
    public static final int MAX_NAME_BYTES = 63;
    public NavigationDetails {
        currentRoad = clean(currentRoad);
        nextRoad = clean(nextRoad);
        if (etaMinute < -1 || etaMinute >= 1440 || speedLimit < -1 || speedLimit == 0 || speedLimit > 300)
            throw new IllegalArgumentException("navigation details");
    }
    static String clean(String source) {
        if (source == null) return "";
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < source.length();) {
            int cp = source.codePointAt(i); i += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || cp >= 0xd800 && cp <= 0xdfff) continue;
            String text = new String(Character.toChars(cp));
            int size = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > MAX_NAME_BYTES) break;
            result.append(text); bytes += size;
        }
        return result.toString().trim();
    }
}
