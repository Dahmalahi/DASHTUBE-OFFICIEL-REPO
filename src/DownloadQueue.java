/*
 * DownloadQueue.java  –  DashTube v1.0
 * MIDP 2.0 / CLDC 1.1 + JSR-75
 *
 * Sequential download queue with live progress UI.
 * All downloads run one at a time on a single worker thread —
 * safe for feature phones with small heaps & slow connections.
 *
 * Usage:
 *   DownloadQueue q = DownloadQueue.getInstance(midlet);
 *   q.add(url, filename);
 *   midlet.getDisplay().setCurrent(q.getScreen());
 */

import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.*;

// ======================== Queue Job ========================

class QueueJob {
    static final int STATE_PENDING   = 0;
    static final int STATE_ACTIVE    = 1;
    static final int STATE_DONE      = 2;
    static final int STATE_ERROR     = 3;
    static final int STATE_CANCELLED = 4;

    String url;
    String filename;
    int    state     = STATE_PENDING;
    int    percent   = 0;
    long   bytesDown = 0;
    long   bytesTotal= 0;
    String note      = "";    // short status / error text

    QueueJob(String url, String filename) {
        this.url = url;
        this.filename = filename != null ? filename : FileManager.filenameFromUrl(url);
    }
}

// ======================== Download Queue ========================

class DownloadQueue {

    private static DownloadQueue instance;

    private Vector        jobs    = new Vector();   // QueueJob list
    private boolean       running = false;
    private QueueJob      current = null;
    private MultiDownloadScreen screen;
    private VideoProxyBrowserMIDlet midlet;
    private FileManager   fm;

    // ---- Singleton ----
    public static DownloadQueue getInstance(VideoProxyBrowserMIDlet midlet) {
        if (instance == null) instance = new DownloadQueue(midlet);
        instance.midlet = midlet;                   // refresh midlet ref on each access
        return instance;
    }

    private DownloadQueue(VideoProxyBrowserMIDlet midlet) {
        this.midlet = midlet;
        this.fm     = FileManager.getInstance();
        this.screen = new MultiDownloadScreen(midlet, this);
    }

    public MultiDownloadScreen getScreen() { return screen; }

    /** Returns total job count (all states). */
    public int size() { return jobs.size(); }

    /** Add a URL to the queue. Returns the new QueueJob. */
    public synchronized QueueJob add(String url, String filename) {
        QueueJob job = new QueueJob(url, filename);
        jobs.addElement(job);
        screen.notifyJobsChanged();
        if (!running) startWorker();
        return job;
    }

    /** Cancel a pending job. Has no effect if already active/done. */
    public synchronized void cancel(int index) {
        if (index < 0 || index >= jobs.size()) return;
        QueueJob job = (QueueJob) jobs.elementAt(index);
        if (job.state == QueueJob.STATE_PENDING) {
            job.state = QueueJob.STATE_CANCELLED;
            job.note  = "Cancelled";
            screen.notifyJobsChanged();
        }
    }

    /** Remove all done/cancelled/error jobs from the list. */
    public synchronized void clearFinished() {
        Vector keep = new Vector();
        for (int i = 0; i < jobs.size(); i++) {
            QueueJob j = (QueueJob) jobs.elementAt(i);
            if (j.state == QueueJob.STATE_PENDING ||
                j.state == QueueJob.STATE_ACTIVE) keep.addElement(j);
        }
        jobs = keep;
        screen.notifyJobsChanged();
    }

    /** Snapshot of job list for the UI (safe copy). */
    public synchronized QueueJob[] getJobs() {
        QueueJob[] arr = new QueueJob[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) arr[i] = (QueueJob) jobs.elementAt(i);
        return arr;
    }

    // ---- Worker ----

    private void startWorker() {
        running = true;
        new Thread(new Runnable() { public void run() { workerLoop(); } }).start();
    }

    private void workerLoop() {
        while (true) {
            QueueJob job = nextPending();
            if (job == null) { running = false; return; }
            current = job;
            job.state = QueueJob.STATE_ACTIVE;
            job.note  = "Starting...";
            screen.notifyJobsChanged();
            downloadJob(job);
        }
    }

