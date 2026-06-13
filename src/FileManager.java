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


class FileManager {
    public static final String APP_DIR    = "DashTube/";
    public static final String VIDEO_DIR  = "DashTube/videos/";
    public static final String MUSIC_DIR  = "DashTube/music/";
    public static final String PHOTOS_DIR = "DashTube/photos/";
    public static final String IMAGES_DIR = "DashTube/images/";
    public static final String CACHE_DIR  = "DashTube/cache/";
    public static final String TEMP_FILE  = "DashTube/temp_video";

    private static final String[] SD_HINTS = {
        "card","Card","CARD","mmc","MMC","sdcard","SDCard",
        "sd","SD","extern","Extern","external","memory",
        "Memory","storage","E:","F:","G:","removable"
    };
    private static final String[] PHONE_HINTS = {
        "phone","Phone","internal","C:","D:","private","root"
    };

    private String  bestRoot, videoDirUrl, musicDirUrl, photosDirUrl, imagesDirUrl;
    private boolean ready;
    private String  initError;
    private static FileManager instance;

    public static FileManager getInstance() {
        if (instance==null) instance=new FileManager(); return instance;
    }
    private FileManager() { init(); }

    private void init() {
        ready=false; initError=null;
        try {
            bestRoot=detectBestRoot();
            if (bestRoot==null) { initError="No file system root found"; return; }
            videoDirUrl  = bestRoot + VIDEO_DIR;
            musicDirUrl  = bestRoot + MUSIC_DIR;
            photosDirUrl = bestRoot + PHOTOS_DIR;
            imagesDirUrl = bestRoot + IMAGES_DIR;
            ensureDir(bestRoot + APP_DIR);
            ensureDir(videoDirUrl);
            ensureDir(musicDirUrl);
            ensureDir(photosDirUrl);
            ensureDir(imagesDirUrl);
            ensureDir(bestRoot + CACHE_DIR);
            ready=true;
        } catch(Exception e) { initError="Init error: "+e.getMessage(); }
    }

    private String detectBestRoot() {
        Enumeration roots;
        try { roots=FileSystemRegistry.listRoots(); } catch(Exception e) { return null; }
        if (roots==null) return null;
        String best=null; int bestScore=-1;
        while (roots.hasMoreElements()) {
            String root=(String)roots.nextElement(), url="file:///"+root;
            int score=scoreRoot(root,url);
            if (score>bestScore&&isAccessible(url)) { bestScore=score; best=url; }
        }
        return best;
    }

    private int scoreRoot(String root, String url) {
        String r=root.toLowerCase(); int score=0;
        for (int i=0;i<SD_HINTS.length;i++)
            if (r.indexOf(SD_HINTS[i].toLowerCase())>=0) { score+=100; break; }
        if (score==0)
            for (int i=0;i<PHONE_HINTS.length;i++)
                if (r.indexOf(PHONE_HINTS[i].toLowerCase())>=0) { score+=10; break; }
        if (score==0) score=1;
        long free=getFreeSpace(url);
        if      (free>100L*1024*1024) score+=50;
        else if (free>10L*1024*1024)  score+=30;
        else if (free>1L*1024*1024)   score+=10;
        else if (free>0)              score+=1;
        return score;
    }

    private boolean isAccessible(String url) {
        FileConnection fc=null;
        try { fc=(FileConnection)Connector.open(url,Connector.READ); return fc.exists(); }
        catch(Exception e) { return false; } finally { closeQ(fc); }
    }

    private long getFreeSpace(String url) {
        FileConnection fc=null;
        try { fc=(FileConnection)Connector.open(url,Connector.READ); return fc.availableSize(); }
        catch(Exception e) { return 0; } finally { closeQ(fc); }
    }

    private void ensureDir(String dirUrl) throws IOException {
        FileConnection fc=null;
        try {
            fc=(FileConnection)Connector.open(dirUrl,Connector.READ_WRITE);
            if (!fc.exists()) fc.mkdir();
        } finally { closeQ(fc); }
    }

    public boolean isReady()           { return ready; }
    public String  getInitError()      { return initError; }
    public String  getBestRoot()       { return bestRoot; }
    public String  getVideoDirUrl()    { return videoDirUrl; }
    public String  getMusicDirUrl()    { return musicDirUrl; }
    public String  getPhotosDirUrl()   { return photosDirUrl; }
    public String  getImagesDirUrl()   { return imagesDirUrl; }

