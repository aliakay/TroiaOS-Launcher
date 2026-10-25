package io.troiaos;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Soft click for the home screen (moving focus and opening). The app synthesizes the sound itself, in the manner of
 * Apple TV's: a very short, round "tick" whose pitch glides down a little (moving) and a deeper, fuller "tock" (opening),
 * both with the highs rolled off. It writes each once as a WAV in the cache and plays it quietly through SoundPool.
 * It only plays on the home screen, never inside apps.
 */
final class Click {
    private static final int RATE = 48_000;
    private SoundPool pool;
    private int move, open;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            // The file names carry the version: a changed sound must not reuse an older cached file.
            for (String old : new String[] {"click-move.wav", "click-open.wav"}) new File(c.getCacheDir(), old).delete();
            move = pool.load(wav(c, "click-move-2.wav", 820f, 520f, 0.012f, 0.012f, 0.055f, 0.04f, 0.002f, 3200f), 1);
            open = pool.load(wav(c, "click-open-2.wav", 520f, 300f, 0.025f, 0.032f, 0.150f, 0.05f, 0.003f, 2600f), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        if (pool != null) pool.play(move, 0.19f, 0.19f, 1, 0, 1f);
    }

    void open() {
        if (pool != null) pool.play(open, 0.23f, 0.23f, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    /**
     * A sine whose pitch glides from `f0` down to `f1` (time constant `sweep`) plus a little of its octave, a short attack
     * (no pop), exponential decay, then a two-pole low-pass at `lowpass` Hz to take the sharpness off.
     */
    private static String wav(Context c, String name, float f0, float f1, float sweep, float decay, float seconds,
                              float harmonic, float attack, float lowpass) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * seconds);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double phase = 2 * Math.PI * (f1 * t + (f0 - f1) * sweep * (1 - Math.exp(-t / sweep)));
            double env = Math.min(1, t / attack) * Math.exp(-t / decay);
            x[i] = ((1 - harmonic) * Math.sin(phase) + harmonic * Math.sin(2 * phase)) * env * 0.7;
        }
        // Biquad low-pass (RBJ cookbook, Q = 1).
        double w = 2 * Math.PI * lowpass / RATE, cos = Math.cos(w), alpha = Math.sin(w) / 2;
        double b0 = (1 - cos) / 2, b1 = 1 - cos, b2 = b0, a0 = 1 + alpha, a1 = -2 * cos, a2 = 1 - alpha;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        for (int i = 0; i < n; i++) {
            double y = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
            x2 = x1; x1 = x[i]; y2 = y1; y1 = y;
            b.putShort((short) Math.round(Math.max(-1, Math.min(1, y)) * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