    private synchronized QueueJob nextPending() {
        for (int i = 0; i < jobs.size(); i++) {
            QueueJob j = (QueueJob) jobs.elementAt(i);
            if (j.state == QueueJob.STATE_PENDING) return j;
        }
        return null;
    }

    private void downloadJob(final QueueJob job) {
        if (!fm.isReady()) {
            job.state = QueueJob.STATE_ERROR;
            job.note  = "Storage unavailable";
            screen.notifyJobsChanged();
            return;
        }
        String savePath = fm.getVideoDirUrl() + FileManager.sanitize(job.filename);

        // Reuse DownloadAndPlayRunnable with autoPlay=false
        // We wrap it to update our job state
        final Object lock = new Object();
        final boolean[] done = { false };

        new DownloadAndPlayRunnable(job.url, savePath, new DlListener() {
            public void onProgress(long dl, long tot, int pct) {
                job.bytesDown  = dl;
                job.bytesTotal = tot;
                job.percent    = pct < 0 ? 0 : pct;
                job.note       = fmtSize(dl) + (tot > 0 ? " / " + fmtSize(tot) : "");
                screen.notifyJobsChanged();
            }
            public void onComplete(String path, long bytes) {
                job.state   = QueueJob.STATE_DONE;
                job.percent = 100;
                job.note    = "Saved  " + fmtSize(bytes);
                screen.notifyJobsChanged();
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
            public void onError(String msg) {
                if (msg != null && msg.startsWith("PLAY_FAIL:")) msg = "Play fail";
                job.state = QueueJob.STATE_ERROR;
                job.note  = msg != null ? msg : "Unknown error";
                screen.notifyJobsChanged();
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
            private String fmtSize(long b) {
                if (b < 1024) return b + "B";
                if (b < 1024*1024) return (b/1024) + "KB";
                return (b/(1024*1024)) + "MB";
            }
        }, false).run();   // .run() directly — we're already on the worker thread
    }
}

// ======================== Multi-Download Screen ========================

class MultiDownloadScreen extends Canvas implements CommandListener {

    private VideoProxyBrowserMIDlet midlet;
    private DownloadQueue           queue;
    private HtmlPageCanvas          browser;

    private Command backCmd, clearCmd, cancelItemCmd;
    private Font fontBold, fontNormal, fontSmall;

    private int scrollY      = 0;
    private int selectedIdx  = 0;

    // Palette
    private static final int C_BG      = 0x0F0F0F;
    private static final int C_CARD0   = 0x1A1A1A;
    private static final int C_CARD1   = 0x161616;
    private static final int C_ACCENT  = 0xD42B52;
    private static final int C_TEXT    = 0xE8E8E8;
    private static final int C_SUB     = 0x888888;
    private static final int C_GREEN   = 0x22BB66;
    private static final int C_BLUE    = 0x3A6EDB;
    private static final int C_GOLD    = 0xE8A020;
    private static final int C_ERR     = 0xFF5555;
    private static final int C_SELBG   = 0xD42B52;
    private static final int C_SELTXT  = 0xFFFFFF;
    private static final int C_STBG    = 0x0A0A0A;
    private static final int C_STTXT   = 0xCCCCCC;
    private static final int ACCENT_W  = 3;

    // Row geometry (constant per device)
    private int ROW_H;   // computed in constructor

    public MultiDownloadScreen(VideoProxyBrowserMIDlet midlet, DownloadQueue queue) {
        this.midlet = midlet;
        this.queue  = queue;
        fontBold   = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,  Font.SIZE_SMALL);
        fontNormal = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontSmall  = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        // Row = filename line + progress bar + note line + padding
        ROW_H = fontBold.getHeight() + 10 + fontSmall.getHeight() + 12;

        backCmd       = new Command("Back",         Command.BACK,   1);
        clearCmd      = new Command("Clear Done",   Command.SCREEN, 2);
        cancelItemCmd = new Command("Cancel Item",  Command.SCREEN, 3);

        addCommand(backCmd);
        addCommand(clearCmd);
        addCommand(cancelItemCmd);
        setCommandListener(this);
    }

    /** Called from the queue worker (any thread) — schedules a repaint. */
    public void notifyJobsChanged() {
        midlet.getDisplay().callSerially(new Runnable() { public void run() { repaint(); } });
    }

    // ---- Paint ----

    protected void paint(Graphics g) {
        int w  = getWidth();
        int h  = getHeight();
        int sH = fontSmall.getHeight() + 8;
        int hH = fontBold.getHeight() + 12;
        int vH = h - sH;

        // Background
        g.setColor(C_BG);
        g.fillRect(0, 0, w, h);

        // Header
        g.setColor(0x1A0010);
        g.fillRect(0, 0, w, hH);
        g.setColor(C_ACCENT);
        g.drawLine(0, hH, w, hH);
        g.setFont(fontBold);
        g.setColor(C_TEXT);
        g.drawString("Download Queue", w / 2, 5, Graphics.TOP | Graphics.HCENTER);

        QueueJob[] jobs = queue.getJobs();

        if (jobs.length == 0) {
            // Empty state
            g.setFont(fontNormal);
            g.setColor(C_SUB);
            g.drawString("No downloads queued.", w / 2, hH + 30,
                         Graphics.TOP | Graphics.HCENTER);
            g.drawString("Use 'Queue Download' in any", w / 2, hH + 30 + fontNormal.getHeight() + 6,
                         Graphics.TOP | Graphics.HCENTER);
            g.drawString("download choice screen.", w / 2, hH + 30 + (fontNormal.getHeight() + 6) * 2,
                         Graphics.TOP | Graphics.HCENTER);
        } else {
            // Job rows
            int y = hH + 2 - scrollY;
            for (int i = 0; i < jobs.length; i++) {
                if (y + ROW_H > hH && y < vH)
                    drawJobRow(g, jobs[i], 4, y, w - 8, i == selectedIdx, i);
                y += ROW_H + 2;
            }

            // Scrollbar
            int totalH = jobs.length * (ROW_H + 2);
            int scrollArea = vH - hH;
            if (totalH > scrollArea) {
                int sbW = 3, sbX = w - sbW - 1;
                g.setColor(0x222222);
                g.fillRect(sbX, hH, sbW, scrollArea);
                int thumbH = Math.max(10, (scrollArea * scrollArea) / totalH);
                int maxScroll = totalH - scrollArea;
                int thumbY = hH + (maxScroll > 0
                    ? (scrollY * (scrollArea - thumbH)) / maxScroll : 0);
                if (thumbY < hH) thumbY = hH;
                if (thumbY + thumbH > hH + scrollArea) thumbY = hH + scrollArea - thumbH;
                g.setColor(C_ACCENT);
                g.fillRect(sbX, thumbY, sbW, thumbH);
            }

            // Summary line in header right side
            int done = 0, active = 0, pending = 0;
            for (int i = 0; i < jobs.length; i++) {
                switch (jobs[i].state) {
                    case QueueJob.STATE_DONE: done++;    break;
                    case QueueJob.STATE_ACTIVE: active++;break;
                    default: if (jobs[i].state == QueueJob.STATE_PENDING) pending++; break;
                }
            }
            g.setFont(fontSmall);
            g.setColor(C_SUB);
            String summary = done + "\u2713 " + active + "\u25BA " + pending + "\u23F3";
            g.drawString(summary, w - 4, 5, Graphics.TOP | Graphics.RIGHT);
        }

        // Status bar
        g.setColor(C_STBG);
        g.fillRect(0, h - sH, w, sH);
        g.setColor(C_ACCENT);
        g.drawLine(0, h - sH, w, h - sH);
        g.setFont(fontSmall);
        g.setColor(C_STTXT);
        String fmStr = FileManager.getInstance().isReady()
            ? FileManager.getInstance().getAvailableSpaceString() + " free" : "No storage";
        g.drawString(fmStr, 4, h - sH + 4, Graphics.TOP | Graphics.LEFT);
        g.setColor(C_SUB);
        g.drawString("FIRE=cancel  0=clear done", w - 4, h - sH + 4, Graphics.TOP | Graphics.RIGHT);
    }

    private void drawJobRow(Graphics g, QueueJob job,
                            int x, int y, int maxW,
                            boolean sel, int idx) {
        // Card
        g.setColor(sel ? C_SELBG : (idx % 2 == 0 ? C_CARD0 : C_CARD1));
        g.fillRect(x - 4, y, maxW + 8, ROW_H);

        // State bar colour
        int barColor;
        switch (job.state) {
            case QueueJob.STATE_ACTIVE:    barColor = C_BLUE;   break;
            case QueueJob.STATE_DONE:      barColor = C_GREEN;  break;
            case QueueJob.STATE_ERROR:     barColor = C_ERR;    break;
            case QueueJob.STATE_CANCELLED: barColor = 0x444444; break;
            default:                       barColor = C_GOLD;   break; // PENDING
        }
        if (!sel) {
            g.setColor(barColor);
            g.fillRect(x - 4, y, ACCENT_W, ROW_H);
        }

        // Divider
        if (!sel) {
            g.setColor(0x1E1E1E);
            g.drawLine(x - 4, y + ROW_H - 1, x + maxW + 4, y + ROW_H - 1);
        }

        int tx  = x + (sel ? 2 : ACCENT_W + 4);
        int tw  = maxW - (sel ? 4 : ACCENT_W + 8);
        int ty  = y + 4;
        int tc  = sel ? C_SELTXT : C_TEXT;

        // Filename
        g.setFont(fontBold);
        g.setColor(tc);
        g.drawString(truncate(job.filename, tw, fontBold), tx, ty, Graphics.TOP | Graphics.LEFT);
        ty += fontBold.getHeight() + 4;

        // Progress bar (only for active / done)
        int barW = tw;
        int barH = 8;
        if (job.state == QueueJob.STATE_ACTIVE || job.state == QueueJob.STATE_DONE) {
            g.setColor(sel ? 0x44000022 : 0x222222);
            g.fillRoundRect(tx, ty, barW, barH, barH, barH);
            int fill = (barW * job.percent) / 100;
            if (fill > 0) {
                g.setColor(job.state == QueueJob.STATE_DONE ? C_GREEN : C_BLUE);
                g.fillRoundRect(tx, ty, fill, barH, barH, barH);
            }
            // Percent text inside bar area (right-aligned)
            g.setFont(fontSmall);
            g.setColor(sel ? C_SELTXT : C_SUB);
            g.drawString(job.percent + "%", tx + barW - 1, ty - 1, Graphics.TOP | Graphics.RIGHT);
        }
        ty += barH + 3;

        // Note / state text
        g.setFont(fontSmall);
        String noteStr;
        switch (job.state) {
            case QueueJob.STATE_PENDING:   noteStr = "\u23F3 Queued"; break;
            case QueueJob.STATE_ACTIVE:    noteStr = job.note;        break;
            case QueueJob.STATE_DONE:      noteStr = "\u2713 " + job.note; break;
            case QueueJob.STATE_ERROR:     noteStr = "\u2717 " + job.note; break;
            case QueueJob.STATE_CANCELLED: noteStr = "\u2715 Cancelled";   break;
            default:                       noteStr = "";              break;
        }
        int noteColor = sel ? C_SELTXT :
            (job.state == QueueJob.STATE_DONE      ? C_GREEN :
             job.state == QueueJob.STATE_ERROR      ? C_ERR   :
             job.state == QueueJob.STATE_CANCELLED  ? 0x555555 :
             job.state == QueueJob.STATE_ACTIVE     ? C_BLUE  : C_GOLD);
        g.setColor(noteColor);
        g.drawString(truncate(noteStr, tw, fontSmall), tx, ty, Graphics.TOP | Graphics.LEFT);
    }

    // ---- Key / Touch ----

    protected void keyPressed(int keyCode) {
        QueueJob[] jobs = queue.getJobs();
        int action = getGameActionSafe(keyCode);
        if (action == Canvas.UP) {
            if (selectedIdx > 0) { selectedIdx--; ensureVisible(jobs.length); repaint(); }
        } else if (action == Canvas.DOWN) {
            if (selectedIdx < jobs.length - 1) { selectedIdx++; ensureVisible(jobs.length); repaint(); }
        } else if (action == Canvas.FIRE || keyCode == KEY_NUM5) {
            cancelSelected(jobs);
        } else if (keyCode == KEY_NUM0) {
            queue.clearFinished();
            if (selectedIdx >= queue.size()) selectedIdx = Math.max(0, queue.size() - 1);
            repaint();
        } else if (keyCode == KEY_NUM2) {
            scrollY = Math.max(0, scrollY - getHeight() / 2);
            repaint();
        } else if (keyCode == KEY_NUM8) {
            scrollY += getHeight() / 2;
            clampScroll();
            repaint();
        }
    }

    private int getGameActionSafe(int keyCode) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}
        if (action != 0) return action;
        switch (keyCode) {
            case -8: return Canvas.FIRE;
            case -1: return Canvas.UP;
            case -6: return Canvas.DOWN;
            case -2: return Canvas.LEFT;
            case -5: return Canvas.RIGHT;
        }
        return 0;
    }

    protected void pointerPressed(int px, int py) {
        int hH = fontBold.getHeight() + 12;
        int sH = fontSmall.getHeight() + 8;
        int vH = getHeight() - sH;
        if (py < hH || py >= vH) return;
        int y = hH + 2 - scrollY;
        QueueJob[] jobs = queue.getJobs();
        for (int i = 0; i < jobs.length; i++) {
            if (py >= y && py < y + ROW_H) { selectedIdx = i; repaint(); return; }
            y += ROW_H + 2;
        }
    }

    protected void pointerReleased(int px, int py) {
        int hH = fontBold.getHeight() + 12;
        int sH = fontSmall.getHeight() + 8;
        int vH = getHeight() - sH;
        if (py < hH || py >= vH) return;
        int y = hH + 2 - scrollY;
        QueueJob[] jobs = queue.getJobs();
        for (int i = 0; i < jobs.length; i++) {
            if (py >= y && py < y + ROW_H) {
                if (i == selectedIdx) cancelSelected(jobs);
                else { selectedIdx = i; repaint(); }
                return;
            }
            y += ROW_H + 2;
        }
    }

    private void cancelSelected(QueueJob[] jobs) {
        if (selectedIdx >= 0 && selectedIdx < jobs.length) {
            queue.cancel(selectedIdx);
            repaint();
        }
    }

    // ---- Commands ----

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            HtmlPageCanvas b = VideoProxyBrowserMIDlet.getBrowser();
            if (b != null) midlet.getDisplay().setCurrent(b);
        } else if (c == clearCmd) {
            queue.clearFinished();
            if (selectedIdx >= queue.size()) selectedIdx = Math.max(0, queue.size() - 1);
            repaint();
        } else if (c == cancelItemCmd) {
            QueueJob[] jobs = queue.getJobs();
            cancelSelected(jobs);
        }
    }

    // ---- Scroll helpers ----

    private void ensureVisible(int jobCount) {
        int hH = fontBold.getHeight() + 12;
        int sH = fontSmall.getHeight() + 8;
        int vH = getHeight() - sH - hH;
        int itemY = selectedIdx * (ROW_H + 2);
        if (itemY < scrollY) scrollY = itemY;
        else if (itemY + ROW_H > scrollY + vH) scrollY = itemY + ROW_H - vH;
        if (scrollY < 0) scrollY = 0;
    }

    private void clampScroll() {
        int sH = fontSmall.getHeight() + 8;
        int hH = fontBold.getHeight() + 12;
        int vH = getHeight() - sH - hH;
        int totalH = queue.size() * (ROW_H + 2);
        int max = Math.max(0, totalH - vH);
        if (scrollY > max) scrollY = max;
        if (scrollY < 0)   scrollY = 0;
    }

    // ---- Util ----

    private String truncate(String s, int maxW, Font f) {
        if (s == null) return "";
        if (f.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && f.stringWidth(s + "...") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
