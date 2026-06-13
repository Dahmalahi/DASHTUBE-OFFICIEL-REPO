/*
 * MusicSearchScreen.java  –  DashTube v1.0
 * MIDP 2.0 / CLDC 1.1 compatible
 *
 * NOTE: This class is kept for source compatibility only.
 * The app now uses MusicCanvas (in MusicItem.java) which provides the full
 * music search → results → track detail → download/stream pipeline
 * against the correct endpoint: http://video.2yxa.mobi/mus.php
 *
 * VideoProxyBrowserMIDlet.showMusicSearch() creates a MusicCanvas and
 * displays it; this class is no longer instantiated by the app.
 */

import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;
import java.util.Vector;

/**
 * Legacy stub – not used at runtime.
 * All music functionality is in MusicCanvas (MusicItem.java).
 */
public class MusicSearchScreen extends Canvas implements CommandListener {

    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas parentCanvas;
    private Command backCmd;
    private Font fontBold, fontNormal, fontSmall;

    private static final int C_BG     = 0x0F0F0F;
    private static final int C_ACCENT = 0xD42B52;
    private static final int C_TEXT   = 0xE8E8E8;
    private static final int C_SUB    = 0x888888;

    public MusicSearchScreen(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas parent) {
        this.midlet = midlet;
        this.parentCanvas = parent;
        fontBold   = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,  Font.SIZE_SMALL);
        fontNormal = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontSmall  = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        backCmd = new Command("Back", Command.BACK, 1);
        addCommand(backCmd);
        setCommandListener(this);
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(C_BG); g.fillRect(0, 0, w, h);
        int hH = fontBold.getHeight() + 10;
        g.setColor(C_ACCENT); g.fillRect(0, 0, w, hH);
        g.setColor(0xFFFFFF); g.setFont(fontBold);
        g.drawString("Music Search", w / 2, 5, Graphics.TOP | Graphics.HCENTER);
        g.setFont(fontNormal); g.setColor(C_TEXT);
        g.drawString("Redirecting to Music module...", w / 2, h / 2, Graphics.TOP | Graphics.HCENTER);
        g.setFont(fontSmall); g.setColor(C_SUB);
        g.drawString("Press Back", w / 2, h / 2 + fontNormal.getHeight() + 8, Graphics.TOP | Graphics.HCENTER);
    }

    public void commandAction(Command c, Displayable d) {
        midlet.getDisplay().setCurrent(parentCanvas);
    }
}

// ======================== Music Result Bean (legacy) ========================

class MusicResult {
    String title;
    String artist;
    String duration;
    String pageUrl;
    String videoId;

    MusicResult(String title, String artist, String duration,
                String pageUrl, String videoId) {
        this.title    = title    != null ? title    : "";
        this.artist   = artist   != null ? artist   : "";
        this.duration = duration != null ? duration : "";
        this.pageUrl  = pageUrl  != null ? pageUrl  : "";
        this.videoId  = videoId  != null ? videoId  : "";
    }
}
