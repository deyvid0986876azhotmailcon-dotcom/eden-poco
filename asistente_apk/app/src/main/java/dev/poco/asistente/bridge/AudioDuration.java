package dev.poco.asistente.bridge;

import android.media.MediaMetadataRetriever;
import dev.poco.asistente.core.AudioInput;
import java.io.File;
import java.io.FileOutputStream;

/** Verify the actual media duration, not just WhatsApp's or HTTP's claimed duration. */
final class AudioDuration {
    static void validate(AudioInput audio, File cache) throws Exception {
        File file = File.createTempFile("incoming-audio-", ".media", cache);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(audio.bytes); }
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null) throw new IllegalArgumentException("Duración desconocida.");
            long millis = Long.parseLong(duration);
            if (millis <= 0 || millis > AudioInput.MAX_SECONDS * 1000L) throw new IllegalArgumentException("Audio demasiado largo.");
        } finally {
            try { retriever.release(); } finally {
                if (!file.delete()) file.deleteOnExit();
            }
        }
    }
}
