package org.wuyang.ytdlp.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.wuyang.ytdlp.config.YtDlpProperties;
import org.wuyang.ytdlp.model.Format;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Identifies untagged audio formats with a local Whisper.cpp model. */
@Service
public class AudioLanguageRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(AudioLanguageRecognitionService.class);
    private final YtDlpRunner runner;
    private final ObjectMapper objectMapper;
    private final String ffmpegPath;
    private final String whisperCli;
    private final String modelPath;
    private final int sampleSeconds;
    private final ExecutorService executor;

    public AudioLanguageRecognitionService(YtDlpRunner runner, YtDlpProperties props,
                                           ObjectMapper objectMapper) {
        this.runner = runner;
        this.objectMapper = objectMapper;
        this.ffmpegPath = props.bin().ffmpeg();
        this.whisperCli = props.speechRecognition().whisperCli();
        this.modelPath = props.speechRecognition().model();
        this.sampleSeconds = Math.max(5, Math.min(props.speechRecognition().sampleSeconds(), 30));
        int parallelism = Math.max(1, Math.min(props.speechRecognition().parallelism(), 4));
        this.executor = Executors.newFixedThreadPool(parallelism);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public List<Format> recognize(String url, String cookieMode, List<Format> formats) {
        List<Format> result = new ArrayList<>(formats);
        List<Future<Format>> pending = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();

        for (int i = 0; i < formats.size(); i++) {
            Format format = formats.get(i);
            if (!format.audioOnly() || "metadata".equals(format.languageStatus())) continue;
            indexes.add(i);
            pending.add(executor.submit(() -> recognizeOne(url, cookieMode, format)));
        }

        for (int i = 0; i < pending.size(); i++) {
            try {
                result.set(indexes.get(i), pending.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=audio_language.interrupted formatId={}", formats.get(indexes.get(i)).id());
                result.set(indexes.get(i), formats.get(indexes.get(i)).withLanguage("", "failed"));
                for (Future<Format> future : pending) future.cancel(true);
                break;
            } catch (ExecutionException e) {
                Format format = formats.get(indexes.get(i));
                log.warn("event=audio_language.failed formatId={} message={}",
                        format.id(), e.getCause().getMessage());
                result.set(indexes.get(i), format.withLanguage("", "failed"));
            }
        }
        return List.copyOf(result);
    }

    private Format recognizeOne(String url, String cookieMode, Format format) throws IOException, InterruptedException {
        if (!isConfigured()) return format.withLanguage("", "not-configured");

        Path tempDir = Files.createTempDirectory("yt-dlp-audio-language-");
        try {
            Path audioSample = tempDir.resolve("sample.wav");
            Path whisperOutput = tempDir.resolve("recognition");
            String streamUrl = runner.resolveAudioStreamUrl(url, cookieMode, format.id());
            runProcess(List.of(ffmpegPath, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", streamUrl, "-t", String.valueOf(sampleSeconds), "-vn", "-ac", "1", "-ar", "16000",
                    "-c:a", "pcm_s16le", audioSample.toString()), 60, "音频片段提取失败");
            runProcess(List.of(whisperCli, "-m", modelPath, "-f", audioSample.toString(),
                    "-l", "auto", "-oj", "-of", whisperOutput.toString()), 180, "本地语种识别失败");

            Path jsonFile = Path.of(whisperOutput + ".json");
            if (!Files.isRegularFile(jsonFile)) {
                throw new IOException("Whisper.cpp 未生成语种识别结果");
            }
            JsonNode root = objectMapper.readTree(Files.readString(jsonFile));
            String language = detectedLanguage(root);
            if (language.isBlank()) {
                return format.withLanguage("", "unrecognized");
            }
            return format.withLanguage(language, "detected");
        } finally {
            deleteTemporaryFiles(tempDir);
        }
    }

    private boolean isConfigured() {
        return ffmpegPath != null && !ffmpegPath.isBlank()
                && whisperCli != null && !whisperCli.isBlank()
                && modelPath != null && !modelPath.isBlank() && Files.isRegularFile(Path.of(modelPath));
    }

    static String detectedLanguage(JsonNode root) {
        String language = root.path("result").path("language").asString("");
        return language.isBlank() || language.equalsIgnoreCase("unknown")
                || language.equalsIgnoreCase("und") || language.equals("?") ? "" : language;
    }

    private static void runProcess(List<String> command, long timeoutSeconds, String failureMessage)
            throws IOException, InterruptedException {
        Path logFile = Files.createTempFile("yt-dlp-audio-language-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException(failureMessage + "（超时）");
            }
            if (process.exitValue() != 0) {
                throw new IOException(failureMessage + "（退出码 " + process.exitValue() + "）");
            }
        } catch (InterruptedException e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            Files.deleteIfExists(logFile);
        }
    }

    private static void deleteTemporaryFiles(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
