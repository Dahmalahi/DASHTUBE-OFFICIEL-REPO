import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;
import java.util.*;

class ConvertScreen extends Canvas implements CommandListener {

    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas          browser;

    private String  inputUrl  = "";
    private String  statusMsg = "Enter a YouTube or TikTok URL";
    private Vector  dlLinks   = new Vector();
    private int     selLink   = 0;
    private int     scrollY   = 0;
    private boolean loading   = false;

    private Font fontBold, fontNormal, fontSmall;
    private Command backCmd, enterUrlCmd, fetchCmd, openCmd;

    public ConvertScreen(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas browser) {
        this.midlet  = midlet;
        this.browser = browser;
        fontBold   = Font.getFont(0, Font.STYLE_BOLD,  Font.SIZE_SMALL);
        fontNormal = Font.getFont(0, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontSmall  = Font.getFont(0, Font.STYLE_PLAIN, Font.SIZE_SMALL);

        backCmd     = new Command("Back",       Command.BACK,   1);
        enterUrlCmd = new Command("Enter URL",  Command.SCREEN, 2);
        fetchCmd    = new Command("Parse Site", Command.OK,     1);
        openCmd     = new Command("Open Link",  Command.SCREEN, 3);

        addCommand(backCmd);
        addCommand(enterUrlCmd);
        addCommand(fetchCmd);
        setCommandListener(this);
    }

    public void setPrefilledUrl(String url) {
        if (url != null && url.trim().length() > 0) {
            inputUrl  = url.trim();
            statusMsg = "URL ready. Press 'Parse Site'";
            dlLinks.removeAllElements();
            selLink = 0;
            scrollY = 0;
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();

        g.setColor(0x0F0F0F);
        g.fillRect(0, 0, w, h);

        // Header
        g.setColor(0xD42B52);
        g.fillRect(0, 0, w, 25);
        g.setColor(0xFFFFFF);
        g.setFont(fontBold);
        g.drawString("2yxa Parser", w / 2, 4, Graphics.TOP | Graphics.HCENTER);

        int y = 30;

        if (loading) {
            g.setColor(0xFFAA00);
            g.setFont(fontBold);
            g.drawString("Fetching page...", w / 2, y + 30,
                         Graphics.TOP | Graphics.HCENTER);
            return;
        }

        if (dlLinks.size() == 0) {
            g.setColor(0x888888);
            g.setFont(fontSmall);
            g.drawString("URL:", 10, y, Graphics.TOP | Graphics.LEFT);
            y += fontSmall.getHeight() + 2;

            g.setColor(0xCCCCCC);
            g.setFont(fontNormal);
            y = drawWrapped(g, inputUrl.length() > 0 ? inputUrl : "(none set)",
                            10, y, w - 20, fontNormal);
            y += 10;

            g.setColor(0x888888);
            g.setFont(fontSmall);
            drawWrapped(g, statusMsg, 10, y, w - 20, fontSmall);
            return;
        }

        g.setColor(0x22BB66);
        g.setFont(fontSmall);
        g.drawString(dlLinks.size() / 2 + " links found",
                     10, y, Graphics.TOP | Graphics.LEFT);
        y += fontSmall.getHeight() + 4;

        int rowH = fontBold.getHeight() + fontSmall.getHeight() + 10;
        int drawY = y - scrollY;

        for (int i = 0; i < dlLinks.size(); i += 2) {
            String lbl = (String) dlLinks.elementAt(i);
            String url = (String) dlLinks.elementAt(i + 1);
            boolean sel = (i / 2 == selLink);

            if (drawY + rowH > 0 && drawY < h - 10) {
                g.setColor(sel ? 0xD42B52 : 0x1A1A1A);
                g.fillRoundRect(5, drawY, w - 10, rowH, 6, 6);

                g.setColor(0xFFFFFF);
                g.setFont(sel ? fontBold : fontNormal);
                g.drawString(truncate(lbl, w - 24, fontBold),
                             12, drawY + 3, Graphics.TOP | Graphics.LEFT);

                g.setColor(sel ? 0xDDDDDD : 0x666666);
                g.setFont(fontSmall);
                g.drawString(truncate(url, w - 24, fontSmall),
                             12, drawY + 3 + fontBold.getHeight(),
                             Graphics.TOP | Graphics.LEFT);
            }
            drawY += rowH + 3;
        }

        g.setColor(0x222222);
        g.fillRect(0, h - fontSmall.getHeight() - 5, w, fontSmall.getHeight() + 5);
        g.setColor(0x888888);
        g.setFont(fontSmall);
        g.drawString("UP/DN=select  FIRE=open link",
                     w / 2, h - fontSmall.getHeight() - 2,
                     Graphics.TOP | Graphics.HCENTER);
    }

    protected void keyPressed(int keyCode) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}
        // Supplement with Nokia S60 raw codes (same as rest of app)
        if (action == 0) {
            switch (keyCode) {
                case -8: action = Canvas.FIRE;  break;
                case -1: action = Canvas.UP;    break;
                case -6: action = Canvas.DOWN;  break;
            }
        }

        int n = dlLinks.size() / 2;

        if (action == Canvas.UP && selLink > 0) {
            selLink--;
            ensureVisible();
            repaint();
        } else if (action == Canvas.DOWN && selLink < n - 1) {
            selLink++;
            ensureVisible();
            repaint();
        } else if (action == Canvas.FIRE || keyCode == Canvas.KEY_NUM5) {
            openSelected();
        }
    }

