/*
 * DashTube v1.0 – DASH ANIMATION V2
 * MIDP 2.0 / CLDC 1.1 + JSR-75
 * Music Module: Search, Results, Track Detail, Download
 */

import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;
import java.util.*;

// ======================== Music Item ========================

class MusicItem {
    static final int TYPE_TRACK   = 0;  // search result row
    static final int TYPE_HEADER  = 1;  // section label
    static final int TYPE_ACTION  = 2;  // download/stream/convert link
    static final int TYPE_PAGING  = 3;  // next/prev page link

    int    type;
    String title;    // track title or label text
    String artist;   // artist name (TYPE_TRACK only)
    String duration; // e.g. "03:45"
    String url;      // href target
    String musicId;  // 2yxa music id (TYPE_TRACK only)
    boolean isCompatible; // android/mp3 or android/mp4 link

    MusicItem(int t, String title) {
        this.type = t; this.title = title;
    }
    MusicItem(int t, String title, String url) {
        this.type = t; this.title = title; this.url = url;
    }
}

// ======================== Music Page Canvas ========================

class MusicCanvas extends Canvas implements CommandListener {

    // ---- Screens / states ----
    private static final int STATE_SEARCH  = 0;  // search form
    private static final int STATE_RESULTS = 1;  // search results list
    private static final int STATE_TRACK   = 2;  // track detail + download links

    private int state = STATE_SEARCH;

    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas          browser;

    // Search state
    private String queryVal   = "";
    private String sortVal    = "0";    // 0=date,1=duration,2=rating
    private String serverVal  = "ok";
    private int    currentPage = 1;

    // Track state
    private String trackTitle  = "";
    private String trackArtist = "";
    private String trackDur    = "";
    private String trackId     = "";
    private String backResultsUrl = "";  // url to restore when back from track

    // List
    private Vector  items        = new Vector();
    private int     selectedIndex = 0;
    private int     scrollY       = 0;

    // Loading
    private boolean loading  = false;
    private String  status   = "Music Search";

    // Fonts & palette
    private Font fontBold, fontNormal, fontSmall;
    private static final int C_BG     = 0x0F0F0F;
    private static final int C_CARD0  = 0x1A1A1A;
    private static final int C_CARD1  = 0x161616;
    private static final int C_ACCENT = 0xD42B52;
    private static final int C_TEXT   = 0xE8E8E8;
    private static final int C_SUB    = 0x888888;
    private static final int C_GREEN  = 0x22BB66;
    private static final int C_BLUE   = 0x3A6EDB;
    private static final int C_GOLD   = 0xE8A020;
    private static final int C_SELBG  = 0xD42B52;
    private static final int C_SELTXT = 0xFFFFFF;
    private static final int C_STBG   = 0x0A0A0A;
    private static final int C_STTXT  = 0xCCCCCC;
    private static final int ACCENT_W = 3;

    // Commands
    private Command backCmd, searchCmd, refreshCmd, newSearchCmd;
    private Command sortCmd, serverCmd;
    private Command viewQueueCmd;   // show multi-download queue screen

