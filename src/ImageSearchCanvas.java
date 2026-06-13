/*
 * ImageSearchCanvas.java  -  DashTube v1.2
 * MIDP 2.0 / CLDC 1.1 compatible - no float, no regex, ASCII source
 *
 * Flow:
 *   showSearch()  ->  STATE_SEARCH  (query + size/type/orientation pickers)
 *   user submits  ->  STATE_RESULTS (thumbnail grid, lazy-load images)
 *   user selects  ->  STATE_DETAIL  (full image + download/resize links)
 */

import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;
import java.util.*;

class ImageSearchCanvas extends Canvas implements CommandListener {

    // ── States ──────────────────────────────────────────────────────────────
    private static final int STATE_SEARCH  = 0;
    private static final int STATE_RESULTS = 1;
    private static final int STATE_DETAIL  = 2;

    // ── Base URLs ────────────────────────────────────────────────────────────
    private static final String BASE      = "http://video.2yxa.mobi";
    private static final String SEARCH_EP = BASE + "/image.php";

    // ── Colours ──────────────────────────────────────────────────────────────
    private static final int C_BG     = 0x0F0F0F;
    private static final int C_ACCENT = 0xD42B52;
    private static final int C_TEXT   = 0xE8E8E8;
    private static final int C_SUB    = 0x888888;
    private static final int C_CARD   = 0x1A1A1A;
    private static final int C_SEL    = 0xD42B52;
    private static final int C_GREEN  = 0x22BB66;

    // ── Picker options (parallel arrays) ────────────────────────────────────
    private static final String[] SIZE_LABELS = {"Any","Small","Medium","Large","Very Large"};
    private static final String[] SIZE_VALS   = {"any","small","medium","large","wallpaper"};
    private static final String[] TYPE_LABELS = {"Any","Photo","Clipart","Animation","Transparent"};
    private static final String[] TYPE_VALS   = {"any","photo","clipart","animetedgif","transparent"};
    private static final String[] ORI_LABELS  = {"Any","Square","Portrait","Landscape"};
    private static final String[] ORI_VALS    = {"any","square","tall","wide"};

    // ── Search-screen state ───────────────────────────────────────────────────
    private int sizeIdx = 0, typeIdx = 0, oriIdx = 0;

    // ── Results state ────────────────────────────────────────────────────────
    // Each result: title, pageUrl, thumbUrl, dimensions  (4 fields per entry)
    private Vector results    = new Vector(); // ImageItem objects
    private String nextPageUrl = null;
    private String resultQuery = "";
    private int    resultSel   = 0;
    private int    scrollY     = 0;

    // thumbnail cache: imageItem.id -> Image
    private Hashtable thumbCache = new Hashtable();

    // ── Detail state ─────────────────────────────────────────────────────────
    private ImageItem detailItem = null;
    private Vector    dlLinks    = new Vector(); // DlLink objects
    private Image     detailImg  = null;
    private int       detailScroll = 0;
    private int       detailSel    = 0;  // selected dl row

    // ── Shared ───────────────────────────────────────────────────────────────
    private int     state      = STATE_SEARCH;
    private boolean loading    = false;
    private String  statusMsg  = "";

    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas          browser;
    private Command backCmd, selectCmd;
    private Font    fontBold, fontNormal, fontSmall;

    // ── Scroll physics ───────────────────────────────────────────────────────
    private int THUMB_W = 72, THUMB_H = 72, THUMB_PAD = 4;
    private int COLS = 2;