    protected void pointerReleased(int px, int py) {
        if (dlLinks.size() == 0) return;
        int y0   = 30 + fontSmall.getHeight() + 4;
        int rowH = fontBold.getHeight() + fontSmall.getHeight() + 10 + 3;
        int drawY = y0 - scrollY;
        for (int i = 0; i < dlLinks.size(); i += 2) {
            if (py >= drawY && py < drawY + rowH) {
                selLink = i / 2;
                repaint();
                openSelected();
                return;
            }
            drawY += rowH;
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            midlet.getDisplay().setCurrent(browser);
        } else if (c == enterUrlCmd) {
            showUrlEditor();
        } else if (c == fetchCmd) {
            startParse();
        } else if (c == openCmd) {
            openSelected();
        }
    }

    private void startParse() {
        if (inputUrl.trim().length() < 5) {
            statusMsg = "Enter a URL first!";
            repaint();
            return;
        }
        loading = true;
        dlLinks.removeAllElements();
        selLink = 0;
        scrollY = 0;
        statusMsg = "Fetching...";
        repaint();

        new Thread(new Runnable() {
            public void run() {
                fetchAndParse();
            }
        }).start();
    }

    private void fetchAndParse() {
        HttpConnection conn = null;
        InputStream    is   = null;
        try {
            String id  = extractId(inputUrl);
            String psk = (inputUrl.toLowerCase().indexOf("tiktok") >= 0) ? "sm" : "you";

            // FIX: Properly encode the target URL for the proxy
            String target   = "http://video.2yxa.mobi/mov.php?id=" + id + "&poisk=" + psk;
            String proxyUrl = "http://" + HttpRequestRunnable.PROXY_HOST 
                            + "/?url=" + UrlEncoder.encode(target);

            conn = (HttpConnection) Connector.open(proxyUrl);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (MIDP-2.0; CLDC-1.1) DashTube/1.0");
            conn.setRequestProperty("Accept", "text/html,*/*");
            CookieManager.injectCookies(conn, HttpRequestRunnable.PROXY_HOST);

            int rc = conn.getResponseCode();
            if (rc != HttpConnection.HTTP_OK) {
                statusMsg = "HTTP Error: " + rc;
                loading = false;
                repaint();
                return;
            }

            CookieManager.storeCookies(conn);

            is = conn.openInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            String html = new String(bos.toByteArray(), "UTF-8");

            parseLinks(html);

            if (dlLinks.size() == 0) {
                statusMsg = "No download links found. Try again or check URL.";
            } else {
                statusMsg = "Done. " + (dlLinks.size() / 2) + " links.";
            }

        } catch (Exception e) {
            statusMsg = "Error: " + e.getMessage();
        } finally {
            try { if (is   != null) is.close();  } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }

        loading = false;
        final boolean hasLinks = dlLinks.size() > 0;
        midlet.getDisplay().callSerially(new Runnable() {
            public void run() {
                if (hasLinks) addCommand(openCmd);
                repaint();
            }
        });
    }

