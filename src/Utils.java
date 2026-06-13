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


interface HttpCallback {
    void onSuccess(String content, String baseUrl);
    void onError(String errorMsg);
}

interface DlListener {
    void onProgress(long downloaded, long total, int percent);
    void onComplete(String savedPath, long totalBytes);
    void onError(String msg);
}



class StringUtils {
    public static int lastIndexOf(String src, String sub, int fromIndex) {
        if (src==null||sub==null) return -1;
        int sl=sub.length(), srcl=src.length();
        if (fromIndex>=srcl) fromIndex=srcl-1;
        if (fromIndex<0||sl==0) return -1;
        for (int i=fromIndex;i>=0;i--) {
            if (i+sl>srcl) continue;
            if (src.substring(i,i+sl).equals(sub)) return i;
        }
        return -1;
    }
}

// ======================== URL Utils ========================

class UrlUtils {
    static final String PROXY_PREFIX =
        "http://"+HttpRequestRunnable.PROXY_HOST+"/?url=";

    public static String decode(String s) {
        if (s==null) return "";
        StringBuffer sb=new StringBuffer(); int i=0;
        while (i<s.length()) {
            char c=s.charAt(i);
            if (c=='%'&&i+2<s.length()) {
                try { int v=Integer.parseInt(s.substring(i+1,i+3),16);
                      sb.append((char)v); i+=3; }
                catch(Exception e) { sb.append(c); i++; }
            } else if (c=='+') { sb.append(' '); i++; }
            else { sb.append(c); i++; }
        }
        return sb.toString();
    }

    public static String stripProxy(String url) {
        if (url==null) return null;
        String r=url;
        for (int s=0;s<10;s++) {
            if (r.startsWith(PROXY_PREFIX)) {
                String inner=decode(r.substring(PROXY_PREFIX.length()));
                if (inner.length()>0&&!inner.equals(r)) { r=inner; continue; }
            }
            String once=decode(r);
            if (once.startsWith(PROXY_PREFIX)&&!once.equals(r)) {
                String inner=decode(once.substring(PROXY_PREFIX.length()));
                if (inner.length()>0) { r=inner; continue; }
            }
            break;
        }
        return r;
    }
}

// ======================== Media Utils ========================

class MediaUtils {
    public static boolean isMediaUrl(String url) {
        if (url==null) return false;
        String lo=url.toLowerCase(); int q=lo.indexOf('?'); if(q>0)lo=lo.substring(0,q);
        return lo.endsWith(".mp4")||lo.endsWith(".3gp")||lo.endsWith(".mp3")
            ||lo.endsWith(".m4a")||lo.endsWith(".m4v")||lo.endsWith(".flv")
            ||lo.endsWith(".avi")||lo.endsWith(".ogg")||lo.endsWith(".wav")||lo.endsWith(".amr");
    }
    public static String guessMime(String url) {
        if (url==null) return "application/octet-stream";
        String lo=url.toLowerCase(); int q=lo.indexOf('?'); if(q>0)lo=lo.substring(0,q);
        if (lo.endsWith(".mp4")||lo.endsWith(".m4v")) return "video/mp4";
        if (lo.endsWith(".3gp")) return "video/3gpp";
        if (lo.endsWith(".mp3")) return "audio/mpeg";
        if (lo.endsWith(".m4a")) return "audio/mp4";
        if (lo.endsWith(".ogg")) return "audio/ogg";
        if (lo.endsWith(".wav")) return "audio/wav";
        if (lo.endsWith(".amr")) return "audio/amr";
        return "application/octet-stream";
    }
    public static boolean isDirectlyPlayable(String mime) {
        if (mime==null) return false;
        return mime.equals("video/3gpp")||mime.equals("audio/mpeg")||mime.equals("audio/amr");
    }
}

// ======================== Cookie Manager ========================

