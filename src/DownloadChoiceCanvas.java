/*
 * DashTube v1.0 – DASH ANIMATION V2
 * MIDP 2.0 / CLDC 1.1 + JSR-75
 */

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.io.file.*;
import javax.microedition.media.*;
import javax.microedition.media.control.*;
import javax.microedition.rms.*;
import java.io.*;
import java.util.*;

class DownloadChoiceCanvas extends Canvas implements CommandListener {
    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas          browser;
    private PageItem                item;
    private FileManager             fm;
    private int    selectedIndex = 0;
    private String filename;
    private String[] labels, subLabels;
    private Command selectCmd, backCmd;
    private Font fontBold, fontNormal, fontSmall;

    private static final int C_BG     = 0x0F0F0F;
    private static final int C_CARD0  = 0x1A1A1A;
    private static final int C_CARD1  = 0x161616;
    private static final int C_ACCENT = 0xD42B52;
    private static final int C_TEXT   = 0xE8E8E8;
    private static final int C_SUB    = 0x888888;
    private static final int C_SELBG  = 0xD42B52;
    private static final int C_SELTXT = 0xFFFFFF;
    private static final int C_GREEN  = 0x22BB66;
    private static final int C_BLUE   = 0x3A6EDB;
    private static final int C_GOLD   = 0xE8A020;

    // Option indices — kept as constants so activate() is readable
    private static final int OPT_STREAM  = 0;
    private static final int OPT_SAVE    = 1;
    private static final int OPT_QUEUE   = 2;  // NEW: add to download queue
    private static final int OPT_CANCEL  = 3;

    public DownloadChoiceCanvas(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas browser,
                                PageItem item, FileManager fm) {
        this.midlet  = midlet;
        this.browser = browser;
        this.item    = item;
        this.fm      = fm;
        fontBold   = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,  Font.SIZE_SMALL);
        fontNormal = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontSmall  = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        filename = FileManager.filenameFromUrl(item.url);

        // How many items already waiting in queue?
        int queueSize = DownloadQueue.getInstance(midlet).size();
        String queueSub = fm.isReady()
            ? (queueSize > 0 ? queueSize + " item(s) already queued" : "No items queued yet")
            : "Storage unavailable";

        labels = new String[] {
            "\u25B6  Stream / Play",
            "\u2193  Save to Storage",
            "\u23F3  Add to Queue",        // NEW
            "\u2715  Cancel"
        };
        subLabels = new String[] {
            "Stream directly on device",
            fm.isReady() ? fm.getRootDescription() : "Storage unavailable",
            queueSub,
            "Return to browser"
        };

        selectCmd = new Command("Select", Command.OK,   1);
        backCmd   = new Command("Back",   Command.BACK, 2);
        addCommand(selectCmd);
        addCommand(backCmd);
        setCommandListener(this);
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(C_BG); g.fillRect(0, 0, w, h);

        // Header
        int headerH = fontBold.getHeight() + 12;
        g.setColor(C_ACCENT); g.fillRect(0, 0, w, headerH);
        g.setColor(C_SELTXT); g.setFont(fontBold);
        g.drawString("Download Options", w / 2, 6, Graphics.TOP | Graphics.HCENTER);
        int y = headerH + 6;

        // Filename – max 2 lines, truncate with "..." if longer
        g.setFont(fontSmall); g.setColor(C_SUB);
        int mw = w - 16;
        String fn = filename != null ? filename : "";
        int fnh = fontSmall.getHeight() + 1;
        int linesDone = 0;
        int ls = 0;
        while (ls < fn.length() && linesDone < 2) {
            // find how many chars fit on this line
            int end = ls + 1;
            while (end <= fn.length() && fontSmall.substringWidth(fn, ls, end - ls) <= mw) end++;
            end--; // last char that fits
            if (end <= ls) end = ls + 1; // at least 1 char
            boolean isLast = (end >= fn.length());
            if (isLast) {
                // fits completely
                g.drawSubstring(fn, ls, fn.length() - ls, 8, y, Graphics.TOP | Graphics.LEFT);
                y += fnh; ls = fn.length();
            } else if (linesDone == 1) {
                // second line – need ellipsis
                String ellipsis = "...";
                int eW = fontSmall.stringWidth(ellipsis);
                int cut = end;
                while (cut > ls && fontSmall.substringWidth(fn, ls, cut - ls) + eW > mw) cut--;
                g.drawSubstring(fn, ls, cut - ls, 8, y, Graphics.TOP | Graphics.LEFT);
                g.drawString(ellipsis, 8 + fontSmall.substringWidth(fn, ls, cut - ls), y, Graphics.TOP | Graphics.LEFT);
                y += fnh; ls = fn.length();
            } else {
                g.drawSubstring(fn, ls, end - ls, 8, y, Graphics.TOP | Graphics.LEFT);
                y += fnh; ls = end;
            }
            linesDone++;
        }
        y += 4;
        g.setColor(0x222222); g.drawLine(8, y, w - 8, y); y += 6;

