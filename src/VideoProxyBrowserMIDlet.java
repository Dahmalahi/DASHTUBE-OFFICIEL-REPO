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

public class VideoProxyBrowserMIDlet extends MIDlet {

    private Display           display;
    private HtmlPageCanvas    browserCanvas;
    private LoginScreen       loginScreen;
    private MusicCanvas       musicCanvas;   // upgraded: MusicCanvas replaces MusicSearchScreen
    private ImageSearchCanvas imageCanvas;   // image search

    static  HtmlPageCanvas    staticBrowser;

    public VideoProxyBrowserMIDlet() {
        display       = Display.getDisplay(this);
        browserCanvas = new HtmlPageCanvas(this);
        staticBrowser = browserCanvas;
        loginScreen   = new LoginScreen(this, browserCanvas);
    }

    public Display getDisplay()               { return display; }
    public static HtmlPageCanvas getBrowser() { return staticBrowser; }

    public void startApp()            { display.setCurrent(loginScreen); }
    public void pauseApp()            {}
    public void destroyApp(boolean u) { browserCanvas.shutdown(); }

    /**
     * Called by HtmlPageCanvas when user picks "Music Search".
     * Lazily creates MusicCanvas (allocate once, reuse).
     */
    public void showMusicSearch() {
        if (musicCanvas == null) {
            musicCanvas = new MusicCanvas(this, browserCanvas);
        }
        musicCanvas.showSearch();
        display.setCurrent(musicCanvas);
    }

    /** Called by HtmlPageCanvas when user picks "Image Search". */
    public void showImageSearch() {
        if (imageCanvas == null) {
            imageCanvas = new ImageSearchCanvas(this, browserCanvas);
        }
        imageCanvas.showSearch();
        display.setCurrent(imageCanvas);
    }
}
