package com.emmanuel.tvhome;

/** show_stats' memory figure from the lines of /proc/meminfo. */
final class MemInfo {
    private MemInfo() {}

    /**
     * RAM usage as {used, total} in MB, used = MemTotal - MemAvailable, e.g. "945 / 1536 MB". Used over free so
     * the line reads as a load against a known whole. MemAvailable only exists since Linux 3.14; on older kernels
     * (TPM171E Philips, Android TV 8, kernel 3.10) available is MemFree + Buffers + Cached, the estimate free(1)
     * used before it. Null when MemTotal or every available figure is missing.
     */
    static long[] usedOfTotalMb(Iterable<String> lines) {
        long total = -1, available = -1, free = -1, buffers = -1, cached = -1;
        for (String line : lines) {
            if (line.startsWith("MemTotal:")) total = kb(line);
            else if (line.startsWith("MemAvailable:")) available = kb(line);
            else if (line.startsWith("MemFree:")) free = kb(line);
            else if (line.startsWith("Buffers:")) buffers = kb(line);
            else if (line.startsWith("Cached:")) cached = kb(line);
            if (total > 0 && available >= 0) break;
        }
        if (available < 0 && free >= 0) available = free + Math.max(0, buffers) + Math.max(0, cached);
        if (available < 0 || total <= 0) return null;
        long totalMb = total / 1024;
        long availableMb = Math.min(total, available) / 1024;
        return new long[]{Math.max(0, totalMb - availableMb), totalMb};
    }

    /** Zero-allocation parser for a "Name:   1234 kB" line, or -1. */
    private static long kb(String line) {
        if (line == null) return -1;
        int len = line.length();
        int i = line.indexOf(':');
        i = i >= 0 ? i + 1 : 0;
        while (i < len && line.charAt(i) <= ' ') i++;
        if (i >= len) return -1;
        long value = 0;
        int digits = 0;
        while (i < len) {
            char c = line.charAt(i);
            if (c >= '0' && c <= '9') {
                if (value > (Long.MAX_VALUE - 9) / 10) return -1;
                value = value * 10 + (c - '0');
                digits++;
                i++;
            } else if (c <= ' ') {
                break;
            } else {
                return -1;
            }
        }
        return digits > 0 ? value : -1;
    }
}
