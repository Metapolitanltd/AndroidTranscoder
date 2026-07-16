package com.otaliastudios.transcoder.internal.audio.remix;

import androidx.annotation.NonNull;

import java.nio.ShortBuffer;

/**
 * A {@link AudioRemixer} that downmixes 5.1 surround (6 channels) audio to stereo or mono,
 * using the ITU-R BS.775 coefficients. This is a fallback for decoders that ignore the
 * downmix request made through {@code MediaFormat} keys (see Decoder).
 *
 * Input frames are expected in the Android canonical PCM channel order:
 * [FL, FR, C, LFE, SL/BL, SR/BR].
 */
public class SurroundDownMixAudioRemixer implements AudioRemixer {

    private static final int INPUT_CHANNELS = 6;

    // ITU-R BS.775 matrix downmix: L = FL + 0.707*C + 0.707*SL (and symmetrically for R).
    // The LFE channel is dropped, as recommended. We don't normalize the coefficients
    // (which would noticeably reduce loudness) - instead we saturate on overflow, which
    // only ever engages on extremely hot masters.
    private static final float CENTER_GAIN = 0.7071f;
    private static final float SURROUND_GAIN = 0.7071f;

    private final int outputChannels;

    public SurroundDownMixAudioRemixer(int outputChannels) {
        if (outputChannels != 1 && outputChannels != 2) {
            throw new IllegalArgumentException("Output channel count not supported: " + outputChannels);
        }
        this.outputChannels = outputChannels;
    }

    @Override
    public void remix(@NonNull final ShortBuffer inputBuffer, @NonNull final ShortBuffer outputBuffer) {
        final int inputFrames = inputBuffer.remaining() / INPUT_CHANNELS;
        final int outputFrames = outputBuffer.remaining() / outputChannels;
        final int frames = Math.min(inputFrames, outputFrames);
        for (int i = 0; i < frames; i++) {
            final short frontLeft = inputBuffer.get();
            final short frontRight = inputBuffer.get();
            final short center = inputBuffer.get();
            inputBuffer.get(); // LFE, dropped
            final short surroundLeft = inputBuffer.get();
            final short surroundRight = inputBuffer.get();
            final float left = frontLeft + CENTER_GAIN * center + SURROUND_GAIN * surroundLeft;
            final float right = frontRight + CENTER_GAIN * center + SURROUND_GAIN * surroundRight;
            if (outputChannels == 2) {
                outputBuffer.put(saturate(left));
                outputBuffer.put(saturate(right));
            } else {
                outputBuffer.put(saturate((left + right) / 2F));
            }
        }
    }

    private static short saturate(float sample) {
        if (sample > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (sample < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) sample;
    }

    @Override
    public int getRemixedSize(int inputSize) {
        return inputSize / INPUT_CHANNELS * outputChannels;
    }
}