    public MusicCanvas(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas browser) {
        this.midlet  = midlet;
        this.browser = browser;
        fontBold   = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,  Font.SIZE_SMALL);
        fontNormal = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontSmall  = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);

        backCmd      = new Command("Back",       Command.BACK,   1);
        searchCmd    = new Command("Search",     Command.OK,     1);
        newSearchCmd = new Command("New Search", Command.SCREEN, 2);
        refreshCmd   = new Command("Refresh",    Command.SCREEN, 3);
        sortCmd      = new Command("Sort By",    Command.SCREEN, 4);
        serverCmd    = new Command("Server",     Command.SCREEN, 5);
        viewQueueCmd = new Command("View Queue", Command.SCREEN, 6);

        addCommand(backCmd);
        addCommand(searchCmd);
        addCommand(viewQueueCmd);
        setCommandListener(this);
        buildSearchScreen();
    }

    // ----------------------------------------------------------------
    // Public entry points
    // ----------------------------------------------------------------

    /** Called from HtmlPageCanvas menu. Shows search screen. */
    public void showSearch() {
        state = STATE_SEARCH;
        selectedIndex = 0; scrollY = 0;
        removeCommand(newSearchCmd); removeCommand(refreshCmd);
        removeCommand(sortCmd);      removeCommand(serverCmd);
        addCommand(searchCmd);
        buildSearchScreen();
        repaint();
    }

    // ----------------------------------------------------------------
    // Screen builders
    // ----------------------------------------------------------------

    private void buildSearchScreen() {
        items.removeAllElements();
        MusicItem hdr = new MusicItem(MusicItem.TYPE_HEADER, "Music Search - 2yxa.mobi");
        items.addElement(hdr);
        MusicItem qi = new MusicItem(MusicItem.TYPE_ACTION,
            "[ Query: " + (queryVal.length() > 0 ? queryVal : "(tap to enter)") + " ]");
        qi.url = "ACTION:QUERY";
        items.addElement(qi);
        MusicItem si = new MusicItem(MusicItem.TYPE_ACTION,
            "Sort: " + getSortLabel(sortVal));
        si.url = "ACTION:SORT";
        items.addElement(si);
        MusicItem sv = new MusicItem(MusicItem.TYPE_ACTION,
            "Server: " + getServerLabel(serverVal));
        sv.url = "ACTION:SERVER";
        items.addElement(sv);
        MusicItem go = new MusicItem(MusicItem.TYPE_ACTION, "\u25B6  Search Music");
        go.url = "ACTION:DOSEARCH";
        items.addElement(go);
        status = "Enter query and press Search";
    }

    private void buildResultsScreen(String html) {
        items.removeAllElements();
        String lower = html.toLowerCase();

        // Count info e.g. "1-10 of 200"
        String countInfo = extractCountInfo(lower, html);
        if (countInfo != null)
            items.addElement(new MusicItem(MusicItem.TYPE_HEADER, countInfo));

        // Parse hulk divs → track rows
        int pos = 0;
        while (pos < lower.length()) {
            int di = lower.indexOf("class=\"vse\"", pos);
            if (di < 0) break;
            int as = StringUtils.lastIndexOf(lower, "<a ", di);
            if (as < 0) { pos = di + 11; continue; }
            int ae = lower.indexOf(">", as);
            if (ae < 0) { pos = di + 11; continue; }
            String href = SiteParser.extractAttr(html.substring(as, ae + 1), "href");
            if (href == null) { pos = ae + 1; continue; }
            href = HtmlDecoder.decode(href);
            int ac = lower.indexOf("</a>", ae);
            if (ac < 0) { pos = ae + 1; continue; }
            String inner = html.substring(ae + 1, ac);
            String innerL = inner.toLowerCase();

            // Artist from class="sm zel"
            String artist = "";
            int zi = innerL.indexOf("class=\"sm zel\"");
            if (zi < 0) zi = innerL.indexOf("class='sm zel'");
            if (zi >= 0) {
                int zo = innerL.indexOf(">", zi);
                int zc = innerL.indexOf("</span>", zi);
                if (zo >= 0 && zc > zo) artist = HtmlDecoder.decode(inner.substring(zo + 1, zc).trim());
            }

            // Duration from class="sm sin"
            String dur = "";
            int duri = innerL.indexOf("class=\"sm sin\"");
            if (duri < 0) duri = innerL.indexOf("class='sm sin'");
            if (duri >= 0) {
                int duro = innerL.indexOf(">", duri);
                int durc = innerL.indexOf("</span>", duri);
                if (duro >= 0 && durc > duro) dur = inner.substring(duro + 1, durc).trim();
            }

            // Title from <b>...</b>
            String title = "";
            int bi = innerL.indexOf("<b>");
            if (bi >= 0) {
                int be = innerL.indexOf("</b>", bi);
                if (be > bi) title = HtmlDecoder.decode(SiteParser.stripTagsStatic(inner.substring(bi + 3, be)).trim());
            }
            if (title.length() == 0) title = HtmlDecoder.decode(SiteParser.stripTagsStatic(inner).trim());
            if (title.length() == 0) { pos = ac + 4; continue; }

            // Extract music id from href like /mus.php?id=123456&database&poisk=ok
            String musicId = extractMusicId(href);
            String absHref = makeMusAbsolute(href);

            MusicItem mi = new MusicItem(MusicItem.TYPE_TRACK, title, absHref);
            mi.artist   = artist;
            mi.duration = dur;
            mi.musicId  = musicId;
            items.addElement(mi);
            pos = ac + 4;
        }

        // Pagination: next link
        String nextUrl = extractNextPage(lower, html);
        if (nextUrl != null) {
            MusicItem pg = new MusicItem(MusicItem.TYPE_PAGING, ">> Next Page >>", makeMusAbsolute(nextUrl));
            items.addElement(pg);
        }

        if (items.size() == 0 || (items.size() == 1 &&
            ((MusicItem)items.elementAt(0)).type == MusicItem.TYPE_HEADER)) {
            items.addElement(new MusicItem(MusicItem.TYPE_HEADER, "No results found."));
        }
        status = "Results: " + (items.size()) + " tracks";
    }

    private void buildTrackScreen(String html) {
        items.removeAllElements();
        String lower = html.toLowerCase();

        // Title from belowtitle span
        String title = extractBelowTitle(html, lower);
        if (title == null || title.length() == 0) title = trackTitle;

        // Performed by
        String artist = "";
        int pbi = lower.indexOf("<b>performed by</b>");
        if (pbi >= 0) {
            int si = lower.indexOf("class=\"sin sm\"", pbi);
            if (si < 0) si = lower.indexOf("class='sin sm'", pbi);
            if (si >= 0) {
                int so = lower.indexOf(">", si);
                int sc = lower.indexOf("</span>", si);
                if (so >= 0 && sc > so)
                    artist = HtmlDecoder.decode(SiteParser.stripTagsStatic(html.substring(so + 1, sc)).trim());
            }
        }

        // Duration
        String dur = "";
        int duri = lower.indexOf("<b>duration</b>");
        if (duri >= 0) {
            int si = lower.indexOf("class=\"sin sm\"", duri);
            if (si < 0) si = lower.indexOf("class='sin sm'", duri);
            if (si >= 0) {
                int so = lower.indexOf(">", si);
                int sc = lower.indexOf("</span>", duri);
                if (so >= 0 && sc > so)
                    dur = html.substring(so + 1, sc).trim();
            }
        }

        // Update cached track info
        if (title.length() > 0)  trackTitle  = title;
        if (artist.length() > 0) trackArtist = artist;
        if (dur.length() > 0)    trackDur    = dur;

        // Header card
        MusicItem hdr = new MusicItem(MusicItem.TYPE_HEADER, title);
        items.addElement(hdr);
        if (artist.length() > 0)
            items.addElement(new MusicItem(MusicItem.TYPE_HEADER, "\u266A " + artist + "  " + dur));

        boolean fileReady = lower.indexOf("file is ready") >= 0;

        // --- Download links from page ---
        // Compatible android links (best for J2ME)
        Vector compatLinks = new Vector();
        Vector origLinks   = new Vector();
        Vector createLinks = new Vector();

        int pos = 0;
        while (pos < lower.length()) {
            int hDq = lower.indexOf("href=\"", pos);
            int hSq = lower.indexOf("href='", pos);
            if (hDq < 0 && hSq < 0) break;
            int hIdx; char quote;
            if      (hDq < 0)    { hIdx = hSq; quote = '\''; }
            else if (hSq < 0)    { hIdx = hDq; quote = '"';  }
            else if (hDq <= hSq) { hIdx = hDq; quote = '"';  }
            else                 { hIdx = hSq; quote = '\''; }
            int us = hIdx + 6;
            int ue = html.indexOf(quote, us);
            if (ue < 0) { pos = us + 1; continue; }
            String rawUrl = HtmlDecoder.decode(html.substring(us, ue).trim());
            if (rawUrl.startsWith("/")) rawUrl = "https://video.2yxa.mobi" + rawUrl;
            else if (!rawUrl.startsWith("http")) { pos = ue + 1; continue; }

            // Get link text
            String linkText = "";
            int gt = lower.indexOf(">", ue);
            int ac = lower.indexOf("</a>", ue);
            if (gt >= 0 && ac > gt)
                linkText = HtmlDecoder.decode(SiteParser.stripTagsStatic(html.substring(gt + 1, ac)).trim());

            String lo = rawUrl.toLowerCase();
            // skip junk (telegram, attach.php with telegram)
            if (lo.indexOf("&telegram") >= 0 || lo.indexOf("?telegram") >= 0
                || lo.indexOf("attach.php") >= 0) {
                pos = ue + 1; continue;
            }

            boolean isAndroid  = lo.indexOf("/android/mp3/") >= 0 || lo.indexOf("/android/mp4/") >= 0;
            boolean isOriginal = !isAndroid &&
                (lo.indexOf("/mp3/2yxa_mobi_") >= 0 || lo.indexOf("/mp4/2yxa_mobi_") >= 0);
            boolean isCreate   = lo.indexOf("/mus.php") >= 0 && lo.indexOf("&dw") >= 0
                && lo.indexOf("type=") >= 0 && lo.indexOf("rtsp") < 0;

            if (isAndroid && !containsUrl(compatLinks, rawUrl))
                addPair(compatLinks, rawUrl, linkText);
            else if (isOriginal && !containsUrl(origLinks, rawUrl))
                addPair(origLinks, rawUrl, linkText);
            else if (isCreate && !containsUrl(createLinks, rawUrl))
                addPair(createLinks, rawUrl, linkText);

            pos = ue + 1;
        }

        if (fileReady)
            items.addElement(new MusicItem(MusicItem.TYPE_HEADER, "\u2713 File is ready!"));

        if (compatLinks.size() > 0) {
            items.addElement(new MusicItem(MusicItem.TYPE_HEADER, "-- Download (Compatible) --"));
            for (int i = 0; i < compatLinks.size(); i += 2) {
                String u = (String) compatLinks.elementAt(i);
                String t = (String) compatLinks.elementAt(i + 1);
                MusicItem dl = new MusicItem(MusicItem.TYPE_ACTION,
                    "\u2193 " + buildMusicLabel(u, t), u);
                dl.isCompatible = true;
                items.addElement(dl);
            }
        }
        if (origLinks.size() > 0) {
            items.addElement(new MusicItem(MusicItem.TYPE_HEADER, "-- Download (Original) --"));
            for (int i = 0; i < origLinks.size(); i += 2) {
                String u = (String) origLinks.elementAt(i);
                String t = (String) origLinks.elementAt(i + 1);
                MusicItem dl = new MusicItem(MusicItem.TYPE_ACTION,
                    "\u2193 " + buildMusicLabel(u, t), u);
                dl.isCompatible = false;
                items.addElement(dl);
            }
        }
        if (createLinks.size() > 0) {
            items.addElement(new MusicItem(MusicItem.TYPE_HEADER, "-- Create / Convert --"));
            for (int i = 0; i < createLinks.size(); i += 2) {
                String u = (String) createLinks.elementAt(i);
                String t = (String) createLinks.elementAt(i + 1);
                MusicItem cl = new MusicItem(MusicItem.TYPE_ACTION,
                    "\u25B6 " + (t.length() > 0 ? t : "Convert"), u);
                items.addElement(cl);
            }
        }

        // If nothing found yet — offer create links via mus.php?id=...&type=X&dw
        if (compatLinks.size() == 0 && origLinks.size() == 0 && createLinks.size() == 0) {
            if (trackId.length() > 0) {
                items.addElement(new MusicItem(MusicItem.TYPE_HEADER, "-- Create Download --"));
                String[][] types = {
                    {"10", "Create MP3 (original ~320kb/s)"},
                    {"16", "Create MP3 (192kb/s)"},
                    {"11", "Create MP3 (128kb/s)"},
                    {"12", "Create MP3 (64kb/s)"},
                    {"13", "Create MP3 (32kb/s)"},
                    {"17", "Create AAC (24kb/s)"},
                };
                for (int i = 0; i < types.length; i++) {
                    String u = "http://video.2yxa.mobi/mus.php?id=" + trackId
                        + "&type=" + types[i][0] + "&dw";
                    items.addElement(new MusicItem(MusicItem.TYPE_ACTION,
                        "\u25B6 " + types[i][1], u));
                }
            } else {
                items.addElement(new MusicItem(MusicItem.TYPE_HEADER,
                    "No download links yet. Try Refresh."));
            }
        }

        status = fileReady ? "Ready! Choose format below" : "Track loaded";
    }

    // ----------------------------------------------------------------
    // HTTP fetchers
    // ----------------------------------------------------------------

    private void doSearch(final int page) {
        if (queryVal.trim().length() == 0) {
            status = "Please enter a query first!";
            repaint(); return;
        }
        loading = true; status = "Searching...";
        repaint();
        StringBuffer url = new StringBuffer("http://video.2yxa.mobi/mus.php?");
        url.append("query=").append(UrlEncoder.encode(queryVal.trim()));
        url.append("&sort=").append(sortVal);
        url.append("&server=").append(serverVal);
        url.append("&poisk=").append(serverVal);
        if (page > 1) url.append("&page=").append(page);
        final String fetchUrl = url.toString();
        backResultsUrl = fetchUrl;
        new Thread(new HttpRequestRunnable(fetchUrl, null, false, new HttpCallback() {
            public void onSuccess(String content, String base) {
                final String finalContent = content; // FIXED: local final copy for anonymous inner thread
                loading = false;
                state = STATE_RESULTS;
                currentPage = page;
                midlet.getDisplay().callSerially(new Runnable() { public void run() {
                    buildResultsScreen(finalContent);
                    selectedIndex = 0; scrollY = 0;
                    removeCommand(searchCmd);
                    addCommand(newSearchCmd);
                    addCommand(refreshCmd);
                    repaint();
                }});
            }
            public void onError(final String msg) {
                loading = false;
                midlet.getDisplay().callSerially(new Runnable() { public void run() {
                    status = "Error: " + msg; repaint();
                }});
            }
        })).start();
    }

    private void openTrack(final MusicItem track) {
        if (track.url == null) return;
        loading = true;
        trackTitle  = track.title;
        trackArtist = track.artist != null ? track.artist : "";
        trackDur    = track.duration != null ? track.duration : "";
        trackId     = track.musicId != null ? track.musicId : "";
        status = "Loading track..."; repaint();
        final String fetchUrl = UrlUtils.stripProxy(track.url);
        new Thread(new HttpRequestRunnable(fetchUrl, null, false, new HttpCallback() {
            public void onSuccess(String content, String base) {
                final String finalContent = content; // FIXED: local final copy for anonymous inner thread
                loading = false;
                state = STATE_TRACK;
                midlet.getDisplay().callSerially(new Runnable() { public void run() {
                    buildTrackScreen(finalContent);
                    selectedIndex = 0; scrollY = 0;
                    removeCommand(newSearchCmd);
                    addCommand(newSearchCmd);
                    addCommand(refreshCmd);
                    repaint();
                }});
            }
            public void onError(final String msg) {
                loading = false;
                // Build a minimal track screen so user can still try create-links
                state = STATE_TRACK;
                midlet.getDisplay().callSerially(new Runnable() { public void run() {
                    buildTrackScreen(""); // empty → shows create links by trackId
                    status = "Load error. Try create links below.";
                    repaint();
                }});
            }
        })).start();
    }

    /** Navigate to a raw mus.php page (pagination / conversion trigger) */
    private void openMusUrl(final String url, final boolean isTrack) {
        loading = true; status = "Loading..."; repaint();
        final String fetchUrl = UrlUtils.stripProxy(url);
        new Thread(new HttpRequestRunnable(fetchUrl, null, false, new HttpCallback() {
            public void onSuccess(String content, String base) {
                final String finalContent = content; // FIXED: local final copy for anonymous inner thread
                loading = false;
                midlet.getDisplay().callSerially(new Runnable() { public void run() {
                    if (isTrack) {
                        state = STATE_TRACK;
                        buildTrackScreen(finalContent); // FIXED: reference final copy
                    } else {
                        state = STATE_RESULTS;
                        buildResultsScreen(finalContent); // FIXED: reference final copy
                    }
                    selectedIndex = 0; scrollY = 0;
                    repaint();
                }});
            }
            public void onError(final String msg) {
                loading = false;
                midlet.getDisplay().callSerially(new Runnable() { public void run() {
                    status = "Error: " + msg; repaint();
                }});
            }
        })).start();
    }

    // ----------------------------------------------------------------
    // Paint
    // ----------------------------------------------------------------

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        int sH = fontSmall.getHeight() + 8;
        int vH = h - sH;

        g.setColor(C_BG); g.fillRect(0, 0, w, h);

        // Header bar
        int hdrH = fontBold.getHeight() + 10;
        g.setColor(C_ACCENT); g.fillRect(0, 0, w, hdrH);
        g.setColor(C_SELTXT); g.setFont(fontBold);
        String hdrTitle = (state == STATE_SEARCH) ? "Music Search"
            : (state == STATE_RESULTS) ? "Music Results"
            : trackTitle.length() > 0 ? truncate(trackTitle, w - 8, fontBold) : "Track";
        g.drawString(hdrTitle, w / 2, 5, Graphics.TOP | Graphics.HCENTER);

        if (loading) {
            int ow = 100, oh = 22, ox = (w - ow) / 2, oy = vH / 2 - oh / 2;
            g.setColor(0x1A1A1A); g.fillRect(ox - 2, oy - 2, ow + 4, oh + 4);
            g.setColor(C_ACCENT); g.drawRect(ox - 2, oy - 2, ow + 4, oh + 4);
            g.setColor(C_TEXT); g.setFont(fontBold);
            g.drawString("Loading...", w / 2, oy + 3, Graphics.TOP | Graphics.HCENTER);
        } else {
            int y = hdrH + 2 - scrollY;
            for (int i = 0; i < items.size(); i++) {
                MusicItem item = (MusicItem) items.elementAt(i);
                int ih = calcItemH(item, w - 8);
                if (y + ih > hdrH && y < vH)
                    drawMusicItem(g, item, 4, y, w - 8, ih, i == selectedIndex, i);
                y += ih + 2;
            }
            // Scrollbar
            int totalH = calcTotalH(w - 8) + hdrH + 4;
            if (totalH > vH) {
                int sbW = 3, sbX = w - sbW - 1, scrollArea = vH - hdrH;
                g.setColor(0x222222); g.fillRect(sbX, hdrH, sbW, scrollArea);
                int thumbH = Math.max(10, (scrollArea * scrollArea) / totalH);
                int maxScroll = totalH - scrollArea;
                int thumbY = hdrH + (maxScroll > 0 ? (scrollY * (scrollArea - thumbH)) / maxScroll : 0);
                if (thumbY < hdrH) thumbY = hdrH;
                if (thumbY + thumbH > hdrH + scrollArea) thumbY = hdrH + scrollArea - thumbH;
                g.setColor(C_ACCENT); g.fillRect(sbX, thumbY, sbW, thumbH);
            }
        }

        // Status bar
        g.setColor(C_STBG); g.fillRect(0, h - sH, w, sH);
        g.setColor(C_ACCENT); g.drawLine(0, h - sH, w, h - sH);
        g.setColor(C_STTXT); g.setFont(fontSmall);
        g.drawString(status, 4, h - sH + 4, Graphics.TOP | Graphics.LEFT);
        // State hint on right
        String hint = state == STATE_SEARCH ? "FIRE=Search" :
                      state == STATE_RESULTS ? "FIRE=Open" : "FIRE=Download";
        g.drawString(hint, w - 4, h - sH + 4, Graphics.TOP | Graphics.RIGHT);
    }

    private int calcTotalH(int maxW) {
        int total = 0;
        for (int i = 0; i < items.size(); i++)
            total += calcItemH((MusicItem) items.elementAt(i), maxW) + 2;
        return total;
    }

    private int calcItemH(MusicItem item, int maxW) {
        int bh = fontBold.getHeight() + 2;
        int sh = fontSmall.getHeight() + 2;
        switch (item.type) {
            case MusicItem.TYPE_TRACK:
                return bh + sh + 10;  // title + artist/duration
            case MusicItem.TYPE_HEADER:
                return sh + 6;
            case MusicItem.TYPE_ACTION:
                return bh + 8;
            case MusicItem.TYPE_PAGING:
                return fontNormal.getHeight() + 8;
            default:
                return fontNormal.getHeight() + 6;
        }
    }

    private void drawMusicItem(Graphics g, MusicItem item,
                               int x, int y, int maxW, int itemH,
                               boolean sel, int idx) {
        int bg;
        switch (item.type) {
            case MusicItem.TYPE_TRACK:   bg = (idx % 2 == 0) ? C_CARD0 : C_CARD1; break;
            case MusicItem.TYPE_HEADER:  bg = 0x111111; break;
            case MusicItem.TYPE_ACTION:  bg = 0x0A1A12; break;
            case MusicItem.TYPE_PAGING:  bg = 0x0F1F0F; break;
            default:                     bg = C_BG; break;
        }

        if (sel) { g.setColor(C_SELBG); g.fillRect(x - 4, y, maxW + 8, itemH); }
        else      { g.setColor(bg);     g.fillRect(x - 4, y, maxW + 8, itemH); }

        // Left accent bar
        if (!sel) {
            int barColor;
            switch (item.type) {
                case MusicItem.TYPE_TRACK:  barColor = C_BLUE;  break;
                case MusicItem.TYPE_ACTION: barColor = item.isCompatible ? C_GREEN : C_GOLD; break;
                case MusicItem.TYPE_PAGING: barColor = 0x444466; break;
                default: barColor = -1; break;
            }
            if (barColor >= 0) { g.setColor(barColor); g.fillRect(x - 4, y, ACCENT_W, itemH); }
        }

        if (!sel) { g.setColor(0x1E1E1E); g.drawLine(x - 4, y + itemH - 1, x + maxW + 4, y + itemH - 1); }

        int ty  = y + 4;
        int cx  = x + (item.type == MusicItem.TYPE_HEADER ? 0 : ACCENT_W + 3);
        int cw  = maxW - (item.type == MusicItem.TYPE_HEADER ? 0 : ACCENT_W + 5);
        int tc  = sel ? C_SELTXT : C_TEXT;

        switch (item.type) {
            case MusicItem.TYPE_TRACK: {
                // Title
                g.setFont(fontBold); g.setColor(tc);
                String t = truncate(item.title, cw, fontBold);
                g.drawString(t, cx, ty, Graphics.TOP | Graphics.LEFT);
                ty += fontBold.getHeight() + 2;
                // Artist + duration
                g.setFont(fontSmall); g.setColor(sel ? C_SELTXT : C_SUB);
                String sub = "";
                if (item.artist != null && item.artist.length() > 0) sub += item.artist;
                if (item.duration != null && item.duration.length() > 0) {
                    if (sub.length() > 0) sub += "  \u00B7  ";
                    sub += item.duration;
                }
                if (sub.length() > 0) {
                    g.drawString(truncate(sub, cw, fontSmall), cx, ty, Graphics.TOP | Graphics.LEFT);
                }
                break;
            }
            case MusicItem.TYPE_HEADER: {
                g.setFont(fontSmall); g.setColor(sel ? C_SELTXT : C_SUB);
                g.drawString(truncate(item.title, maxW, fontSmall), x + 4, ty, Graphics.TOP | Graphics.LEFT);
                break;
            }
            case MusicItem.TYPE_ACTION: {
                g.setFont(fontBold); g.setColor(sel ? C_SELTXT : C_GREEN);
                g.drawString(truncate(item.title, cw, fontBold), cx, ty, Graphics.TOP | Graphics.LEFT);
                break;
            }
            case MusicItem.TYPE_PAGING: {
                g.setFont(fontNormal); g.setColor(sel ? C_SELTXT : 0xA0C8FF);
                g.drawString(item.title, cx, ty, Graphics.TOP | Graphics.LEFT);
                break;
            }
        }
    }

    // ----------------------------------------------------------------
    // Key / Touch
    // ----------------------------------------------------------------

    protected void keyPressed(int keyCode) {
        if (loading) return;
        int action = getGameActionSafe(keyCode);
        if (action == Canvas.UP) {
            if (selectedIndex > 0) { selectedIndex--; ensureVisible(); }
            repaint();
        } else if (action == Canvas.DOWN) {
            if (selectedIndex < items.size() - 1) { selectedIndex++; ensureVisible(); }
            repaint();
        } else if (action == Canvas.FIRE || keyCode == KEY_NUM5) {
            activateSelected();
        } else if (keyCode == KEY_NUM2) {
            scrollY = Math.max(0, scrollY - getHeight() / 2);
            clampScroll(); repaint();
        } else if (keyCode == KEY_NUM8) {
            scrollY += getHeight() / 2;
            clampScroll(); repaint();
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
        if (loading) return;
        int hdrH = fontBold.getHeight() + 10;
        int y = hdrH + 2 - scrollY;
        for (int i = 0; i < items.size(); i++) {
            MusicItem item = (MusicItem) items.elementAt(i);
            int ih = calcItemH(item, getWidth() - 8);
            if (py >= y && py < y + ih) { selectedIndex = i; repaint(); return; }
            y += ih + 2;
        }
    }

    protected void pointerReleased(int px, int py) {
        if (loading) return;
        int hdrH = fontBold.getHeight() + 10;
        int y = hdrH + 2 - scrollY;
        for (int i = 0; i < items.size(); i++) {
            MusicItem item = (MusicItem) items.elementAt(i);
            int ih = calcItemH(item, getWidth() - 8);
            if (py >= y && py < y + ih) {
                if (i == selectedIndex) activateSelected();
                else { selectedIndex = i; repaint(); }
                return;
            }
            y += ih + 2;
        }
    }

    private void activateSelected() {
        if (selectedIndex < 0 || selectedIndex >= items.size()) return;
        MusicItem item = (MusicItem) items.elementAt(selectedIndex);

        if (item.type == MusicItem.TYPE_TRACK) {
            openTrack(item);
            return;
        }
        if (item.type == MusicItem.TYPE_PAGING && item.url != null) {
            openMusUrl(item.url, false);
            return;
        }
        if (item.type == MusicItem.TYPE_ACTION) {
            String url = item.url;
            if ("ACTION:QUERY".equals(url)) {
                promptQuery(); return;
            }
            if ("ACTION:SORT".equals(url)) {
                promptSort(); return;
            }
            if ("ACTION:SERVER".equals(url)) {
                promptServer(); return;
            }
            if ("ACTION:DOSEARCH".equals(url)) {
                doSearch(1); return;
            }
            if (url != null) {
                String lo = url.toLowerCase();
                // Conversion triggers: navigate to the page and rebuild track screen
                boolean isCreateLink = lo.indexOf("/mus.php") >= 0 && lo.indexOf("&dw") >= 0;
                if (isCreateLink) {
                    openMusUrl(url, true);
                    return;
                }
                // Direct download/stream link → show choice
                if (lo.indexOf("/android/mp3/") >= 0 || lo.indexOf("/android/mp4/") >= 0
                    || lo.indexOf("/mp3/2yxa_mobi_") >= 0 || lo.indexOf("/mp4/2yxa_mobi_") >= 0) {
                    showMusicDownloadChoice(item);
                    return;
                }
                // Fallback: navigate in browser
                browser.navigateTo(url);
                midlet.getDisplay().setCurrent(browser);
            }
        }
    }

    private void showMusicDownloadChoice(final MusicItem item) {
        // Build a PageItem shim so DownloadChoiceCanvas can handle it
        PageItem pi = new PageItem(PageItem.TYPE_DOWNLOAD, item.title, item.url);
        FileManager fm = FileManager.getInstance();
        midlet.getDisplay().setCurrent(
            new DownloadChoiceCanvas(midlet, browser, pi, fm));
    }

    // ----------------------------------------------------------------
    // Input prompts
    // ----------------------------------------------------------------

    private void promptQuery() {
        final TextBox tb = new TextBox("Music Query", queryVal, 100, TextField.ANY);
        final Command ok  = new Command("Search", Command.OK,     1);
        final Command bk  = new Command("Cancel", Command.CANCEL, 2);
        tb.addCommand(ok); tb.addCommand(bk);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == ok) {
                    queryVal = tb.getString().trim();
                }
                midlet.getDisplay().setCurrent(MusicCanvas.this);
                buildSearchScreen();
                selectedIndex = 0; scrollY = 0;
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    private void promptSort() {
        final String[] labels  = { "By Upload Date", "By Duration", "By Rating" };
        final String[] vals    = { "0", "1", "2" };
        final List list = new List("Sort By", List.IMPLICIT, labels, null);
        for (int i = 0; i < vals.length; i++)
            if (vals[i].equals(sortVal)) { list.setSelectedIndex(i, true); break; }
        final Command bk = new Command("Cancel", Command.CANCEL, 2);
        list.addCommand(bk);
        list.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c != bk) {
                    int idx = list.getSelectedIndex();
                    if (idx >= 0) sortVal = vals[idx];
                }
                midlet.getDisplay().setCurrent(MusicCanvas.this);
                buildSearchScreen();
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(list);
    }

    private void promptServer() {
        final String[] labels = { "Additional (ok)", "Additional (ma)" };
        final String[] vals   = { "ok", "am" };
        final List list = new List("Server", List.IMPLICIT, labels, null);
        for (int i = 0; i < vals.length; i++)
            if (vals[i].equals(serverVal)) { list.setSelectedIndex(i, true); break; }
        final Command bk = new Command("Cancel", Command.CANCEL, 2);
        list.addCommand(bk);
        list.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c != bk) {
                    int idx = list.getSelectedIndex();
                    if (idx >= 0) serverVal = vals[idx];
                }
                midlet.getDisplay().setCurrent(MusicCanvas.this);
                buildSearchScreen();
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(list);
    }

    // ----------------------------------------------------------------
    // Command listener
    // ----------------------------------------------------------------

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            if (state == STATE_TRACK) {
                // Back to results
                if (backResultsUrl.length() > 0) {
                    openMusUrl(backResultsUrl, false);
                } else {
                    state = STATE_RESULTS;
                    selectedIndex = 0; scrollY = 0;
                    repaint();
                }
            } else if (state == STATE_RESULTS) {
                state = STATE_SEARCH;
                removeCommand(newSearchCmd); removeCommand(refreshCmd);
                addCommand(searchCmd);
                buildSearchScreen();
                selectedIndex = 0; scrollY = 0;
                repaint();
            } else {
                midlet.getDisplay().setCurrent(browser);
            }
        } else if (c == searchCmd || c == newSearchCmd) {
            state = STATE_SEARCH;
            removeCommand(newSearchCmd); removeCommand(refreshCmd);
            addCommand(searchCmd);
            buildSearchScreen();
            selectedIndex = 0; scrollY = 0;
            repaint();
        } else if (c == viewQueueCmd) {
            midlet.getDisplay().setCurrent(DownloadQueue.getInstance(midlet).getScreen());
        } else if (c == refreshCmd) {
            if (state == STATE_RESULTS && backResultsUrl.length() > 0) {
                openMusUrl(backResultsUrl, false);
            } else if (state == STATE_TRACK && trackId.length() > 0) {
                openMusUrl("http://video.2yxa.mobi/mus.php?id=" + trackId + "&database&poisk=ok", true);
            }
        }
    }

    // ----------------------------------------------------------------
    // Scroll helpers
    // ----------------------------------------------------------------

    private void ensureVisible() {
        if (selectedIndex < 0 || selectedIndex >= items.size()) return;
        int hdrH = fontBold.getHeight() + 10;
        int sH   = fontSmall.getHeight() + 8;
        int vH   = getHeight() - sH - hdrH;
        int y = 0;
        for (int i = 0; i < selectedIndex; i++)
            y += calcItemH((MusicItem) items.elementAt(i), getWidth() - 8) + 2;
        int selH = calcItemH((MusicItem) items.elementAt(selectedIndex), getWidth() - 8);
        if (y < scrollY) scrollY = y;
        else if (y + selH > scrollY + vH) scrollY = y + selH - vH;
        if (scrollY < 0) scrollY = 0;
    }

    private void clampScroll() {
        int total = calcTotalH(getWidth() - 8);
        int hdrH  = fontBold.getHeight() + 10;
        int sH    = fontSmall.getHeight() + 8;
        int vH    = getHeight() - sH - hdrH;
        int max   = Math.max(0, total - vH);
        if (scrollY > max) scrollY = max;
        if (scrollY < 0)   scrollY = 0;
    }

    // ----------------------------------------------------------------
    // Parse helpers
    // ----------------------------------------------------------------

    private String extractMusicId(String href) {
        if (href == null) return null;
        String lo = href.toLowerCase();
        int idx = lo.indexOf("id=");
        if (idx < 0) return null;
        int s = idx + 3, e = s;
        while (e < href.length() && href.charAt(e) != '&' &&
               href.charAt(e) != '"' && href.charAt(e) != '\'') e++;
        return e > s ? href.substring(s, e) : null;
    }

    private String extractCountInfo(String lower, String html) {
        // "1-10 of 200"
        int ci = lower.indexOf("class=\"sm sin\"");
        if (ci < 0) return null;
        // look for pattern like "1-10 of"
        // scan a narrow window
        int so = lower.indexOf(">", ci);
        int sc = lower.indexOf("</div>", ci);
        if (so < 0 || sc < 0 || sc <= so) return null;
        String t = SiteParser.stripTagsStatic(html.substring(so + 1, sc)).trim();
        if (t.indexOf("of") >= 0 && t.indexOf("-") >= 0) return t;
        return null;
    }

    private String extractNextPage(String lower, String html) {
        int ni = lower.indexOf(">next&gt;<");
        if (ni < 0) ni = lower.indexOf(">next></");
        if (ni < 0) return null;
        int as = StringUtils.lastIndexOf(lower, "<a ", ni);
        if (as < 0) return null;
        int ae = lower.indexOf(">", as);
        if (ae < 0) return null;
        return SiteParser.extractAttr(html.substring(as, ae + 1), "href");
    }

    private String extractBelowTitle(String html, String lower) {
        int bi = lower.indexOf("class=\"sin\"");
        if (bi < 0) bi = lower.indexOf("class='sin'");
        if (bi < 0) return null;
        int so = lower.indexOf(">", bi);
        int sc = lower.indexOf("</span>", bi);
        if (so < 0 || sc < 0 || sc <= so) return null;
        return HtmlDecoder.decode(html.substring(so + 1, sc).trim());
    }

    private String buildMusicLabel(String url, String linkText) {
        if (linkText != null && linkText.length() > 0
            && !linkText.equalsIgnoreCase("download")
            && linkText.length() < 50)
            return linkText;
        String lo = url.toLowerCase();
        String fmt = lo.indexOf("/mp3/") >= 0 ? "MP3" :
                     lo.indexOf("/mp4/") >= 0 ? "MP4" :
                     lo.indexOf("/aac/") >= 0 ? "AAC" : "Audio";
        String qual = lo.indexOf("_192kbs") >= 0 ? " 192kb/s" :
                      lo.indexOf("_128kbs") >= 0 ? " 128kb/s" :
                      lo.indexOf("_64kbs")  >= 0 ? " 64kb/s"  :
                      lo.indexOf("_32kbs")  >= 0 ? " 32kb/s"  : "";
        return fmt + qual;
    }

    private String makeMusAbsolute(String url) {
        if (url == null) return null;
        url = UrlUtils.stripProxy(url);
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("/")) return "http://video.2yxa.mobi" + url;
        return "http://video.2yxa.mobi/" + url;
    }

    private boolean containsUrl(Vector v, String u) {
        for (int i = 0; i < v.size(); i += 2)
            if (u.equals(v.elementAt(i))) return true;
        return false;
    }

    private void addPair(Vector v, String u, String t) {
        v.addElement(u); v.addElement(t != null ? t : "");
    }

    private String getSortLabel(String val) {
        if ("1".equals(val)) return "Duration";
        if ("2".equals(val)) return "Rating";
        return "Upload Date";
    }

    private String getServerLabel(String val) {
        if ("am".equals(val)) return "Additional (ma)";
        return "Additional (ok)";
    }

    private String truncate(String s, int maxW, Font f) {
        if (s == null) return "";
        if (f.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && f.stringWidth(s + "...") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}