class UrlEncoder {
    public static String encode(String s) {
        if (s==null) return "";
        StringBuffer sb=new StringBuffer();
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if ((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')
                ||c=='-'||c=='_'||c=='.'||c=='~') { sb.append(c); }
            else if (c==' ') { sb.append('+'); }
            else {
                byte[] bytes;
                try { bytes=String.valueOf(c).getBytes("UTF-8"); }
                catch(Exception e) { bytes=new byte[]{(byte)c}; }
                for (int b=0;b<bytes.length;b++) {
                    sb.append('%');
                    int bv=bytes[b]&0xFF;
                    String hex=Integer.toHexString(bv).toUpperCase();
                    if (hex.length()==1) sb.append('0');
                    sb.append(hex);
                }
            }
        }
        return sb.toString();
    }
}

// ======================== HTML Decoder ========================

class HtmlDecoder {
    public static String decode(String s) {
        if (s==null) return "";
        StringBuffer sb=new StringBuffer(); int i=0;
        while (i<s.length()) {
            if (s.charAt(i)=='&') {
                int semi=s.indexOf(';',i);
                if (semi>i&&semi-i<12) {
                    String ent=s.substring(i+1,semi);
                    if      (ent.equals("amp"))  sb.append('&');
                    else if (ent.equals("lt"))   sb.append('<');
                    else if (ent.equals("gt"))   sb.append('>');
                    else if (ent.equals("quot")) sb.append('"');
                    else if (ent.equals("apos")) sb.append('\'');
                    else if (ent.equals("nbsp")) sb.append(' ');
                    else if (ent.startsWith("#")) {
                        try {
                            int code=(ent.length()>1&&(ent.charAt(1)=='x'||ent.charAt(1)=='X'))
                                ?Integer.parseInt(ent.substring(2),16)
                                :Integer.parseInt(ent.substring(1));
                            sb.append((char)code);
                        } catch(Exception e) { sb.append('?'); }
                    } else { sb.append('&'); sb.append(ent); sb.append(';'); }
                    i=semi+1; continue;
                }
            }
            sb.append(s.charAt(i)); i++;
        }
        return sb.toString();
    }
}

// ======================== HTTP Runner ========================

class CookieManager {
    private static Hashtable cookieStore = new Hashtable();

    public static void storeCookies(HttpConnection conn) {
        try {
            String host=HttpRequestRunnable.PROXY_HOST;
            for (int i=0;i<20;i++) {
                String key=conn.getHeaderFieldKey(i);
                if (key==null) { if (i>5) break; continue; }
                if (!key.equalsIgnoreCase("Set-Cookie")) continue;
                String val=conn.getHeaderField(i); if (val==null) continue;
                String nv=val; int semi=val.indexOf(';'); if(semi>0)nv=val.substring(0,semi).trim();
                String cn=nv; int eq=nv.indexOf('='); if(eq>0)cn=nv.substring(0,eq);
                String ex=(String)cookieStore.get(host);
                if (ex==null||ex.length()==0) {
                    cookieStore.put(host,nv);
                } else if (ex.indexOf(cn+"=")>=0) {
                    String[] parts=splitCookies(ex);
                    StringBuffer sb=new StringBuffer();
                    for (int j=0;j<parts.length;j++) {
                        String p=parts[j].trim();
                        if (!p.startsWith(cn+"=")) { if(sb.length()>0)sb.append("; "); sb.append(p); }
                    }
                    if (sb.length()>0) sb.append("; "); sb.append(nv);
                    cookieStore.put(host,sb.toString());
                } else { cookieStore.put(host,ex+"; "+nv); }
            }
        } catch(IOException e){}
    }

    private static String[] splitCookies(String c) {
        Vector v=new Vector(); int s=0;
        while (s<c.length()) {
            int semi=c.indexOf(';',s);
            if (semi<0) { v.addElement(c.substring(s)); break; }
            v.addElement(c.substring(s,semi)); s=semi+1;
        }
        String[] a=new String[v.size()];
        for (int i=0;i<v.size();i++) a[i]=(String)v.elementAt(i);
        return a;
    }

