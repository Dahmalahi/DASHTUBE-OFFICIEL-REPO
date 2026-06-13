/*
 * MusicSearchParser.java
 * MIDP 2.0 / CLDC 1.1 compatible
 * All parsing is now done inside MusicSearchScreen.
 * This file kept as a thin wrapper for compatibility.
 */

import javax.microedition.io.*;
import java.io.*;
import java.util.Vector;

public class MusicSearchParser {

    private static final String PROXY_BASE   =
            "http://2yxa-proxy.ndukadavid70.workers.dev/?url=";
    private static final String SEARCH_BASE  =
            "http://video.2yxa.mobi/?query=";
    private static final String SEARCH_SUFFIX =
            "&sort=relevance&server=you";

    /**
     * Fetches and returns raw HTML for a music search query,
     * routing the request through your Cloudflare proxy.
     *
     * @param query  plain-text search query (e.g. "boruto music audio")
     * @return raw HTML string from 2yxa.mobi
     */
    public static String fetchSearchHtml(String query) throws Exception {
        // Build real URL
        String targetUrl = SEARCH_BASE
                + UrlEncoder.encode(query)
                + SEARCH_SUFFIX;

        // Wrap in proxy
        String proxyUrl = PROXY_BASE + UrlEncoder.encode(targetUrl);

        return fetchHtml(proxyUrl);
    }

    // ----------------------------------------------------------------
    // HTTP helper - CLDC 1.1 safe
    // ----------------------------------------------------------------
    private static String fetchHtml(String url) throws Exception {
        HttpConnection conn = null;
        InputStream    is   = null;
        try {
            conn = (HttpConnection) Connector.open(url);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (J2ME; MIDP-2.0; CLDC-1.1) DashTube/1.0");
            conn.setRequestProperty("Accept", "text/html");

            int rc = conn.getResponseCode();
            if (rc != HttpConnection.HTTP_OK)
                throw new IOException("HTTP " + rc);

            is = conn.openInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int    n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);

            try {
                return new String(baos.toByteArray(), "UTF-8");
            } catch (Exception e) {
                return new String(baos.toByteArray());
            }
        } finally {
            if (is   != null) try { is.close();   } catch (Exception e) {}
            if (conn != null) try { conn.close(); } catch (Exception e) {}
        }
    }

    // ----------------------------------------------------------------
    // CLDC 1.1 safe string replace (no replaceAll / String.replace(String))
    // ----------------------------------------------------------------
    static String replaceAll(String src, String find, String rep) {
        if (src == null || find == null || find.length() == 0) return src;
        StringBuffer sb  = new StringBuffer();
        int          pos = 0;
        int          fl  = find.length();
        while (true) {
            int idx = src.indexOf(find, pos);
            if (idx == -1) { sb.append(src.substring(pos)); break; }
            sb.append(src.substring(pos, idx));
            sb.append(rep);
            pos = idx + fl;
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // CLDC 1.1 safe tag stripper (no regex)
    // ----------------------------------------------------------------
    static String stripTags(String html) {
        if (html == null) return "";
        StringBuffer sb  = new StringBuffer();
        boolean      in  = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if      (c == '<') in = true;
            else if (c == '>') in = false;
            else if (!in)      sb.append(c);
        }
        return sb.toString();
    }
}