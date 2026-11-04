/*
 * Authors:
 *   1. <Zixi Khoo a1925025>
 */
package comp3011;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

/**
 * Fetches a transcript for a media file from the subtitle service.
 *
 * <p>The whole audio track is extracted and uploaded in one request rather than streamed in chunks.
 * That gives the complete timeline up front, so seeking needs no extra work: the player simply looks
 * up whichever segment covers the current playback time. The cost is a delay before the first
 * subtitle appears, which is why this runs on its own thread and the player plays on regardless.</p>
 */
public class SubtitleService {
    private static final String ENDPOINT = "http://localhost:8080/api/v1/subtitles";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);
    private static final String BOUNDARY = "----VideoPlayerSubtitleBoundary";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Extracts the audio and asks the service for a transcript. Never throws: a failure completes
     * with an empty list, because subtitles are an enhancement and must not stop playback.
     */
    public CompletableFuture<List<SubtitleSegment>> fetchSegments(File mediaFile) {
        return CompletableFuture.supplyAsync(() -> {
            Path audioFile = null;
            try {
                audioFile = extractAudio(mediaFile);
                byte[] body = multipartBody(audioFile, mediaFile.getName());

                HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    System.out.println("Subtitle service returned HTTP " + response.statusCode());
                    return List.<SubtitleSegment>of();
                }
                
                return parseSegments(response.body());
            } catch (Exception e) {
                System.out.println("Subtitles unavailable: " + e.getClass().getSimpleName());
                return List.<SubtitleSegment>of();
            } finally {
                deleteQuietly(audioFile);
            }
        });
    }

    // Decodes the media's audio track into a temporary MP3, which keeps the upload small.
    private Path extractAudio(File mediaFile) throws Exception {
        Path output = Files.createTempFile("subtitle-audio-", ".mp3");

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile)) {
            grabber.setSampleMode(org.bytedeco.javacv.FrameGrabber.SampleMode.SHORT);
            grabber.start();

            if (!grabber.hasAudio()) {
                throw new IllegalStateException("media has no audio track");
            }

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                    output.toFile(), grabber.getAudioChannels())) {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
                recorder.setSampleRate(grabber.getSampleRate());
                recorder.setAudioChannels(grabber.getAudioChannels());
                recorder.setFormat("mp3");
                recorder.start();

                Frame frame;
                while ((frame = grabber.grabSamples()) != null) {
                    recorder.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
                }
            }
        }
        return output;
    }

    private byte[] multipartBody(Path audioFile, String mediaName) throws Exception {
        byte[] audio = Files.readAllBytes(audioFile);
        String header = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"mediaId\"\r\n\r\n"
                + mediaName + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.mp3\"\r\n"
                + "Content-Type: audio/mpeg\r\n\r\n";
        String footer = "\r\n--" + BOUNDARY + "--\r\n";

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + audio.length + footerBytes.length];

        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(audio, 0, body, headerBytes.length, audio.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + audio.length, footerBytes.length);
        return body;
    }

    // A small hand written parser, so the player needs no JSON dependency of its own.
    private List<SubtitleSegment> parseSegments(String json) {
        List<SubtitleSegment> segments = new ArrayList<>();
        Pattern pattern = Pattern.compile(
                "\\{\\s*\"start\"\\s*:\\s*([0-9.eE+-]+)\\s*,"
                        + "\\s*\"end\"\\s*:\\s*([0-9.eE+-]+)\\s*,"
                        + "\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}");

        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            long startUs = Math.round(Double.parseDouble(matcher.group(1)) * 1_000_000.0);
            long endUs = Math.round(Double.parseDouble(matcher.group(2)) * 1_000_000.0);
            String text = unescape(matcher.group(3));
            if (!text.isBlank()) {
                segments.add(new SubtitleSegment(startUs, endUs, text));
            }
        }
        return segments;
    }

    private String unescape(String text) {
        return text.replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\\\", "\\");
    }

    private void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception e) {
                // A leftover temporary file is harmless; the OS will clear it.
            }
        }
    }
}