    public static String getCookieString(String host) {
        String c=(String)cookieStore.get(host); return c!=null?c:"";
    }

    public static void injectCookies(HttpConnection conn, String host) {
        String c=getCookieString(host);
        if (c.length()>0) try { conn.setRequestProperty("Cookie",c); } catch(IOException e){}
    }
}

// ======================== URL Encoder ========================

class LoginStore {
    private static final String STORE_NAME = "DashTubeLogin";

    public static String[] loadCredentials() {
        String[] r = new String[3];
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE_NAME, false);
            int n = rs.getNumRecords();
            try { if (n>=1) r[0]=new String(rs.getRecord(1),"UTF-8"); } catch(Exception e){}
            try { if (n>=2) r[1]=new String(rs.getRecord(2),"UTF-8"); } catch(Exception e){}
            try { if (n>=3) r[2]=new String(rs.getRecord(3),"UTF-8"); } catch(Exception e){}
        } catch(RecordStoreNotFoundException e) {
        } catch(Exception e) {
            try { RecordStore.deleteRecordStore(STORE_NAME); } catch(Exception ex){}
        } finally {
            if (rs!=null) try { rs.closeRecordStore(); } catch(Exception e){}
        }
        return r;
    }

    public static void saveCredentials(String u, String p, String t) {
        RecordStore rs = null;
        try {
            try { RecordStore.deleteRecordStore(STORE_NAME); } catch(RecordStoreNotFoundException e){}
            rs = RecordStore.openRecordStore(STORE_NAME, true);
            byte[] ub=(u!=null?u:"").getBytes("UTF-8");
            byte[] pb=(p!=null?p:"").getBytes("UTF-8");
            byte[] tb=(t!=null?t:"").getBytes("UTF-8");
            rs.addRecord(ub,0,ub.length);
            rs.addRecord(pb,0,pb.length);
            rs.addRecord(tb,0,tb.length);
        } catch(Exception e) {
        } finally {
            if (rs!=null) try { rs.closeRecordStore(); } catch(Exception e){}
        }
    }

    public static void clearCredentials() {
        try { RecordStore.deleteRecordStore(STORE_NAME); } catch(Exception e){}
    }

    public static boolean hasSavedCredentials() {
        String[] c = loadCredentials();
        return c[0]!=null&&c[0].length()>0&&c[1]!=null&&c[1].length()>0;
    }
}

// ======================== StringUtils ========================

class HttpRequestRunnable implements Runnable {
    private String       targetUrl, postData;
    private HttpCallback callback;
    private boolean      isPost;

    static final String PROXY_HOST = "2yxa-proxy.ndukadavid70.workers.dev";

    public HttpRequestRunnable(String url, String postData,
                               boolean isPost, HttpCallback cb) {
        this.targetUrl=url; this.postData=postData;
        this.isPost=isPost; this.callback=cb;
    }

    public void run() {
        HttpConnection conn=null; InputStream is=null;
        try {
            String clean=UrlUtils.stripProxy(targetUrl);
            String pUrl="http://"+PROXY_HOST+"/?url="+UrlEncoder.encode(clean);
            conn=(HttpConnection)Connector.open(pUrl);
            conn.setRequestMethod(isPost?HttpConnection.POST:HttpConnection.GET);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (J2ME; MIDP-2.0; CLDC-1.1) DashTube/1.0");
            conn.setRequestProperty("Accept","text/html,application/xhtml+xml,*/*");
            CookieManager.injectCookies(conn,PROXY_HOST);
            if (isPost&&postData!=null) {
                byte[] pb=postData.getBytes("UTF-8");
                conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                conn.setRequestProperty("Content-Length",Integer.toString(pb.length));
                OutputStream os=conn.openOutputStream(); os.write(pb); os.close();
            }
            int rc=conn.getResponseCode();
            if (rc==HttpConnection.HTTP_OK) {
                CookieManager.storeCookies(conn);
                is=conn.openInputStream();
                ByteArrayOutputStream baos=new ByteArrayOutputStream(4096);
                byte[] buf=new byte[1024]; int n;
                while ((n=is.read(buf))!=-1) baos.write(buf,0,n);
                callback.onSuccess(new String(baos.toByteArray(),"UTF-8"),clean);
            } else { callback.onError("HTTP "+rc); }
        } catch(Exception e) { callback.onError("Net: "+e.getMessage()); }
        finally {
            try{if(is!=null)is.close();}catch(Exception e){}
            try{if(conn!=null)conn.close();}catch(Exception e){}
        }
    }
}

