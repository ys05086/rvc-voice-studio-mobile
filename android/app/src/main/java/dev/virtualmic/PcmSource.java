package dev.virtualmic;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Only PCM production/parsing; usable in host-side tests without Android. */
abstract class PcmSource implements Closeable {
    final int rate;
    final int channels;

    PcmSource(int rate, int channels) {
        this.rate = rate;
        this.channels = channels;
    }

    abstract void fill(short[] samples) throws IOException;

    @Override public void close() throws IOException {}

    static final class Tone extends PcmSource {
        private long frame;

        Tone() { super(48000, 1); }

        @Override void fill(short[] samples) {
            for (int i = 0; i < samples.length; i++, frame++) {
                // A 1 kHz pulse: 0.5 s on / 0.5 s off, with 5 ms edge ramps.
                long phase = frame % rate;
                double envelope = phase >= rate / 2 ? 0.0
                        : Math.min(1.0, Math.min(phase / 240.0,
                                (rate / 2 - 1 - phase) / 240.0));
                samples[i] = (short) Math.round(4096.0 * envelope
                        * Math.sin(2.0 * Math.PI * 1000.0 * frame / rate));
            }
        }
    }

    static final class Wav extends PcmSource {
        private final RandomAccessFile file;
        private final long dataStart;
        private final long dataEnd;

        private Wav(RandomAccessFile file, int rate, int channels, long start, long size)
                throws IOException {
            super(rate, channels);
            this.file = file;
            dataStart = start;
            dataEnd = start + size;
            file.seek(start);
        }

        static Wav open(String path) throws IOException {
            RandomAccessFile file = new RandomAccessFile(path, "r");
            try {
                if (!"RIFF".equals(fourCC(file))) throw new IOException("Expected RIFF WAV");
                long riffEnd = uint32(file) + 8;
                if (!"WAVE".equals(fourCC(file))) throw new IOException("Expected WAVE");
                if (riffEnd > file.length() || riffEnd < 12) {
                    throw new IOException("Invalid or truncated RIFF size");
                }
                int rate = 0, channels = 0, align = 0;
                boolean hasFormat = false;
                long start = -1, size = 0;
                while (file.getFilePointer() + 8 <= riffEnd) {
                    String chunk = fourCC(file);
                    long length = uint32(file);
                    long body = file.getFilePointer();
                    long next = body + length + (length & 1);
                    if (next > riffEnd) throw new IOException("Truncated WAV chunk: " + chunk);
                    if ("fmt ".equals(chunk)) {
                        if (hasFormat || length < 16) throw new IOException("Invalid fmt chunk");
                        int encoding = uint16(file);
                        channels = uint16(file);
                        long sampleRate = uint32(file);
                        long byteRate = uint32(file);
                        align = uint16(file);
                        int bits = uint16(file);
                        if (encoding != 1 || bits != 16 || (channels != 1 && channels != 2)
                                || sampleRate < 8000 || sampleRate > 48000
                                || align != channels * 2 || byteRate != sampleRate * align) {
                            throw new IOException("Use PCM16 WAV, mono/stereo, 8-48 kHz");
                        }
                        rate = (int) sampleRate;
                        hasFormat = true;
                    } else if ("data".equals(chunk)) {
                        if (start >= 0) throw new IOException("Multiple data chunks unsupported");
                        start = body;
                        size = length;
                    }
                    file.seek(next);
                }
                if (!hasFormat || start < 0 || size == 0 || size % align != 0) {
                    throw new IOException("Missing format/data or unaligned/empty PCM");
                }
                return new Wav(file, rate, channels, start, size);
            } catch (IOException | RuntimeException error) {
                file.close();
                throw error;
            }
        }

        @Override void fill(short[] samples) throws IOException {
            if (samples.length % channels != 0) throw new IOException("Unaligned sample buffer");
            for (int i = 0; i < samples.length; i++) {
                if (file.getFilePointer() >= dataEnd) file.seek(dataStart);
                samples[i] = (short) uint16(file);
            }
        }

        @Override public void close() throws IOException { file.close(); }

        private static int uint16(RandomAccessFile file) throws IOException {
            return file.readUnsignedByte() | file.readUnsignedByte() << 8;
        }

        private static long uint32(RandomAccessFile file) throws IOException {
            return (long) uint16(file) | (long) uint16(file) << 16;
        }

        private static String fourCC(RandomAccessFile file) throws IOException {
            byte[] bytes = new byte[4];
            file.readFully(bytes);
            return new String(bytes, "US-ASCII");
        }
    }
}