    public String getTempFileUrl(String mediaUrl) {
        String ext = ".mp4";
        String dir = bestRoot + TEMP_FILE; // default: video temp path
        if (mediaUrl != null) {
            String lo = mediaUrl.toLowerCase();
            // ── image extensions → images dir ──
            if      (lo.indexOf(".jpg")>=0||lo.indexOf(".jpeg")>=0) { ext=".jpg";  dir=imagesDirUrl+"_temp"; }
            else if (lo.indexOf(".png")>=0)                          { ext=".png";  dir=imagesDirUrl+"_temp"; }
            else if (lo.indexOf(".gif")>=0)                          { ext=".gif";  dir=imagesDirUrl+"_temp"; }
            // ── audio extensions → music dir ──
            else if (lo.indexOf(".mp3")>=0)                          { ext=".mp3";  dir=musicDirUrl+"_temp"; }
            else if (lo.indexOf(".m4a")>=0)                          { ext=".m4a";  dir=musicDirUrl+"_temp"; }
            else if (lo.indexOf(".aac")>=0)                          { ext=".aac";  dir=musicDirUrl+"_temp"; }
            else if (lo.indexOf(".ogg")>=0)                          { ext=".ogg";  dir=musicDirUrl+"_temp"; }
            // ── video extensions → video temp path ──
            else if (lo.indexOf(".3gp")>=0)                          { ext=".3gp"; }
        }
        return dir + ext;
    }

    /** Pick save directory based on filename extension */
    public String getDirForFile(String filename) {
        if (filename == null) return videoDirUrl;
        String lo = filename.toLowerCase();
        if (lo.endsWith(".jpg") || lo.endsWith(".jpeg") || lo.endsWith(".png") || lo.endsWith(".gif"))
            return imagesDirUrl;
        if (lo.endsWith(".mp3") || lo.endsWith(".m4a") || lo.endsWith(".aac") || lo.endsWith(".ogg"))
            return musicDirUrl;
        return videoDirUrl;
    }

    public void saveImage(String remoteUrl, String filename, DlListener listener) {
        if (!ready) { if (listener!=null) listener.onError("Storage: "+initError); return; }
        new DownloadAndPlayRunnable(remoteUrl, imagesDirUrl+sanitize(filename), listener, false).run();
    }

    public void saveMusic(String remoteUrl, String filename, DlListener listener) {
        if (!ready) { if (listener!=null) listener.onError("Storage: "+initError); return; }
        new DownloadAndPlayRunnable(remoteUrl, musicDirUrl+sanitize(filename), listener, false).run();
    }

    /**
     * Generic save: downloads remoteUrl and writes to dirUrl+sanitize(filename).
     * dirUrl must end with '/'. Uses getDirForFile() if dirUrl is null.
     */
    public void saveToDir(String remoteUrl, String dirUrl, String filename, DlListener listener) {
        if (!ready) { if (listener!=null) listener.onError("Storage: "+initError); return; }
        if (dirUrl == null) dirUrl = getDirForFile(filename);
        new DownloadAndPlayRunnable(remoteUrl, dirUrl+sanitize(filename), listener, false).run();
    }

    public String[] listImages() { return listDir(imagesDirUrl); }
    public String[] listMusic()  { return listDir(musicDirUrl);  }
    public String[] listPhotos() { return listDir(photosDirUrl); }

    public String[] listDir(String dirUrl) {
        if (!ready || dirUrl == null) return new String[0];
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(dirUrl, Connector.READ);
            if (!fc.exists()) return new String[0];
            Enumeration files = fc.list("*", false);
            Vector v = new Vector();
            while (files.hasMoreElements()) {
                String nm = (String) files.nextElement();
                if (!nm.endsWith("/")) v.addElement(nm);
            }
            String[] a = new String[v.size()];
            for (int i = 0; i < v.size(); i++) a[i] = (String) v.elementAt(i);
            return a;
        } catch (Exception e) { return new String[0]; }
        finally { closeQ(fc); }
    }

    public String getAvailableSpaceString() {
        long b=(bestRoot!=null)?getFreeSpace(bestRoot):0;
        if (b<=0) return "unknown";
        if (b<1024) return b+" B";
        if (b<1024*1024) return (b/1024)+" KB";
        return (b/(1024*1024))+" MB";
    }

    public String getRootDescription() {
        if (bestRoot==null) return "No storage";
        String root=bestRoot.startsWith("file:///")?bestRoot.substring(8):bestRoot;
        return root+" ("+guessType(root)+") - "+getAvailableSpaceString()+" free";
    }

    private String guessType(String root) {
        String r=root.toLowerCase();
        for (int i=0;i<SD_HINTS.length;i++)
            if (r.indexOf(SD_HINTS[i].toLowerCase())>=0) return "SD card";
        for (int i=0;i<PHONE_HINTS.length;i++)
            if (r.indexOf(PHONE_HINTS[i].toLowerCase())>=0) return "phone";
        return "storage";
    }

    public String[] listVideos() {
        if (!ready) return new String[0];
        FileConnection fc=null;
        try {
            fc=(FileConnection)Connector.open(videoDirUrl,Connector.READ);
            if (!fc.exists()) return new String[0];
            Enumeration files=fc.list("*",false);
            Vector v=new Vector();
            while (files.hasMoreElements()) {
                String nm=(String)files.nextElement();
                if (!nm.endsWith("/")) v.addElement(nm);
            }
            String[] a=new String[v.size()];
            for (int i=0;i<v.size();i++) a[i]=(String)v.elementAt(i);
            return a;
        } catch(Exception e) { return new String[0]; }
        finally { closeQ(fc); }
    }

    public boolean deleteVideo(String filename) {
        if (!ready) return false;
        FileConnection fc=null;
        try {
            fc=(FileConnection)Connector.open(videoDirUrl+sanitize(filename),Connector.READ_WRITE);
            if (fc.exists()) { fc.delete(); return true; }
            return false;
        } catch(Exception e) { return false; }
        finally { closeQ(fc); }
    }

    public void downloadAndSave(String remoteUrl, String filename, DlListener listener) {
        if (!ready) { if (listener!=null) listener.onError("Storage: "+initError); return; }
        String dir = getDirForFile(filename);
        new DownloadAndPlayRunnable(remoteUrl, dir+sanitize(filename), listener, false).run();
    }

    public static String sanitize(String name) {
        if (name==null) return "file";
        StringBuffer sb=new StringBuffer();
        for (int i=0;i<name.length();i++) {
            char c=name.charAt(i);
            if ((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='.'||c=='-'||c=='_')
                sb.append(c); else sb.append('_');
        }
        String s=sb.toString();
        while (s.length()>0&&(s.charAt(0)=='.'||s.charAt(0)=='_')) s=s.substring(1);
        return s.length()>0?s:"file";
    }

    public static String filenameFromUrl(String url) {
        if (url==null) return "download.mp4";
        int slash=url.lastIndexOf('/');
        String name=(slash>=0&&slash<url.length()-1)?url.substring(slash+1):url;
        int q=name.indexOf('?'); if (q>=0) name=name.substring(0,q);
        name=sanitize(name);
        return name.length()>0?name:"download.mp4";
    }

    void closeQ(Object o) {
        if (o==null) return;
        try {
            if      (o instanceof Connection)   ((Connection)o).close();
            else if (o instanceof InputStream)  ((InputStream)o).close();
            else if (o instanceof OutputStream) ((OutputStream)o).close();
        } catch(Exception e){}
    }
}