// ======================== Download-then-Play Runnable ========================

class DownloadAndPlayRunnable implements Runnable {
    private String remoteUrl, localPath;
    private DlListener listener;
    private boolean autoPlay;

    public DownloadAndPlayRunnable(String remoteUrl, String localPath,
                                   DlListener listener, boolean autoPlay) {
        this.remoteUrl=remoteUrl; this.localPath=localPath;
        this.listener=listener;  this.autoPlay=autoPlay;
    }

    public void run() {
        String cleanUrl=UrlUtils.stripProxy(remoteUrl);
        String fetchUrl=cleanUrl;
        if (fetchUrl.startsWith("https://video.2yxa.mobi/"))
            fetchUrl="http://video.2yxa.mobi/"+fetchUrl.substring("https://video.2yxa.mobi/".length());
        else if (fetchUrl.startsWith("https://"))
            fetchUrl="http://"+fetchUrl.substring(8);

        HttpConnection conn=null; InputStream is=null;
        FileConnection fc=null;  OutputStream os=null;
        try {
            conn=(HttpConnection)Connector.open(fetchUrl);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (J2ME; MIDP-2.0; CLDC-1.1) DashTube/1.0");
            conn.setRequestProperty("Accept","video/mp4,video/3gpp,audio/mpeg,audio/mp4,*/*");
            String ck=CookieManager.getCookieString(HttpRequestRunnable.PROXY_HOST);
            if (ck==null||ck.length()==0) ck=CookieManager.getCookieString("video.2yxa.mobi");
            if (ck!=null&&ck.length()>0) try{conn.setRequestProperty("Cookie",ck);}catch(IOException io){}
            int rc=conn.getResponseCode();
            if (rc!=HttpConnection.HTTP_OK) {
                if (listener!=null) listener.onError("Server HTTP "+rc); return;
            }
            long total=conn.getLength();
            CookieManager.storeCookies(conn);
            is=conn.openInputStream();
            fc=(FileConnection)Connector.open(localPath,Connector.READ_WRITE);
            if (fc.exists()) fc.delete(); fc.create();
            os=fc.openOutputStream();
            byte[] buf=new byte[4096]; long saved=0; int n;
            while ((n=is.read(buf))!=-1) {
                os.write(buf,0,n); saved+=n;
                if (listener!=null) {
                    int pct=(total>0)?(int)((saved*100L)/total):-1;
                    listener.onProgress(saved,total,pct);
                }
            }
            os.flush();
            final String path=localPath; final long bytes=saved;
            if (autoPlay) {
                try {
                    Player p=Manager.createPlayer(localPath);
                    p.realize();
                    VolumeControl vc=(VolumeControl)p.getControl("VolumeControl");
                    if (vc!=null) vc.setLevel(100);
                    p.start();
                } catch(Exception pe) {
                    if (listener!=null) listener.onError("PLAY_FAIL:"+localPath); return;
                }
            }
            if (listener!=null) listener.onComplete(path,bytes);
        } catch(Exception e) {
            if (listener!=null) listener.onError("DL: "+e.getMessage());
        } finally {
            try{if(os!=null)os.close();}catch(Exception e){}
            try{if(is!=null)is.close();}catch(Exception e){}
            try{if(fc!=null)fc.close();}catch(Exception e){}
            try{if(conn!=null)conn.close();}catch(Exception e){}
        }
    }
}

// ======================== File Manager ========================