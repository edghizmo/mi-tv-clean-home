package com.cleanhome.tv;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Offline voice commands for the remote's voice button (replaces Xiao Ai).
 * Hold the button and speak: audio comes from the remote's microphone (the
 * "MIBT.VoiceControl" input), and on release Vosk turns it into text on the
 * TV itself, then {@link VoiceCommands} acts on it. Nothing leaves the TV.
 * The model (~70 MB in memory) is loaded on first use and freed after 10
 * minutes without a voice command. Logs use the tag "CleanHomeVoice".
 */
final class VoiceAssistant {
    private static final String TAG = "CleanHomeVoice";
    private static final int RATE = 16000;
    private static final long UNLOAD_AFTER_MS = 10 * 60 * 1000L;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VoiceCommands commands;
    private volatile Model model;
    private CountDownLatch modelReady;
    private volatile boolean recording;
    private Thread recorder;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

    // Earcons like Google's: rising chime = listening, falling chime = done.
    private final SoundPool sounds = new SoundPool.Builder().setMaxStreams(2)
            .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build();
    private int startSound, stopSound;

    VoiceAssistant(Context ctx) {
        this.ctx = ctx;
        this.commands = new VoiceCommands(ctx);
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        startSound = sounds.load(ctx, R.raw.voice_start, 1);
        stopSound = sounds.load(ctx, R.raw.voice_stop, 1);
    }

    /** Voice button pressed: start listening. */
    void press() {
        if (recording) return;
        main.removeCallbacks(unload);
        loadModel();
        sounds.play(startSound, 1f, 1f, 1, 0, 1f);
        show("Listening…");
        recording = true;
        synchronized (pcm) {
            pcm.reset();
        }
        recorder = new Thread(this::record, "voice-record");
        recorder.start();
    }

    /** Voice button released: recognise what was said and act on it. */
    void release() {
        if (!recording) return;
        recording = false;
        sounds.play(stopSound, 1f, 1f, 1, 0, 1f);
        show("…");
        Thread rec = recorder;
        new Thread(() -> {
            try {
                if (rec != null) rec.join(2000);
            } catch (InterruptedException ignored) {
            }
            byte[] audio;
            synchronized (pcm) {
                audio = pcm.toByteArray();
            }
            // Two passes over the same audio: against the list of known
            // commands (accurate for app names and fixed phrases), and as free
            // dictation (for what follows "search"). A flag brackets the
            // command pass: if Vosk crashed the process during it last time,
            // stick to dictation rather than crash again.
            String free = recognise(audio, null);
            String cmd = "";
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE);
            if (prefs.getBoolean("grammarCrashed", false)) {
                Log.w(TAG, "command pass off: it crashed before");
            } else if (model != null) {
                String grammar = commands.grammar(this::known);
                prefs.edit().putBoolean("grammarCrashed", true).commit();
                cmd = recognise(audio, grammar);
                prefs.edit().putBoolean("grammarCrashed", false).commit();
            }
            Log.i(TAG, "heard: command=\"" + cmd + "\" free=\"" + free + "\" ("
                    + audio.length / (2.0 * RATE) + " s)");
            final String heard = cmd;
            main.post(() -> {
                String said = commands.run(heard, free);
                show(said);
                main.postDelayed(unload, UNLOAD_AFTER_MS);
            });
        }, "voice-recognise").start();
    }

    // ---------------------------------------------------------------- audio

    private void record() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
        } catch (SecurityException e) {
            Log.w(TAG, "no microphone permission");
            return;
        }
        for (AudioDeviceInfo d : ctx.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (String.valueOf(d.getProductName()).contains("MIBT")) rec.setPreferredDevice(d);
        }
        byte[] buf = new byte[3200];
        long t0 = System.currentTimeMillis();
        try {
            rec.startRecording();
            // Non-blocking reads: if the remote sends nothing, don't hang. Max 15 s.
            while (recording && System.currentTimeMillis() - t0 < 15_000) {
                int n = rec.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
                if (n > 0) {
                    synchronized (pcm) {
                        pcm.write(buf, 0, n);
                    }
                } else {
                    Thread.sleep(15);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "recording: " + e);
        } finally {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            rec.release();
        }
    }

    // ---------------------------------------------------------------- speech

    private synchronized void loadModel() {
        if (model != null || modelReady != null) return;
        modelReady = new CountDownLatch(1);
        final CountDownLatch done = modelReady;
        StorageService.unpack(ctx, "model-en-us", "model", m -> {
            model = m;
            Log.i(TAG, "model loaded");
            done.countDown();
        }, e -> {
            Log.w(TAG, "model failed: " + e);
            synchronized (VoiceAssistant.this) {
                modelReady = null;
            }
            done.countDown();
        });
    }

    /** Recognises the audio; with a grammar (JSON array of phrases) only those phrases can match. */
    private String recognise(byte[] audio, String grammar) {
        CountDownLatch ready;
        synchronized (this) {
            ready = modelReady;
        }
        try {
            if (ready != null) ready.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        Model m = model;
        if (m == null || audio.length == 0) return "";
        try (Recognizer r = grammar == null ? new Recognizer(m, RATE) : new Recognizer(m, RATE, grammar)) {
            r.acceptWaveForm(audio, audio.length);
            return new JSONObject(r.getFinalResult()).optString("text", "").trim();
        } catch (Exception e) {
            Log.w(TAG, "recognise: " + e);
            return "";
        }
    }

    /** vosk_model_find_word isn't in the Java API; call the native library directly. */
    private interface Vocab extends com.sun.jna.Library {
        int vosk_model_find_word(com.sun.jna.Pointer model, String word);
    }

    private Vocab vocab;

    /** Whether the loaded model has this word. */
    private boolean known(String word) {
        Model m = model;
        if (m == null) return false;
        if (vocab == null) vocab = com.sun.jna.Native.load("vosk", Vocab.class);
        return vocab.vosk_model_find_word(m.getPointer(), word) >= 0;
    }

    private final Runnable unload = () -> {
        synchronized (VoiceAssistant.this) {
            if (recording || model == null) return;
            model.close();
            model = null;
            modelReady = null;
            Log.i(TAG, "model unloaded");
        }
    };

    // ---------------------------------------------------------------- feedback

    /**
     * Shows a short message as a system toast. Not an overlay window: Xiaomi's
     * window manager force-stops any app that owns a "system window" when
     * another app comes to the front, which also switches off our Home-button
     * accessibility service.
     */
    private void show(String text) {
        main.post(() -> {
            if (toast != null) toast.cancel();
            toast = Toast.makeText(ctx, text, Toast.LENGTH_SHORT);
            toast.show();
        });
    }

    private Toast toast;
    private final Runnable hide = () -> {
        if (toast != null) toast.cancel();
    };
}
