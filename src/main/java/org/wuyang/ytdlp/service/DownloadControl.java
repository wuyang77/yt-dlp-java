package org.wuyang.ytdlp.service;

/** Controls a running yt-dlp process for pause and resume operations. */
public final class DownloadControl {

    private volatile Process process;
    private volatile boolean paused;

    void attach(Process process) {
        this.process = process;
    }

    void detach(Process process) {
        if (this.process == process) {
            this.process = null;
        }
    }

    public void pause() {
        paused = true;
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroy();
        }
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }
}