    private void parseLinks(String html) {
        String lo = html.toLowerCase();
        int pos = 0;

        // FIX: Use the same parsing logic as SiteParser
        while (pos < lo.length()) {
            int hDq = lo.indexOf("href=\"", pos);
            int hSq = lo.indexOf("href='", pos);
            
            if (hDq < 0 && hSq < 0) break;
            
            int hIdx;
            char quote;
            if      (hDq < 0)    { hIdx = hSq; quote = '\''; }
            else if (hSq < 0)    { hIdx = hDq; quote = '"'; }
            else if (hDq <= hSq) { hIdx = hDq; quote = '"'; }
            else                 { hIdx = hSq; quote = '\''; }
            
            int us = hIdx + 6;
            int ue = html.indexOf(quote, us);
            if (ue < 0) { pos = us + 1; continue; }
            
            String rawUrl = HtmlDecoder.decode(html.substring(us, ue).trim());
            
            // Make absolute
            if (rawUrl.startsWith("/")) {
                rawUrl = "https://video.2yxa.mobi" + rawUrl;
            } else if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                rawUrl = "http://video.2yxa.mobi/" + rawUrl;
            }
            
            // Extract link text
            String linkText = "";
            int gt = lo.indexOf(">", ue);
            int ac = lo.indexOf("</a>", ue);
            if (gt >= 0 && ac > gt) {
                linkText = HtmlDecoder.decode(stripTags(html.substring(gt + 1, ac)).trim());
            }
            
            String urlLo = rawUrl.toLowerCase();
            boolean keep = false;
            
            // FIX: Use DownloadUrlFilter logic
            // Compatible download links (android/ paths)
            if (urlLo.indexOf("/android/mp4/") >= 0 ||
                urlLo.indexOf("/android/3gp/") >= 0 ||
                urlLo.indexOf("/android/mp3/") >= 0) {
                keep = true;
            }
            
            // Original download links
            else if (urlLo.indexOf("/mp4/2yxa_mobi_") >= 0 ||
                     urlLo.indexOf("/3gp/2yxa_mobi_") >= 0 ||
                     urlLo.indexOf("/mp3/2yxa_mobi_") >= 0) {
                keep = true;
            }
            
            // Conversion trigger links
            else if (urlLo.indexOf("mov.php") >= 0 && 
                     urlLo.indexOf("&dw") >= 0 &&
                     urlLo.indexOf("rtsp") < 0) {
                keep = true;
            }
            
            // Filter junk
            if (urlLo.indexOf("attach.php") >= 0 ||
                urlLo.indexOf("t.me/") >= 0 ||
                urlLo.indexOf("telegram") >= 0 ||
                urlLo.indexOf("vkontakte") >= 0 ||
                urlLo.indexOf("facebook") >= 0 ||
                urlLo.indexOf("twitter") >= 0 ||
                urlLo.indexOf("whatsapp") >= 0) {
                keep = false;
            }
            
            if (keep) {
                if (linkText.length() == 0) {
                    linkText = buildLabel(rawUrl);
                }
                
                // Deduplicate
                boolean dup = false;
                for (int i = 1; i < dlLinks.size(); i += 2) {
                    if (dlLinks.elementAt(i).equals(rawUrl)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    dlLinks.addElement(linkText);
                    dlLinks.addElement(rawUrl);
                }
            }
            
            pos = ue + 1;
        }
    }

    private String buildLabel(String url) {
        String lo = url.toLowerCase();
        
        // Check for format in URL
        String fmt = "File";
        if (lo.indexOf(".mp4") >= 0) fmt = "MP4";
        else if (lo.indexOf(".3gp") >= 0) fmt = "3GP";
        else if (lo.indexOf(".mp3") >= 0) fmt = "MP3";
        
        // Check type= parameter for conversion links
        if (lo.indexOf("type=") >= 0) {
            int ti = lo.indexOf("type=") + 5;
            int te = lo.indexOf("&", ti);
            if (te < 0) te = url.length();
            String t = url.substring(ti, te);
            
            if (t.equals("1"))  return "\u25B6 Create 3GP 128x96";
            if (t.equals("2"))  return "\u25B6 Create 3GP 176x144";
            if (t.equals("3"))  return "\u25B6 Create MP4 320x240";
            if (t.equals("4"))  return "\u25B6 Create MP3";
            if (t.equals("6"))  return "\u2605 Create MP4 176x144 (Best)";
            if (t.equals("18")) return "\u25B6 MP4 Original Quality";
        }
        
        // Direct download links
        boolean isAndroid = lo.indexOf("/android/") >= 0;
        return "\u2193 Download " + fmt + (isAndroid ? " [ready]" : "");
    }