    // ====================================================================
    // Constructor
    // ====================================================================
    ImageSearchCanvas(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas browser) {
        this.midlet  = midlet;
        this.browser = browser;
        fontBold   = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,  Font.SIZE_SMALL);
        fontNormal = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontSmall  = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        backCmd   = new Command("Back",   Command.BACK, 1);
        selectCmd = new Command("Select", Command.OK,   1);
        addCommand(backCmd);
        addCommand(selectCmd);
        setCommandListener(this);
    }

    /** Called by MIDlet to reset to search form */
    void showSearch() {
        state     = STATE_SEARCH;
        statusMsg = "";
        loading   = false;
        repaint();
    }

    // ====================================================================
    // Paint dispatcher
    // ====================================================================
    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(C_BG); g.fillRect(0, 0, w, h);
        switch (state) {
            case STATE_SEARCH:  paintSearch(g, w, h);  break;
            case STATE_RESULTS: paintResults(g, w, h); break;
            case STATE_DETAIL:  paintDetail(g, w, h);  break;
        }
    }

    // ====================================================================
    // STATE_SEARCH
    // ====================================================================
    private void paintSearch(Graphics g, int w, int h) {
        // Header
        int hH = fontBold.getHeight() + 10;
        g.setColor(C_ACCENT); g.fillRect(0, 0, w, hH);
        g.setColor(0xFFFFFF); g.setFont(fontBold);
        g.drawString("Image Search", w / 2, 5, Graphics.TOP | Graphics.HCENTER);
        int y = hH + 8;

        int pad = 10, fw = getWidth() - pad * 2;

        y = drawPicker(g, "Size:",   SIZE_LABELS, sizeIdx, pad, y, fw, searchRow == 0); y += 4;
        y = drawPicker(g, "Type:",   TYPE_LABELS, typeIdx, pad, y, fw, searchRow == 1); y += 4;
        y = drawPicker(g, "Orient:", ORI_LABELS,  oriIdx,  pad, y, fw, searchRow == 2); y += 8;

        // Search button (highlighted when searchRow==3)
        int btnH = fontBold.getHeight() + 12;
        g.setColor(searchRow == 3 ? 0xFFFFFF : C_ACCENT);
        g.fillRoundRect(pad, y, fw, btnH, 8, 8);
        g.setColor(searchRow == 3 ? C_ACCENT : 0xFFFFFF);
        g.setFont(fontBold);
        g.drawString("Search Images", getWidth() / 2, y + 6, Graphics.TOP | Graphics.HCENTER);
        y += btnH + 8;

        // status
        if (statusMsg.length() > 0) {
            g.setFont(fontSmall); g.setColor(C_SUB);
            g.drawString(statusMsg, getWidth() / 2, y, Graphics.TOP | Graphics.HCENTER);
        }

        // hint
        g.setFont(fontSmall); g.setColor(0x444444);
        g.drawString("UP/DN: row  L/R: change  FIRE: ok", getWidth() / 2,
                     getHeight() - fontSmall.getHeight() - 4, Graphics.TOP | Graphics.HCENTER);
    }

    // which row is focused on search screen: 0=size,1=type,2=orient,3=button
    private int searchRow = 0;

    private int drawPicker(Graphics g, String label, String[] opts, int sel,
                           int x, int y, int w, boolean focused) {
        int lH = fontSmall.getHeight();
        int rH = fontNormal.getHeight() + 8;
        g.setFont(fontSmall); g.setColor(C_SUB);
        g.drawString(label, x, y, Graphics.TOP | Graphics.LEFT);
        y += lH + 2;
        g.setColor(focused ? C_ACCENT : C_CARD);
        g.fillRoundRect(x, y, w, rH, 6, 6);
        if (focused) g.drawRoundRect(x, y, w, rH, 6, 6);
        g.setFont(fontNormal); g.setColor(C_TEXT);
        g.drawString("< " + opts[sel] + " >", x + w / 2, y + 4, Graphics.TOP | Graphics.HCENTER);
        return y + rH;
    }

    // ====================================================================
    // STATE_RESULTS
    // ====================================================================
    private static final int GRID_COLS = 2;

    private void paintResults(Graphics g, int w, int h) {
        int hH = fontBold.getHeight() + 10;
        g.setColor(C_ACCENT); g.fillRect(0, 0, w, hH);
        g.setColor(0xFFFFFF); g.setFont(fontBold);
        g.drawString("Images: " + resultQuery, w / 2, 5, Graphics.TOP | Graphics.HCENTER);

        if (loading && results.isEmpty()) {
            g.setFont(fontNormal); g.setColor(C_SUB);
            g.drawString("Loading...", w / 2, h / 2, Graphics.TOP | Graphics.HCENTER);
            return;
        }
        if (results.isEmpty()) {
            g.setFont(fontNormal); g.setColor(C_SUB);
            g.drawString("No results", w / 2, h / 2, Graphics.TOP | Graphics.HCENTER);
            return;
        }

        int pad   = 4;
        int cellW = (w - pad * (GRID_COLS + 1)) / GRID_COLS;
        int cellH = cellW + fontSmall.getHeight() + 6;
        int gridY = hH + pad;

        g.clipRect(0, hH, w, h - hH);

        for (int i = 0; i < results.size(); i++) {
            ImageItem item = (ImageItem) results.elementAt(i);
            int col  = i % GRID_COLS;
            int row  = i / GRID_COLS;
            int cx   = pad + col * (cellW + pad);
            int cy   = gridY + row * (cellH + pad) - scrollY;

            if (cy + cellH < hH || cy > h) continue; // off-screen

            boolean sel = (i == resultSel);
            g.setColor(sel ? C_SEL : C_CARD);
            g.fillRoundRect(cx, cy, cellW, cellH, 6, 6);

            // Thumbnail
            Image thumb = (Image) thumbCache.get(item.id);
            int imgAreaH = cellW; // square image area
            if (thumb != null) {
                int tw = thumb.getWidth(), th2 = thumb.getHeight();
                // Scale down so the shorter side fills the cell square (cover/crop)
                // scale factor: find smallest integer divisor so image fits in cellW
                int drawW = tw, drawH = th2;
                // Scale so that the image covers the cell (at least cellW wide AND tall)
                // Use integer arithmetic: multiply both dims until one fits, then divide
                if (drawW > 0 && drawH > 0) {
                    // scale down: divide by largest factor that keeps both dims >= cellW
                    int scale = 1;
                    while (drawW / (scale + 1) >= cellW && drawH / (scale + 1) >= cellW) scale++;
                    drawW = drawW / scale;
                    drawH = drawH / scale;
                    // If still both larger than cell, scale down to fit shorter side = cellW
                    if (drawW > cellW * 2 || drawH > cellW * 2) {
                        int s2 = (drawW < drawH) ? drawW : drawH;
                        if (s2 > cellW) {
                            drawH = drawH * cellW / s2;
                            drawW = drawW * cellW / s2;
                        }
                    }
                }
                // Center-offset so image is cropped to cell square
                int offX = cx + (cellW - drawW) / 2;
                int offY = cy + (imgAreaH - drawH) / 2;
                // Clip to cell image area before drawing
                g.setClip(cx, cy, cellW, imgAreaH);
                g.drawImage(thumb, offX, offY, Graphics.TOP | Graphics.LEFT);
                // Restore grid clip
                g.setClip(0, hH, w, h - hH);
            } else {
                g.setColor(0x222222);
                g.fillRect(cx + 2, cy + 2, cellW - 4, imgAreaH - 4);
                g.setColor(C_SUB); g.setFont(fontSmall);
                g.drawString("...", cx + cellW / 2, cy + imgAreaH / 2 - fontSmall.getHeight() / 2,
                             Graphics.TOP | Graphics.HCENTER);
                startThumbLoad(item);
            }

            // Dimensions label below image area
            g.setFont(fontSmall); g.setColor(sel ? 0xFFFFFF : C_SUB);
            String dim = item.dims.length() > 0 ? item.dims : "img";
            g.drawString(dim, cx + cellW / 2, cy + imgAreaH + 2, Graphics.TOP | Graphics.HCENTER);
        }

        g.setClip(0, 0, w, h);

        // Status bar
        if (statusMsg.length() > 0 || loading) {
            String msg = loading ? "Loading..." : statusMsg;
            g.setColor(C_BG); g.fillRect(0, h - fontSmall.getHeight() - 6, w, fontSmall.getHeight() + 6);
            g.setFont(fontSmall); g.setColor(C_SUB);
            g.drawString(msg, w / 2, h - fontSmall.getHeight() - 3, Graphics.TOP | Graphics.HCENTER);
        }
    }

    // ====================================================================
    // STATE_DETAIL
    // ====================================================================
    private void paintDetail(Graphics g, int w, int h) {
        int hH = fontBold.getHeight() + 10;
        g.setColor(C_ACCENT); g.fillRect(0, 0, w, hH);
        g.setColor(0xFFFFFF); g.setFont(fontBold);
        String title = detailItem != null ? detailItem.dims : "Image";
        g.drawString(title, w / 2, 5, Graphics.TOP | Graphics.HCENTER);

        g.clipRect(0, hH, w, h - hH);

        int y = hH + 4 - detailScroll;

        // Preview image
        if (detailImg != null) {
            int iw = detailImg.getWidth(), ih = detailImg.getHeight();
            int maxW = w - 8;
            if (iw > maxW) { ih = ih * maxW / iw; iw = maxW; }
            g.drawImage(detailImg, (w - iw) / 2, y, Graphics.TOP | Graphics.LEFT);
            y += ih + 8;
        } else {
            g.setColor(0x222222); g.fillRect(8, y, w - 16, 80);
            g.setColor(C_SUB); g.setFont(fontNormal);
            g.drawString(loading ? "Loading image..." : "No preview", w / 2, y + 32,
                         Graphics.TOP | Graphics.HCENTER);
            y += 90;
        }

        // Download links
        for (int i = 0; i < dlLinks.size(); i++) {
            DlLink lnk = (DlLink) dlLinks.elementAt(i);
            boolean sel = (i == detailSel);
            int rH = fontBold.getHeight() + fontSmall.getHeight() + 10;
            g.setColor(sel ? C_SEL : C_CARD);
            g.fillRoundRect(8, y, w - 16, rH, 6, 6);
            if (!sel) { g.setColor(C_GREEN); g.fillRoundRect(8, y, 4, rH, 4, 4); }
            g.setFont(fontBold); g.setColor(sel ? 0xFFFFFF : C_TEXT);
            g.drawString(lnk.label, 20, y + 5, Graphics.TOP | Graphics.LEFT);
            g.setFont(fontSmall); g.setColor(sel ? 0xDDDDDD : C_SUB);
            g.drawString(lnk.sub, 20, y + 5 + fontBold.getHeight() + 2, Graphics.TOP | Graphics.LEFT);
            y += rH + 4;
        }

        if (loading && dlLinks.isEmpty()) {
            g.setFont(fontNormal); g.setColor(C_SUB);
            g.drawString("Loading links...", w / 2, y + 10, Graphics.TOP | Graphics.HCENTER);
        }

        g.setClip(0, 0, w, h);
    }

    // ====================================================================
    // Input
    // ====================================================================
    protected void keyPressed(int keyCode) {
        int action = getGameActionSafe(keyCode);
        switch (state) {
            case STATE_SEARCH:  handleSearchKey(action, keyCode); break;
            case STATE_RESULTS: handleResultsKey(action); break;
            case STATE_DETAIL:  handleDetailKey(action); break;
        }
    }

    private void handleSearchKey(int action, int keyCode) {
        if (action == Canvas.UP)   { if (searchRow > 0) { searchRow--; repaint(); } }
        else if (action == Canvas.DOWN) { if (searchRow < 3) { searchRow++; repaint(); } }
        else if (action == Canvas.FIRE || keyCode == KEY_NUM5) {
            if (searchRow == 3) { doSearch(); }
            else repaint(); // show focus
        }
        else if (action == Canvas.LEFT) {
            if      (searchRow == 0 && sizeIdx > 0)                    sizeIdx--;
            else if (searchRow == 1 && typeIdx > 0)                    typeIdx--;
            else if (searchRow == 2 && oriIdx  > 0)                    oriIdx--;
            repaint();
        }
        else if (action == Canvas.RIGHT) {
            if      (searchRow == 0 && sizeIdx < SIZE_VALS.length - 1) sizeIdx++;
            else if (searchRow == 1 && typeIdx < TYPE_VALS.length - 1) typeIdx++;
            else if (searchRow == 2 && oriIdx  < ORI_VALS.length  - 1) oriIdx++;
            repaint();
        }
    }

    private void handleResultsKey(int action) {
        int n = results.size();
        if (n == 0) return;
        if      (action == Canvas.UP)   { if (resultSel >= GRID_COLS) resultSel -= GRID_COLS; else resultSel = 0; scrollToSel(); repaint(); }
        else if (action == Canvas.DOWN) { if (resultSel + GRID_COLS < n) resultSel += GRID_COLS; else resultSel = n - 1; scrollToSel(); repaint(); }
        else if (action == Canvas.LEFT) { if (resultSel > 0) resultSel--; scrollToSel(); repaint(); }
        else if (action == Canvas.RIGHT){ if (resultSel < n - 1) resultSel++; scrollToSel(); repaint(); }
        else if (action == Canvas.FIRE) { openDetail(resultSel); }
    }

    private void handleDetailKey(int action) {
        int n = dlLinks.size();
        if      (action == Canvas.UP)   { if (detailSel > 0) { detailSel--; repaint(); } else if (detailScroll > 0) { detailScroll -= 10; repaint(); } }
        else if (action == Canvas.DOWN) { if (detailSel < n - 1) { detailSel++; repaint(); } else { detailScroll += 10; repaint(); } }
        else if (action == Canvas.FIRE) { activateDetail(); }
    }

    protected void pointerReleased(int px, int py) {
        if (state == STATE_SEARCH)  handleSearchTouch(px, py);
        else if (state == STATE_RESULTS) handleResultsTouch(px, py);
        else if (state == STATE_DETAIL)  handleDetailTouch(px, py);
    }

    private void handleSearchTouch(int px, int py) {
        int hH = fontBold.getHeight() + 10;
        int pad = 10, fw = getWidth() - pad * 2;
        int y = hH + 8;
        int lH = fontSmall.getHeight();
        int rH = fontNormal.getHeight() + 8;

        // Size
        int pickerTop0 = y + lH + 2;
        int pickerTop1 = pickerTop0 + rH + 4 + lH + 2;
        int pickerTop2 = pickerTop1 + rH + 4 + lH + 2;
        int btnTop     = pickerTop2 + rH + 8;

        if (py >= pickerTop0 && py < pickerTop0 + rH) { searchRow = 0; repaint(); }
        else if (py >= pickerTop1 && py < pickerTop1 + rH) { searchRow = 1; repaint(); }
        else if (py >= pickerTop2 && py < pickerTop2 + rH) { searchRow = 2; repaint(); }
        else if (py >= btnTop) { doSearch(); }
    }

    private void handleResultsTouch(int px, int py) {
        int w = getWidth();
        int hH = fontBold.getHeight() + 10;
        int pad = 4;
        int cellW = (w - pad * (GRID_COLS + 1)) / GRID_COLS;
        int cellH = cellW + fontSmall.getHeight() + 6;
        int gridY = hH + pad;

        for (int i = 0; i < results.size(); i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int cx  = pad + col * (cellW + pad);
            int cy  = gridY + row * (cellH + pad) - scrollY;
            if (px >= cx && px < cx + cellW && py >= cy && py < cy + cellH) {
                resultSel = i; repaint();
                openDetail(i);
                return;
            }
        }
    }

    private void handleDetailTouch(int px, int py) {
        int h = getHeight(), hH = fontBold.getHeight() + 10;
        int imgH = detailImg != null ? getScaledImgH() : 90;
        int baseY = hH + 4 + imgH + 8 - detailScroll;
        int rH = fontBold.getHeight() + fontSmall.getHeight() + 10;
        for (int i = 0; i < dlLinks.size(); i++) {
            int ry = baseY + i * (rH + 4);
            if (py >= ry && py < ry + rH) {
                detailSel = i; repaint();
                activateDetail();
                return;
            }
        }
    }

    private int getScaledImgH() {
        if (detailImg == null) return 0;
        int iw = detailImg.getWidth(), ih = detailImg.getHeight();
        int maxW = getWidth() - 8;
        if (iw > maxW) return ih * maxW / iw;
        return ih;
    }

    private void scrollToSel() {
        int w = getWidth(), hH = fontBold.getHeight() + 10, pad = 4;
        int cellW = (w - pad * (GRID_COLS + 1)) / GRID_COLS;
        int cellH = cellW + fontSmall.getHeight() + 6;
        int row = resultSel / GRID_COLS;
        int cy  = hH + pad + row * (cellH + pad) - scrollY;
        int h   = getHeight();
        if (cy < hH + 4)         scrollY -= (hH + 4 - cy);
        if (cy + cellH > h - 20) scrollY += (cy + cellH - h + 20);
        if (scrollY < 0) scrollY = 0;
    }

    private int getGameActionSafe(int keyCode) {
        int a = 0;
        try { a = getGameAction(keyCode); } catch (Exception e) {}
        if (a != 0) return a;
        switch (keyCode) {
            case -8: return Canvas.FIRE;
            case -1: return Canvas.UP;
            case -6: return Canvas.DOWN;
            case -2: return Canvas.LEFT;
            case -5: return Canvas.RIGHT;
        }
        return 0;
    }

    // ====================================================================
    // Commands
    // ====================================================================
    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            if      (state == STATE_DETAIL)  { state = STATE_RESULTS; detailImg = null; repaint(); }
            else if (state == STATE_RESULTS) { state = STATE_SEARCH; repaint(); }
            else midlet.getDisplay().setCurrent(browser);
        } else if (c == selectCmd) {
            if      (state == STATE_SEARCH)  doSearch();
            else if (state == STATE_RESULTS) openDetail(resultSel);
            else if (state == STATE_DETAIL)  activateDetail();
        }
    }

    // ====================================================================
    // Search: show TextBox for query, then load
    // ====================================================================
    private void doSearch() {
        final TextBox tb = new TextBox("Image query", "", 80, TextField.ANY);
        final Command ok = new Command("Search", Command.OK,    1);
        final Command bk = new Command("Cancel", Command.CANCEL,1);
        tb.addCommand(ok); tb.addCommand(bk);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                midlet.getDisplay().setCurrent(ImageSearchCanvas.this);
                if (c == ok) {
                    String q = tb.getString().trim();
                    if (q.length() > 0) startSearch(q);
                }
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    private void startSearch(final String query) {
        results.removeAllElements(); thumbCache = new Hashtable();
        resultSel = 0; scrollY = 0; resultQuery = query; nextPageUrl = null;
        state     = STATE_RESULTS; loading = true; statusMsg = "";
        repaint();

        String url = SEARCH_EP + "?query=" + UrlEncoder.encode(query)
                   + "&param%5Bsize%5D="   + SIZE_VALS[sizeIdx]
                   + "&param%5Btype%5D="   + TYPE_VALS[typeIdx]
                   + "&param%5Blayout%5D=" + ORI_VALS[oriIdx];

        fetchHtml(url, new HtmlCallback() {
            public void done(String html, String base) { parseResults(html, base); }
            public void fail(String err) { loading = false; statusMsg = "Error: " + err; repaint(); }
        });
    }

    // ====================================================================
    // Parse results page
    // ====================================================================
    private void parseResults(String html, String base) {
        String lo = html.toLowerCase();
        // Each result is inside <li>...<a href="/image.php?id=XXX&...">
        // Thumbnail: <img src="https://tse...bing.net/...">
        // Dimensions: <div><a href="...&original">...<br/>WxH</a></div>

        results.removeAllElements();
        int pos = 0;
        while (pos < lo.length()) {
            // find next image result <li>
            int liStart = lo.indexOf("<li>", pos);
            if (liStart < 0) break;
            int liEnd = lo.indexOf("</li>", liStart);
            if (liEnd < 0) liEnd = lo.length();
            String li = html.substring(liStart, liEnd);
            String liLo = li.toLowerCase();

            // Must have /image.php?id= link
            int idxLink = liLo.indexOf("/image.php?id=");
            if (idxLink >= 0) {
                // Extract id
                int idStart = idxLink + "/image.php?id=".length();
                int idEnd   = idStart;
                while (idEnd < li.length() && li.charAt(idEnd) != '&' && li.charAt(idEnd) != '"') idEnd++;
                String id = li.substring(idStart, idEnd);

                // page url - scan backwards from idxLink for href="
                int hrefStart = -1;
                {
                    String find = "href=\"";
                    for (int si = idxLink - find.length(); si >= 0; si--) {
                        if (liLo.substring(si, si + find.length()).equals(find)) {
                            hrefStart = si + find.length(); break;
                        }
                    }
                }
                int hrefEnd   = (hrefStart >= 0) ? li.indexOf('"', hrefStart) : -1;
                String pageUrl = (hrefStart >= 0 && hrefEnd > hrefStart)
                    ? BASE + HtmlDecoder.decode(li.substring(hrefStart, hrefEnd)) : "";

                // thumb src
                String thumbUrl = "";
                int imgIdx = liLo.indexOf("<img src=");
                if (imgIdx >= 0) {
                    int sq = li.indexOf('"', imgIdx + 9) + 1;
                    int eq = li.indexOf('"', sq);
                    if (sq > 0 && eq > sq) thumbUrl = li.substring(sq, eq);
                }

                // dimensions (WxH pattern after last <br/>)
                String dims = extractDims(li);

                if (id.length() > 0) {
                    results.addElement(new ImageItem(id, pageUrl, thumbUrl, dims));
                }
            }
            pos = liEnd + 1;
        }

        // next page link
        nextPageUrl = null;
        int nextIdx = lo.indexOf("next&gt;");
        if (nextIdx < 0) nextIdx = lo.indexOf(">next<");
        if (nextIdx >= 0) {
            int aStart = -1;
            {
                String find = "<a ";
                for (int si = nextIdx - find.length(); si >= 0; si--) {
                    if (lo.substring(si, si + find.length()).equals(find)) {
                        aStart = si; break;
                    }
                }
            }
            if (aStart >= 0) {
                String aTag = html.substring(aStart, nextIdx + 10);
                String href = extractAttr(aTag, "href");
                if (href != null) nextPageUrl = BASE + HtmlDecoder.decode(href);
            }
        }

        loading   = false;
        statusMsg = results.size() + " images";
        repaint();
    }

    // ====================================================================
    // Lazy thumbnail loading
    // ====================================================================
    private Hashtable loadingThumbs = new Hashtable();

    private void startThumbLoad(final ImageItem item) {
        if (thumbCache.containsKey(item.id)) return;
        if (loadingThumbs.containsKey(item.id)) return;
        if (item.thumbUrl.length() == 0) return;
        loadingThumbs.put(item.id, item.id);
        new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] data = fetchBytes(item.thumbUrl);
                    if (data != null && data.length > 0) {
                        final Image img = Image.createImage(data, 0, data.length);
                        thumbCache.put(item.id, img);
                        midlet.getDisplay().callSerially(new Runnable() {
                            public void run() { if (state == STATE_RESULTS) repaint(); }
                        });
                    }
                } catch (Exception e) { /* ignore */ }
                finally { loadingThumbs.remove(item.id); }
            }
        }).start();
    }

    // ====================================================================
    // Open detail page - fetch to get real download URLs
    // ====================================================================
    private void openDetail(int idx) {
        if (idx < 0 || idx >= results.size()) return;
        detailItem   = (ImageItem) results.elementAt(idx);
        dlLinks.removeAllElements(); detailImg = null;
        detailScroll = 0; detailSel = 0;
        state        = STATE_DETAIL;
        loading      = true; statusMsg = "";
        repaint();

        // Show cached thumb while loading
        Image cachedThumb = (Image) thumbCache.get(detailItem.id);
        if (cachedThumb != null) { detailImg = cachedThumb; repaint(); }

        // Fetch detail page to get real filenames
        fetchHtml(detailItem.pageUrl, new HtmlCallback() {
            public void done(String html, String base) { parseDetail(html, base); }
            public void fail(String err) { loading = false; statusMsg = "Error: " + err; repaint(); }
        });
    }

    // ====================================================================
    // Parse detail page
    // ====================================================================
    private void parseDetail(String html, String base) {
        String lo = html.toLowerCase();
        dlLinks.removeAllElements();

        // Full preview: <img src="/image/ID.jpg" ...> inside the page body
        // Pattern: src="/image/ID.jpg" or src="https://video.2yxa.mobi/image/ID.jpg"
        String previewUrl = null;
        // Try proxied path first: /image/ID (without 2yxa_mobi prefix = actual preview)
        String previewPath = "/image/" + detailItem.id + ".jpg";
        int pvIdx = lo.indexOf(previewPath);
        if (pvIdx >= 0) {
            // Make sure it's an img src, not a download link
            int srcPos = -1;
            {
                String find = "src=";
                for (int si = pvIdx - find.length(); si >= 0 && si >= pvIdx - 20; si--) {
                    if (lo.substring(si, si + find.length()).equals(find)) { srcPos = si; break; }
                }
            }
            if (srcPos >= 0 && pvIdx - srcPos < 20) {
                previewUrl = BASE + previewPath;
            }
        }
        if (previewUrl == null) {
            // Fallback: find <img src=".../image/ID
            String findImg = "src=\"/image/" + detailItem.id.toLowerCase();
            int fi = lo.indexOf(findImg);
            if (fi >= 0) {
                int qs = fi + 5; // skip src="
                int qe = lo.indexOf('"', qs);
                if (qe > qs) previewUrl = BASE + html.substring(qs, qe);
            }
        }
        if (previewUrl != null) loadDetailImage(previewUrl);

        // ── Parse download links ──────────────────────────────────────────
        // Track seen URLs to avoid duplicates (http vs https same file)
        Vector seenUrls = new Vector();

        int pos = 0;
        while (pos < lo.length()) {
            int aStart = lo.indexOf("<a ", pos);
            if (aStart < 0) break;
            int aEnd = lo.indexOf(">", aStart);
            if (aEnd < 0) { pos = aStart + 1; continue; }
            String aTag = html.substring(aStart, aEnd + 1);
            String href = extractAttr(aTag, "href");
            int txtEnd  = lo.indexOf("</a>", aEnd);
            String txt  = (txtEnd > aEnd) ? stripTags(html.substring(aEnd + 1, txtEnd)) : "";
            pos = (txtEnd > aEnd) ? txtEnd + 4 : aEnd + 1;

            if (href == null || href.length() == 0) continue;
            String hlo = href.toLowerCase();

            // Skip nav/icon-only links (no meaningful text and not a direct file link)
            // Skip attach.php (email), cab.php, image.php?id=X (back links without gabarit)
            if (hlo.indexOf("attach.php") >= 0) continue;
            if (hlo.indexOf("cab.php") >= 0) continue;
            if (hlo.indexOf("#") >= 0) continue;

            // Normalise URL to detect duplicates (strip http/https prefix)
            String normUrl = hlo;
            if (normUrl.startsWith("https://")) normUrl = normUrl.substring(8);
            else if (normUrl.startsWith("http://")) normUrl = normUrl.substring(7);
            boolean seen = false;
            for (int si = 0; si < seenUrls.size(); si++) {
                if (seenUrls.elementAt(si).equals(normUrl)) { seen = true; break; }
            }
            if (seen) continue;

            // ── Mobile-compatible download (/android/image/...) ──────────
            if (hlo.indexOf("/android/image/") >= 0) {
                seenUrls.addElement(normUrl);
                String sub = extractSize(txt);
                dlLinks.addElement(new DlLink("Download (mobile)", sub.length() > 0 ? sub : "optimised for J2ME", makeAbsolute(href)));
            }
            // ── Original full download (/image/2yxa_mobi_...) ────────────
            else if (hlo.indexOf("/image/2yxa_mobi_") >= 0 && hlo.indexOf("/android/") < 0) {
                seenUrls.addElement(normUrl);
                String sub = extractSize(txt);
                // Try to get size from nearby span in txt, e.g. "Download (original) (41kb)"
                if (sub.length() == 0) sub = extractSize(href);
                dlLinks.addElement(new DlLink("Download (original)", sub, makeAbsolute(href)));
            }
            // ── Resize links (/image.php?id=XXX&gabarit=WxH) ────────────
            else if (hlo.indexOf("gabarit=") >= 0 && hlo.indexOf("image.php") >= 0) {
                seenUrls.addElement(normUrl);
                int gi = href.indexOf("gabarit=") + 8;
                int ge = href.indexOf("&", gi); if (ge < 0) ge = href.length();
                String dim = href.substring(gi, ge);
                dlLinks.addElement(new DlLink("Download " + dim, "resized JPG", makeAbsolute(href)));
            }
        }

        loading = false;
        repaint();
    }

    private void loadDetailImage(final String url) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    // proxy the request
                    String proxyUrl = "http://" + HttpRequestRunnable.PROXY_HOST
                        + "/?url=" + UrlEncoder.encode(url);
                    byte[] data = fetchBytes(proxyUrl);
                    if (data != null && data.length > 0) {
                        final Image img = Image.createImage(data, 0, data.length);
                        midlet.getDisplay().callSerially(new Runnable() {
                            public void run() {
                                detailImg = img;
                                if (state == STATE_DETAIL) repaint();
                            }
                        });
                    }
                } catch (Exception e) { /* keep thumb */ }
            }
        }).start();
    }

    // ====================================================================
    // Activate detail download link
    // ====================================================================
    private void activateDetail() {
        if (dlLinks.isEmpty()) return;
        if (detailSel < 0 || detailSel >= dlLinks.size()) return;
        DlLink lnk = (DlLink) dlLinks.elementAt(detailSel);
        // Extract filename from URL
        String url = lnk.url;
        String filename = url;
        int slash = filename.lastIndexOf('/');
        if (slash >= 0 && slash < filename.length() - 1) filename = filename.substring(slash + 1);
        int q = filename.indexOf('?'); if (q >= 0) filename = filename.substring(0, q);
        if (filename.length() == 0) filename = "image.jpg";
        // Start download directly (not navigate — navigateTo would try to parse it as HTML)
        midlet.getDisplay().setCurrent(browser);
        browser.startDownloadPublic(url, filename);
    }

    // ====================================================================
    // Networking helpers
    // ====================================================================
    private interface HtmlCallback {
        void done(String html, String base);
        void fail(String err);
    }

    private void fetchHtml(final String url, final HtmlCallback cb) {
        new Thread(new HttpRequestRunnable(url, null, false, new HttpCallback() {
            public void onSuccess(String content, String base) {
                cb.done(content, base);
            }
            public void onError(String err) {
                cb.fail(err);
            }
        })).start();
    }

    private byte[] fetchBytes(String url) {
        HttpConnection conn = null;
        InputStream    is   = null;
        try {
            conn = (HttpConnection) Connector.open(url);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (J2ME; MIDP-2.0; CLDC-1.1) DashTube/1.2");
            int rc = conn.getResponseCode();
            if (rc != HttpConnection.HTTP_OK) return null;
            is = conn.openInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Exception e) { return null; }
        finally {
            try { if (is   != null) is.close();   } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }

    // ====================================================================
    // HTML helpers (CLDC 1.1, no regex)
    // ====================================================================
    private static String extractAttr(String tag, String attr) {
        String lo = tag.toLowerCase();
        String find = attr.toLowerCase() + "=";
        int idx = lo.indexOf(find);
        if (idx < 0) return null;
        idx += find.length();
        if (idx >= tag.length()) return null;
        char q = tag.charAt(idx);
        if (q == '"' || q == '\'') {
            idx++;
            int end = tag.indexOf(q, idx);
            return end > idx ? tag.substring(idx, end) : null;
        }
        // unquoted
        int end = idx;
        while (end < tag.length() && tag.charAt(end) != ' ' && tag.charAt(end) != '>' && tag.charAt(end) != '/') end++;
        return end > idx ? tag.substring(idx, end) : null;
    }

    private static String stripTags(String html) {
        if (html == null) return "";
        StringBuffer sb = new StringBuffer();
        boolean in = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if      (c == '<') in = true;
            else if (c == '>') in = false;
            else if (!in) sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String sanitizeFilename(String s) {
        if (s == null) return "image";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
            else sb.append('_');
        }
        return sb.length() > 0 ? sb.toString() : "image";
    }

    private static String extractDims(String li) {
        // Look for WxH pattern e.g. 626x626
        for (int i = 0; i < li.length() - 3; i++) {
            if (li.charAt(i) >= '1' && li.charAt(i) <= '9') {
                int ns = i;
                while (i < li.length() && li.charAt(i) >= '0' && li.charAt(i) <= '9') i++;
                if (i < li.length() && (li.charAt(i) == 'x' || li.charAt(i) == 'X')) {
                    i++;
                    int ne = i;
                    while (ne < li.length() && li.charAt(ne) >= '0' && li.charAt(ne) <= '9') ne++;
                    if (ne > i && ne - i >= 2 && i - ns >= 2) return li.substring(ns, ne);
                }
            }
        }
        return "";
    }

    private static String extractSize(String s) {
        if (s == null) return "";
        int p = s.indexOf('(');
        int q = s.indexOf(')');
        if (p >= 0 && q > p) return s.substring(p + 1, q).trim();
        return "";
    }

    private static String makeAbsolute(String url) {
        if (url == null) return "";
        if (url.startsWith("http")) return url;
        if (url.startsWith("//"))   return "http:" + url;
        if (url.startsWith("/"))    return BASE + url;
        return BASE + "/" + url;
    }

    // ====================================================================
    // Inner data classes
    // ====================================================================
    private static class ImageItem {
        String id, pageUrl, thumbUrl, dims;
        ImageItem(String id, String pageUrl, String thumbUrl, String dims) {
            this.id = id; this.pageUrl = pageUrl; this.thumbUrl = thumbUrl; this.dims = dims;
        }
    }

    private static class DlLink {
        String label, sub, url;
        DlLink(String label, String sub, String url) {
            this.label = label; this.sub = sub; this.url = url;
        }
    }
}