        // Option rows
        int rowPad = 6;
        int rowH   = fontBold.getHeight() + fontSmall.getHeight() + rowPad * 2 + 4;
        for (int i = 0; i < labels.length; i++) {
            boolean sel = (i == selectedIndex);
            g.setColor(sel ? C_SELBG : (i % 2 == 0 ? C_CARD0 : C_CARD1));
            g.fillRoundRect(8, y, w - 16, rowH, 8, 8);

            // Left accent stripe (state colour)
            if (!sel) {
                int sc = rowStripeColor(i);
                g.setColor(sc); g.fillRoundRect(8, y, 4, rowH, 4, 4);
            }

            // Label
            g.setFont(fontBold); g.setColor(sel ? C_SELTXT : C_TEXT);
            g.drawString(labels[i], 20, y + rowPad, Graphics.TOP | Graphics.LEFT);

            // Sub-label (wrapped to 1 line max inside row)
            g.setFont(fontSmall); g.setColor(sel ? 0xDDDDDD : C_SUB);
            String sub = subLabels[i];
            int sl2 = 0, sy = y + rowPad + fontBold.getHeight() + 2;
            int smw = w - 44;
            for (int j = 1; j <= sub.length(); j++) {
                boolean last = (j == sub.length());
                if (fontSmall.substringWidth(sub, sl2, j - sl2) > smw || last) {
                    int len = last ? j - sl2 : j - 1 - sl2;
                    if (len > 0 && sy < y + rowH) {
                        g.drawSubstring(sub, sl2, len, 20, sy, Graphics.TOP | Graphics.LEFT);
                        sy += fontSmall.getHeight() + 1;
                    }
                    sl2 = last ? j : j - 1;
                }
            }
            y += rowH + 4;
        }

        g.setFont(fontSmall); g.setColor(0x444444);
        g.drawString("UP/DOWN select, FIRE confirm", w / 2, h - fontSmall.getHeight() - 5,
                     Graphics.TOP | Graphics.HCENTER);
    }

    private int rowStripeColor(int i) {
        switch (i) {
            case OPT_STREAM: return C_BLUE;
            case OPT_SAVE:   return C_GREEN;
            case OPT_QUEUE:  return C_GOLD;
            default:         return 0x444444;
        }
    }

    protected void keyPressed(int keyCode) {
        int action = getGameActionSafe(keyCode);
        if      (action == Canvas.UP)   { if (selectedIndex > 0)              { selectedIndex--; repaint(); } }
        else if (action == Canvas.DOWN) { if (selectedIndex < labels.length-1){ selectedIndex++; repaint(); } }
        else if (action == Canvas.FIRE) { activate(); }
        else if (keyCode == KEY_NUM5)   { activate(); }
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

    protected void pointerReleased(int px, int py) {
        int headerH = fontBold.getHeight() + 12;
        int rowPad  = 6;
        int rowH    = fontBold.getHeight() + fontSmall.getHeight() + rowPad * 2 + 4;
        int fnh     = fontSmall.getHeight() + 1;
        // filename: max 2 lines + 4 gap + divider (1) + 6 gap
        int y = headerH + 6 + fnh * 2 + 4 + 1 + 6;
        for (int i = 0; i < labels.length; i++) {
            if (py >= y && py < y + rowH) {
                selectedIndex = i;
                repaint();
                activate();
                return;
            }
            y += rowH + 4;
        }
    }

    private void activate() {
        switch (selectedIndex) {
            case OPT_STREAM:
                midlet.getDisplay().setCurrent(browser);
                browser.playMedia(item.url);
                break;

            case OPT_SAVE:
                midlet.getDisplay().setCurrent(browser);
                browser.startDownloadPublic(item.url, filename);
                break;

            case OPT_QUEUE:
                // Add to queue and switch to the queue screen
                DownloadQueue q = DownloadQueue.getInstance(midlet);
                q.add(item.url, filename);
                midlet.getDisplay().setCurrent(q.getScreen());
                break;

            case OPT_CANCEL:
            default:
                midlet.getDisplay().setCurrent(browser);
                break;
        }
    }

    public void commandAction(Command c, Displayable d) {
        if      (c == backCmd)   midlet.getDisplay().setCurrent(browser);
        else if (c == selectCmd) activate();
    }
}

// ======================== About Canvas ========================