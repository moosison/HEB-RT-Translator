/*
 * Copyright 2024 HebrewTranslator Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Routes translated TTS audio to the RIGHT Bluetooth earbud channel and, optionally,
 * original captured voice to the LEFT channel.  This enables "lecture mode":
 *   Left ear  → original speaker's voice (heard naturally / played back from Recorder PCM)
 *   Right ear → Hebrew (or other target language) TTS translation
 *
 * Both methods are fire-and-forget: they spawn their own background thread and return
 * immediately to the caller.
 */
public class StereoAudioRouter {

    private static final String TAG = "StereoAudioRouter";

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Plays {@code pcmFloat} (the raw audio captured by {@link
     * nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder}) on the
     * LEFT stereo channel only.  The right channel is silent.
     *
     * @param pcmFloat   Float32 PCM samples in [-1, 1] range.
     * @param sampleRate Sample rate of the PCM data (typically 16000 Hz).
     */
    public static void playLeftChannel(float[] pcmFloat, int sampleRate) {
        if (pcmFloat == null || pcmFloat.length == 0 || sampleRate <= 0) return;
        new Thread(() -> {
            short[] stereo = floatMonoToStereoLeft(pcmFloat);
            playStereoShorts(stereo, sampleRate);
        }, "ht-left-channel").start();
    }

    /**
     * Reads a WAV file produced by {@link android.speech.tts.TextToSpeech#synthesizeToFile}
     * and plays it on the RIGHT stereo channel only.  The left channel is silent.
     * {@code onDone} is called on the playback thread after the AudioTrack finishes.
     *
     * @param wavFile WAV file written by TTS synthesizeToFile.
     * @param onDone  Callback invoked (on the playback thread) when audio finishes or on error.
     */
    public static void playRightChannelFromWav(File wavFile, Runnable onDone) {
        if (wavFile == null) {
            if (onDone != null) onDone.run();
            return;
        }
        new Thread(() -> {
            try {
                if (!wavFile.exists() || wavFile.length() < 44) {
                    Log.w(TAG, "WAV file missing or too small: " + wavFile.getAbsolutePath());
                    return;
                }
                try (FileInputStream fis = new FileInputStream(wavFile)) {
                    byte[] header = new byte[44];
                    if (fis.read(header) < 44) {
                        Log.e(TAG, "Could not read WAV header");
                        return;
                    }

                    // Parse key WAV header fields (little-endian)
                    int sampleRate = ByteBuffer.wrap(header, 24, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).getInt();
                    short numChannels = ByteBuffer.wrap(header, 22, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).getShort();

                    long dataLen = wavFile.length() - 44;
                    if (dataLen <= 0) {
                        Log.w(TAG, "WAV has no PCM data");
                        return;
                    }

                    byte[] pcmBytes = new byte[(int) dataLen];
                    //noinspection ResultOfMethodCallIgnored
                    fis.read(pcmBytes);

                    // Bytes → 16-bit signed shorts (little-endian)
                    short[] mono = new short[pcmBytes.length / 2];
                    ByteBuffer.wrap(pcmBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(mono);

                    // If TTS produced stereo (unusual), take left channel
                    if (numChannels == 2) {
                        short[] tmp = new short[mono.length / 2];
                        for (int i = 0; i < tmp.length; i++) tmp[i] = mono[i * 2];
                        mono = tmp;
                    }

                    short[] stereo = shortMonoToStereoRight(mono);
                    playStereoShorts(stereo, sampleRate);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading WAV for right-channel playback", e);
            } finally {
                if (onDone != null) onDone.run();
            }
        }, "ht-right-channel").start();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Interleave mono floats into stereo shorts: [L, 0, L, 0, …] */
    private static short[] floatMonoToStereoLeft(float[] mono) {
        short[] stereo = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            stereo[i * 2]     = floatToShort(mono[i]); // left
            stereo[i * 2 + 1] = 0;                      // right silent
        }
        return stereo;
    }

    /** Interleave mono shorts into stereo shorts: [0, R, 0, R, …] */
    private static short[] shortMonoToStereoRight(short[] mono) {
        short[] stereo = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            stereo[i * 2]     = 0;        // left silent
            stereo[i * 2 + 1] = mono[i]; // right
        }
        return stereo;
    }

    private static short floatToShort(float v) {
        // Clamp to [-1, 1] then scale
        if (v > 1f) v = 1f;
        if (v < -1f) v = -1f;
        return (short) (v * Short.MAX_VALUE);
    }

    /**
     * Streams {@code stereo} interleaved short PCM to an AudioTrack in STREAM mode.
     * Blocks until all samples have been written (but NOT until the hardware finishes
     * playing the last buffer; the extra sleep handles that).
     */
    private static void playStereoShorts(short[] stereo, int sampleRate) {
        if (stereo == null || stereo.length == 0) return;

        int minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid AudioTrack buffer size " + minBuf + " at " + sampleRate + " Hz");
            return;
        }

        AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
                AudioTrack.MODE_STREAM);

        try {
            track.play();
            int offset = 0;
            int chunk = minBuf / 2; // write half-buffer chunks (in shorts)
            while (offset < stereo.length && !Thread.currentThread().isInterrupted()) {
                int toWrite = Math.min(chunk, stereo.length - offset);
                track.write(stereo, offset, toWrite);
                offset += toWrite;
            }
            // Wait for the hardware to drain its internal buffer
            // Duration = samples / (sampleRate × channels)
            long durationMs = (long) stereo.length * 1000L / ((long) sampleRate * 2);
            Thread.sleep(Math.min(durationMs + 300L, 35_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            track.stop();
            track.release();
        }
    }
}
