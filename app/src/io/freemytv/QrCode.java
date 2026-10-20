package io.freemytv;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1 to 10 (up to 213 bytes:
 * plenty for a Wi-Fi login). After the well-known algorithm of Project Nayuki's QR Code generator.
 */
final class QrCode {
    private QrCode() {}

    // Level M, versions 1–10: error correction codewords per block, and number of blocks.
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_M = 0;   // the level's two format bits (L = 1, M = 0, Q = 3, H = 2)

    /** The modules (true = dark), [y][x]; null if the text is too long. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        for (; version <= 10; version++) {
            int bits = 4 + (version <= 9 ? 8 : 16) + data.length * 8;
            if (bits <= dataCodewords(version) * 8) break;
        }
        if (version > 10) return null;
        int capacity = dataCodewords(version) * 8;
        BitBuffer bb = new BitBuffer();
        bb.append(0x4, 4);                                   // byte mode
        bb.append(data.length, version <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity - bb.length));      // terminator
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacity; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[bb.length / 8];
        for (int i = 0; i < bb.length; i++) codewords[i >>> 3] |= bb.bit(i) << (7 - (i & 7));

        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size], function = new boolean[size][size];
        drawFunctionPatterns(version, size, modules, function);
        drawCodewords(addEccAndInterleave(version, codewords), size, modules, function);
        // The mask with the lowest penalty, and its format bits.
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask, size, modules, function);
            drawFormatBits(mask, size, modules, function);
            long p = penalty(size, modules);
            if (p < bestPenalty) {
                bestPenalty = p;
                best = mask;
            }
            applyMask(mask, size, modules, function);    // XOR again: undone
        }
        applyMask(best, size, modules, function);
        drawFormatBits(best, size, modules, function);
        return modules;
    }

    /** "WIFI:T:WPA;S:name;P:password;;" with \ ; , : and " escaped (the format phones read). */
    static String wifi(String ssid, String password, String security) {
        String type = security == null || security.trim().isEmpty() ? "WPA" : security.trim();
        boolean open = type.equalsIgnoreCase("nopass") || password == null || password.isEmpty();
        return "WIFI:T:" + (open ? "nopass" : type) + ";S:" + escape(ssid) + ";" + (open ? "" : "P:" + escape(password) + ";") + ";";
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    // MARK: - Sizes

    private static int rawModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int align = ver / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    private static int[] alignmentPositions(int ver, int size) {
        if (ver == 1) return new int[0];
        int count = ver / 7 + 2;
        int step = (ver * 4 + count * 2 + 1) / (count * 2 - 2) * 2;
        int[] out = new int[count];
        out[0] = 6;
        for (int i = count - 1, pos = size - 7; i >= 1; i--, pos -= step) out[i] = pos;
        return out;
    }

    // MARK: - Function patterns

    private static void set(int x, int y, boolean dark, boolean[][] m, boolean[][] f) {
        m[y][x] = dark;
        f[y][x] = true;
    }

    private static void drawFunctionPatterns(int ver, int size, boolean[][] m, boolean[][] f) {
        for (int i = 0; i < size; i++) {            // timing patterns
            set(6, i, i % 2 == 0, m, f);
            set(i, 6, i % 2 == 0, m, f);
        }
        finder(3, 3, size, m, f);
        finder(size - 4, 3, size, m, f);
        finder(3, size - 4, size, m, f);
        int[] align = alignmentPositions(ver, size);
        for (int i = 0; i < align.length; i++) {
            for (int j = 0; j < align.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == align.length - 1) || (i == align.length - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        set(align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1, m, f);
                    }
                }
            }
        }
        drawFormatBits(0, size, m, f);              // reserved now, real bits once the mask is chosen
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(a, b, bit, m, f);
                set(b, a, bit, m, f);
            }
        }
    }

    /** Finder pattern centred at (cx, cy), with its white separator. */
    private static void finder(int cx, int cy, int size, boolean[][] m, boolean[][] f) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, dist != 2 && dist != 4, m, f);
            }
        }
    }

    private static void drawFormatBits(int mask, int size, boolean[][] m, boolean[][] f) {
        int data = FORMAT_M << 3 | mask, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i), m, f);
        set(8, 7, bit(bits, 6), m, f);
        set(8, 8, bit(bits, 7), m, f);
        set(7, 8, bit(bits, 8), m, f);
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i), m, f);
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i), m, f);
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i), m, f);
        set(8, size - 8, true, m, f);               // always dark
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // MARK: - Data

    private static byte[] addEccAndInterleave(int ver, byte[] data) {
        int numBlocks = BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver], raw = rawModules(ver) / 8;
        int shortBlocks = numBlocks - raw % numBlocks, shortLen = raw / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[] divisor = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int dataLen = shortLen - eccLen + (i < shortBlocks ? 0 : 1);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(data, k, block, 0, dataLen);
            k += dataLen;
            byte[] ecc = rsRemainder(data, k - dataLen, dataLen, divisor);
            System.arraycopy(ecc, 0, block, shortLen + 1 - eccLen, eccLen);
            blocks[i] = block;
        }
        byte[] out = new byte[raw];
        int n = 0;
        for (int i = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                // The short blocks have no byte at the end of their data part.
                if (i != shortLen - eccLen || j >= shortBlocks) out[n++] = blocks[j][i];
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) mul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = mul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, int from, int len, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (int i = from; i < from + len; i++) {
            int factor = (data[i] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int j = 0; j < result.length; j++) result[j] ^= (byte) mul(divisor[j] & 0xFF, factor);
        }
        return result;
    }

    /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    /** The codewords in the zigzag order, two columns at a time from the right, skipping the vertical timing line. */
    private static void drawCodewords(byte[] data, int size, boolean[][] m, boolean[][] f) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!f[y][x] && i < data.length * 8) {
                        m[y][x] = bit(data[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(int mask, int size, boolean[][] m, boolean[][] f) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (f[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert) m[y][x] = !m[y][x];
            }
        }
    }

    /** Penalty rules 1, 2 and 4 of the standard (runs, 2 × 2 blocks, dark/light balance): enough to pick a mask. */
    private static long penalty(int size, boolean[][] m) {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 0;
                boolean color = false;
                for (int b = 0; b < size; b++) {
                    boolean c = pass == 0 ? m[a][b] : m[b][a];
                    if (b > 0 && c == color) {
                        run++;
                        if (run == 5) p += 3;
                        else if (run > 5) p++;
                    } else {
                        color = c;
                        run = 1;
                    }
                }
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : m) for (boolean c : row) if (c) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10L;
    }

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet();
        int length;

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) bits.set(length++, ((value >>> i) & 1) != 0);
        }

        int bit(int i) {
            return bits.get(i) ? 1 : 0;
        }
    }
}