// ======================== Download URL Filter ========================

class DownloadUrlFilter {
    public static boolean isCompatibleLink(String url) {
        if (url==null) return false; String lo=url.toLowerCase();
        if (lo.indexOf("attach.php")>=0||lo.indexOf("&telegram")>=0||lo.indexOf("?telegram")>=0) return false;
        if (lo.indexOf("/android/mp4/")>=0||lo.indexOf("/android/mp3/")>=0||lo.indexOf("/android/3gp/")>=0) return true;
        return lo.indexOf("/mp4/2yxa_mobi_")>=0||lo.indexOf("/3gp/2yxa_mobi_")>=0||lo.indexOf("/mp3/2yxa_mobi_")>=0;
    }
    public static boolean isOriginalLink(String url) {
        if (url==null) return false; String lo=url.toLowerCase();
        if (lo.indexOf("attach.php")>=0||lo.indexOf("&telegram")>=0||lo.indexOf("?telegram")>=0) return false;
        if (isCompatibleLink(url)) return false;
        return (lo.indexOf("/mp4/")>=0||lo.indexOf("/3gp/")>=0||lo.indexOf("/mp3/")>=0)&&lo.indexOf("2yxa.mobi")>=0;
    }
    public static boolean isJunkLink(String url) {
        if (url==null) return true; String lo=url.toLowerCase();
        return lo.indexOf("attach.php")>=0||lo.indexOf("&telegram")>=0||lo.indexOf("?telegram")>=0;
    }
    public static String buildLabel(String url, String linkText) {
        if (url==null) return "Download"; String lo=url.toLowerCase();
        String fmt="";
        if      (lo.indexOf("/mp4/")>=0) fmt="MP4";
        else if (lo.indexOf("/mp3/")>=0) fmt="MP3";
        else if (lo.indexOf("/3gp/")>=0) fmt="3GP";
        String res=extractResolution(url);
        String base;
        if (linkText!=null&&linkText.length()>0&&!linkText.equalsIgnoreCase("download")) {
            base=linkText;
        } else {
            base="\u2193 Download";
            if (fmt.length()>0) base+=" "+fmt;
            if (res!=null)      base+=" "+res;
        }
        return base;
    }
    public static String extractResolution(String url) {
        if (url==null) return null; String lo=url.toLowerCase(); int idx=0;
        while (idx<lo.length()) {
            int xp=lo.indexOf("x",idx); if (xp<0) break;
            int ns=xp-1;
            while (ns>=0&&lo.charAt(ns)>='0'&&lo.charAt(ns)<='9') ns--;
            ns++;
            int ne=xp+1;
            while (ne<lo.length()&&lo.charAt(ne)>='0'&&lo.charAt(ne)<='9') ne++;
            if (xp-ns>=2&&ne-xp-1>=2) return url.substring(ns,ne);
            idx=xp+1;
        }
        return null;
    }
}

// ======================== Page Item ========================