    private void openSelected() {
        if (dlLinks.size() == 0) return;
        int idx = selLink * 2 + 1;
        if (idx >= dlLinks.size()) return;

        String url = (String) dlLinks.elementAt(idx);

        // FIX: Properly encode URL when routing through proxy
        String proxyUrl;
        if (url.indexOf(HttpRequestRunnable.PROXY_HOST) >= 0) {
            proxyUrl = url;
        } else {
            proxyUrl = "http://" + HttpRequestRunnable.PROXY_HOST 
                     + "/?url=" + UrlEncoder.encode(url);
        }

        browser.navigateTo(proxyUrl);
        midlet.getDisplay().setCurrent(browser);
    }

    private String extractId(String url) {
        if (url == null) return "";

        // 2yxa mov.php URL with id=
        if (url.indexOf("id=") >= 0) {
            int s = url.indexOf("id=") + 3;
            int e = url.indexOf("&", s);
            return e < 0 ? url.substring(s) : url.substring(s, e);
        }

        // YouTube ?v= or &v=
        if (url.indexOf("?v=") >= 0 || url.indexOf("&v=") >= 0) {
            int s = url.indexOf("v=") + 2;
            int e = url.indexOf("&", s);
            return e < 0 ? url.substring(s) : url.substring(s, e);
        }

        // youtu.be/ID
        if (url.indexOf("youtu.be/") >= 0) {
            int s = url.indexOf("youtu.be/") + 9;
            int e = url.indexOf("?", s);
            return e < 0 ? url.substring(s) : url.substring(s, e);
        }

        // TikTok /video/DIGITS
        if (url.indexOf("/video/") >= 0) {
            int s = url.indexOf("/video/") + 7;
            int e = s;
            while (e < url.length() && 
                   url.charAt(e) >= '0' && 
                   url.charAt(e) <= '9') e++;
            if (e > s) return url.substring(s, e);
        }

        // Fallback: last segment
        int s = url.lastIndexOf('/') + 1;
        String seg = url.substring(s);
        int q = seg.indexOf('?');
        return q >= 0 ? seg.substring(0, q) : seg;
    }

    private void showUrlEditor() {
        final TextBox tb = new TextBox("Video URL", inputUrl, 512, TextField.URL);
        // OK → left softkey (positive confirm), Cancel → right (dismiss)
        final Command ok = new Command("OK",     Command.OK,     1);
        final Command bk = new Command("Cancel", Command.CANCEL, 1);
        tb.addCommand(ok);
        tb.addCommand(bk);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == ok) {
                    String v = tb.getString().trim();
                    if (v.length() > 0) {
                        inputUrl  = v;
                        statusMsg = "URL set. Press 'Parse Site'";
                        dlLinks.removeAllElements();
                        selLink = 0;
                    }
                }
                midlet.getDisplay().setCurrent(ConvertScreen.this);
                repaint();
            }
        });
        midlet.getDisplay().setCurrent(tb);
    }

    // Utility methods

    private String stripTags(String s) {
        if (s == null) return "";
        StringBuffer sb = new StringBuffer();
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if      (c == '<') in = true;
            else if (c == '>') in = false;
            else if (!in)      sb.append(c);
        }
        return sb.toString();
    }

    private String truncate(String s, int maxW, Font f) {
        if (s == null) return "";
        if (f.stringWidth(s) <= maxW) return s;
        while (s.length() > 3 && f.stringWidth(s + "..") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "..";
    }

    private int drawWrapped(Graphics g, String text, int x, int y, int maxW, Font f) {
        if (text == null || text.length() == 0) return y;
        g.setFont(f);
        int cur = 0, lh = f.getHeight() + 2;
        while (cur < text.length()) {
            int nl = text.indexOf('\n', cur);
            String line = nl >= 0 ? text.substring(cur, nl) : text.substring(cur);
            while (line.length() > 0) {
                int len = line.length();
                while (len > 1 && f.substringWidth(line, 0, len) > maxW) len--;
                g.drawSubstring(line, 0, len, x, y, Graphics.TOP | Graphics.LEFT);
                y += lh;
                line = line.substring(len);
            }
            cur = nl >= 0 ? nl + 1 : text.length();
        }
        return y;
    }

    private void ensureVisible() {
        int y0   = 30 + fontSmall.getHeight() + 4;
        int rowH = fontBold.getHeight() + fontSmall.getHeight() + 10 + 3;
        int itemTop = y0 + selLink * rowH;
        int h = getHeight();
        if (itemTop - scrollY < y0) {
            scrollY = itemTop - y0;
        } else if (itemTop - scrollY + rowH > h - 20) {
            scrollY = itemTop + rowH - (h - 20);
        }
        if (scrollY < 0) scrollY = 0;
    }
}