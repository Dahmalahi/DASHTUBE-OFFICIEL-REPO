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


class PageItem {
    static final int TYPE_TEXT=0,TYPE_LINK=1,TYPE_VIDEO_RESULT=2,
        TYPE_SEARCH_FORM=3,TYPE_PAGINATION=5,TYPE_CAPTCHA=6,
        TYPE_DOWNLOAD=7,TYPE_CONVERT=8,TYPE_SAVED=9;
    int type; String text, url, extra, extra2, videoId;
    PageItem(int t,String tx)          { type=t; text=tx; }
    PageItem(int t,String tx,String u) { type=t; text=tx; url=u; }
}

// ======================== Site Parser ========================

class SiteParser {
    static final String BASE_DOMAIN="http://video.2yxa.mobi";
    private Vector  items;
    private String  sessionToken;
    private boolean hasCaptcha;
    private String  captchaAction,captchaInputName,captchaImageUrl;
    private boolean isVideoPage,isConversionReadyPage;

    public SiteParser() { items=new Vector(); }
    public Vector  getItems()              { return items; }
    public String  getSessionToken()       { return sessionToken; }
    public boolean hasCaptcha()            { return hasCaptcha; }
    public String  getCaptchaAction()      { return captchaAction; }
    public String  getCaptchaInputName()   { return captchaInputName; }
    public String  getCaptchaImageUrl()    { return captchaImageUrl; }
    public boolean isConversionReadyPage() { return isConversionReadyPage; }
    /** True if at least one TYPE_DOWNLOAD or TYPE_CONVERT item was parsed. */
    public boolean hasDownloadLinks() {
        for (int i=0;i<items.size();i++) {
            int t=((PageItem)items.elementAt(i)).type;
            if (t==PageItem.TYPE_DOWNLOAD||t==PageItem.TYPE_CONVERT) return true;
        }
        return false;
    }

    public void parse(String html) {
        items.removeAllElements();
        sessionToken=null; hasCaptcha=false;
        captchaAction=captchaInputName=captchaImageUrl=null;
        isVideoPage=isConversionReadyPage=false;
        if (html==null||html.length()==0) return;
        String lower=html.toLowerCase();
        extractSessionToken(html,lower);
        detectCaptcha(html,lower);
        isVideoPage=lower.indexOf("create mp4")>=0||lower.indexOf("create 3gp")>=0||lower.indexOf("preview (3gp")>=0;
        isConversionReadyPage=
            lower.indexOf("file is ready")>=0||lower.indexOf("download mp4")>=0||
            lower.indexOf("download mp3")>=0||lower.indexOf("download 3gp")>=0||
            (lower.indexOf("/android/mp4/")>=0&&lower.indexOf("href=")>=0)||
            (lower.indexOf("/android/mp3/")>=0&&lower.indexOf("href=")>=0)||
            (lower.indexOf("/mp4/2yxa_mobi_")>=0&&lower.indexOf("href=")>=0)||
            (lower.indexOf("/3gp/2yxa_mobi_")>=0&&lower.indexOf("href=")>=0);
        if (isConversionReadyPage) { parseConversionPage(html,lower); }
        else if (isVideoPage)      { parseVideoPage(html,lower); }
        else {
            String sa=extractSearchFormAction(html,lower);
            extractVideoResults(html,lower); extractPagination(html,lower);
            if (countByType(PageItem.TYPE_VIDEO_RESULT)==0) extractNavLinks(html,lower);
            if (sa!=null) {
                PageItem si=new PageItem(PageItem.TYPE_SEARCH_FORM,"[ Search Videos ]",sa);
                si.extra2=sessionToken; items.insertElementAt(si,0);
            }
        }
    }

    private void parseConversionPage(String html,String lower) {
        String title=extractTitle(html);
        if (title!=null&&title.length()>0) items.addElement(new PageItem(PageItem.TYPE_TEXT,title));
        if (lower.indexOf("file is ready")>=0)
            items.addElement(new PageItem(PageItem.TYPE_TEXT,"\u2713 File is ready!"));
        Vector compatLinks=new Vector(),origLinks=new Vector(),convertLinks=new Vector();
        int pos=0;
        while (pos<lower.length()) {
            int hDq=lower.indexOf("href=\"",pos), hSq=lower.indexOf("href='",pos);
            if (hDq<0&&hSq<0) break;
            int hIdx; char quote;
            if      (hDq<0)    {hIdx=hSq;quote='\'';}
            else if (hSq<0)    {hIdx=hDq;quote='"';}
            else if (hDq<=hSq) {hIdx=hDq;quote='"';}
            else               {hIdx=hSq;quote='\'';}
            int us=hIdx+6, ue=html.indexOf(quote,us); if(ue<0){pos=us+1;continue;}
            String rawUrl=HtmlDecoder.decode(html.substring(us,ue).trim());
            if (rawUrl.startsWith("/")) rawUrl="https://video.2yxa.mobi"+rawUrl;
            else if (!rawUrl.startsWith("http://")&&!rawUrl.startsWith("https://")) rawUrl=BASE_DOMAIN+"/"+rawUrl;
            String linkText="";
            int gt=lower.indexOf(">",ue), ac=lower.indexOf("</a>",ue);
            if (gt>=0&&ac>gt) linkText=HtmlDecoder.decode(stripTags(html.substring(gt+1,ac)).trim());
            if (DownloadUrlFilter.isCompatibleLink(rawUrl)) {
                if (!containsUrl(compatLinks,rawUrl)) addPair(compatLinks,rawUrl,linkText);
            } else if (DownloadUrlFilter.isOriginalLink(rawUrl)) {
                if (!containsUrl(origLinks,rawUrl)) addPair(origLinks,rawUrl,linkText);
            } else if (!DownloadUrlFilter.isJunkLink(rawUrl)
                &&rawUrl.toLowerCase().indexOf("mov.php")>=0
                &&rawUrl.toLowerCase().indexOf("&dw")>=0) {
                if (!containsUrl(convertLinks,rawUrl)) addPair(convertLinks,rawUrl,linkText);
            }
            pos=ue+1;
        }
        if (compatLinks.size()>0) {
            items.addElement(new PageItem(PageItem.TYPE_TEXT,"-- Downloads --"));
            for (int i=0;i<compatLinks.size();i+=2) {
                String u=(String)compatLinks.elementAt(i), t=(String)compatLinks.elementAt(i+1);
                items.addElement(new PageItem(PageItem.TYPE_DOWNLOAD,"\u2193 "+DownloadUrlFilter.buildLabel(u,t),u));
            }
        }
        if (origLinks.size()>0) {
            items.addElement(new PageItem(PageItem.TYPE_TEXT,"-- More Downloads --"));
            for (int i=0;i<origLinks.size();i+=2) {
                String u=(String)origLinks.elementAt(i), t=(String)origLinks.elementAt(i+1);
                items.addElement(new PageItem(PageItem.TYPE_DOWNLOAD,"\u2193 "+DownloadUrlFilter.buildLabel(u,t),u));
            }
        }
        if (convertLinks.size()>0) {
            items.addElement(new PageItem(PageItem.TYPE_TEXT,"-- Convert Options --"));
            for (int i=0;i<convertLinks.size();i+=2) {
                String u=(String)convertLinks.elementAt(i), t=(String)convertLinks.elementAt(i+1);
                items.addElement(new PageItem(PageItem.TYPE_CONVERT,"\u25B6 "+(t.length()>0?t:"Convert"),u));
            }
        }
        if (compatLinks.size()==0&&origLinks.size()==0&&convertLinks.size()==0)
            items.addElement(new PageItem(PageItem.TYPE_TEXT,
                "No download links found.\nFile may still be converting.\nPress Refresh."));
    }

    private void addPair(Vector v,String u,String t){v.addElement(u);v.addElement(t!=null?t:"");}
    private boolean containsUrl(Vector v,String u){
        for(int i=0;i<v.size();i+=2) if(u.equals(v.elementAt(i)))return true; return false;
    }

    private void parseVideoPage(String html,String lower) {
        String title=extractTitle(html);
        if (title!=null&&title.length()>0) items.addElement(new PageItem(PageItem.TYPE_TEXT,title));
        int di=lower.indexOf("<b>duration</b>");
        if (di>=0) {
            int ss=lower.indexOf("class=\"sin\"",di);
            if (ss>=0) {
                int so=lower.indexOf(">",ss),sc=lower.indexOf("</span>",ss);
                if (so>=0&&sc>so) items.addElement(new PageItem(PageItem.TYPE_TEXT,"Duration: "+html.substring(so+1,sc).trim()));
            }
        }
        items.addElement(new PageItem(PageItem.TYPE_TEXT,"--- Select Format ---"));
        String vid=extractVideoIdFromPage(html,lower);
        if (vid!=null) items.addElement(new PageItem(PageItem.TYPE_CONVERT,"\u2605 Best for phone (MP4 176x144)",
            BASE_DOMAIN+"/mov.php?id="+vid+"&type=6&poisk=you&dw"));
        int pos=0;
        while (pos<lower.length()) {
            int hi=lower.indexOf("href=\"/mov.php?id=",pos); if(hi<0)break;
            int us=hi+6, ue=lower.indexOf("\"",us); if(ue<0)break;
            String ru=HtmlDecoder.decode(html.substring(us,ue)), rl=ru.toLowerCase();
            if (rl.indexOf("&dw")>=0&&rl.indexOf("rtsp")<0) {
                int ac=lower.indexOf("</a>",ue); String lt="Download";
                if (ac>ue) { int ao=lower.indexOf(">",ue);
                    if (ao>=0&&ao<ac) { String tx=stripTags(html.substring(ao+1,ac)).trim(); if(tx.length()>0)lt=HtmlDecoder.decode(tx); }
                }
                String abs=makeAbsolute(ru); boolean dup=false;
                for(int i=0;i<items.size();i++) if(abs.equals(((PageItem)items.elementAt(i)).url)){dup=true;break;}
                if (!dup) items.addElement(new PageItem(PageItem.TYPE_CONVERT,"\u25B6 "+lt,abs));
            }
            pos=ue+1;
        }
    }

    private String extractVideoIdFromPage(String html,String lower) {
        int i=lower.indexOf("name=\"id\" value=\"");
        if (i>=0){int vs=i+17,ve=html.indexOf("\"",vs);if(ve>vs)return html.substring(vs,ve);}
        i=lower.indexOf("mov.php?id=");
        if (i>=0){int vs=i+11,ve=vs;
            while(ve<html.length()&&html.charAt(ve)!='&'&&html.charAt(ve)!='"'&&html.charAt(ve)!=(char)39&&html.charAt(ve)!=' ')ve++;
            if(ve>vs)return html.substring(vs,ve);}
        return null;
    }

    private String extractVideoId(String href) {
        if (href==null) return null;
        int idPos=href.toLowerCase().indexOf("id="); if(idPos<0)return null;
        int start=idPos+3, end=href.indexOf("&",start); if(end<0)end=href.length();
        String id=href.substring(start,end); return id.length()>0?id:null;
    }

    private String extractTitle(String html) {
        String lo=html.toLowerCase();
        int i=lo.indexOf("<title>"); if(i<0)return null;
        int e=lo.indexOf("</title>",i); if(e<0)return null;
        String t=HtmlDecoder.decode(html.substring(i+7,e).trim());
        int c=t.indexOf(" :: "); if(c>0)t=t.substring(0,c); return t;
    }

    private void extractSessionToken(String html,String lower) {
        String m="name=\"ilove2yxa\""; int i=lower.indexOf(m);
        if (i<0){m="name='ilove2yxa'";i=lower.indexOf(m);}
        if (i<0) return;
        String region=html.substring(Math.max(0,i-50),Math.min(html.length(),i+120));
        String lo=region.toLowerCase();
        int vi=lo.indexOf("value=\"");
        if(vi>=0){int vs=vi+7,ve=region.indexOf("\"",vs);if(ve>vs){sessionToken=region.substring(vs,ve);return;}}
        vi=lo.indexOf("value='");
        if(vi>=0){int vs=vi+7,ve=region.indexOf("'",vs);if(ve>vs)sessionToken=region.substring(vs,ve);}
    }

    public String extractCaptchaImageUrl(String html,String lower) {
        int ii=lower.indexOf("zkod.php"); if(ii<0)return null;
        int ss=StringUtils.lastIndexOf(lower,"src=\"",ii);
        if(ss>=0){ss+=5;int se=lower.indexOf("\"",ss);if(se>ss)return makeAbsolute(html.substring(ss,se));}
        ss=StringUtils.lastIndexOf(lower,"src='",ii);
        if(ss>=0){ss+=5;int se=lower.indexOf("'",ss);if(se>ss)return makeAbsolute(html.substring(ss,se));}
        return null;
    }

    private void detectCaptcha(String html,String lower) {
        if(lower.indexOf("zkod.php")<0&&lower.indexOf("captcha")<0&&lower.indexOf("secretkey")<0)return;
        hasCaptcha=true; captchaImageUrl=extractCaptchaImageUrl(html,lower);
        int fp=0;
        while(fp<lower.length()){
            int fi=lower.indexOf("<form",fp);if(fi<0)break;
            int fe=lower.indexOf(">",fi);if(fe<0)break;
            int fc=lower.indexOf("</form>",fe);if(fc<0)fc=lower.length();
            String body=lower.substring(fe+1,fc);
            if(body.indexOf("secretkey")>=0||body.indexOf("captcha")>=0){
                String act=extractAttr(lower.substring(fi,fe+1),"action");
                if(act!=null)captchaAction=makeAbsolute(HtmlDecoder.decode(act));
                int ii=body.indexOf("type=\"text\"");if(ii<0)ii=body.indexOf("type='text'");
                if(ii>=0){int ts=StringUtils.lastIndexOf(body,"<input",ii);
                    if(ts>=0){int te=body.indexOf(">",ii);
                        if(te>=0){String nm=extractAttr(body.substring(ts,te+1),"name");if(nm!=null)captchaInputName=nm;}}}
                break;
            }
            fp=fc+7;
        }
        if(captchaInputName==null)captchaInputName="secretKey";
        PageItem ci=new PageItem(PageItem.TYPE_CAPTCHA,"[ CAPTCHA - press FIRE to view & enter code ]");
        ci.url=captchaAction;ci.extra=captchaInputName;ci.extra2=captchaImageUrl;items.addElement(ci);
    }

    private String extractSearchFormAction(String html,String lower) {
        int pos=0;
        while(pos<lower.length()){
            int fi=lower.indexOf("<form",pos);if(fi<0)break;
            int fe=lower.indexOf(">",fi);if(fe<0)break;
            int fc=lower.indexOf("</form>",fe);if(fc<0)fc=lower.length();
            String body=lower.substring(fe+1,fc);
            if(body.indexOf("name=\"query\"")>=0||body.indexOf("name='query'")>=0){
                String act=extractAttr(lower.substring(fi,fe+1),"action");
                if(act!=null)return makeAbsolute(HtmlDecoder.decode(act));
            }
            pos=fe+1;
        }
        return null;
    }

    private void extractVideoResults(String html,String lower) {
        int pos=0;
        while(pos<lower.length()){
            int ci=lower.indexOf("class=\"vse\"",pos);if(ci<0)break;
            int as=StringUtils.lastIndexOf(lower,"<a",ci);if(as<0){pos=ci+11;continue;}
            int ae=lower.indexOf(">",as);if(ae<0){pos=ci+11;continue;}
            String href=extractAttr(html.substring(as,ae+1),"href");
            if(href==null){pos=ae+1;continue;}
            href=HtmlDecoder.decode(href);
            int ac=lower.indexOf("</a>",ae);if(ac<0){pos=ae+1;continue;}
            String ih=html.substring(ae+1,ac),il=ih.toLowerCase();
            String title=extractBoldText(ih);
            if(title==null||title.length()==0)title=stripTags(ih).trim();
            title=HtmlDecoder.decode(title);
            if(title.length()==0){pos=ac+4;continue;}
            String dur="";
            int si=il.indexOf("class=\"sm sin\"");if(si<0)si=il.indexOf("class='sm sin'");
            if(si>=0){int so=il.indexOf(">",si),sc=il.indexOf("</span>",si);
                if(so>=0&&sc>so)dur=ih.substring(so+1,sc).trim();}
            PageItem vi=new PageItem(PageItem.TYPE_VIDEO_RESULT,title,makeAbsolute(href));
            vi.extra=dur; vi.videoId=extractVideoId(href);
            items.addElement(vi); pos=ac+4;
        }
    }

    private void extractPagination(String html,String lower) {
        int mi=lower.indexOf("more results");if(mi<0)return;
        int as=StringUtils.lastIndexOf(lower,"<a",mi);if(as<0)return;
        int ae=lower.indexOf(">",as);if(ae<0)return;
        String href=extractAttr(html.substring(as,ae+1),"href");if(href==null)return;
        items.addElement(new PageItem(PageItem.TYPE_PAGINATION,">> More Results >>",makeAbsolute(HtmlDecoder.decode(href))));
    }

    private void extractNavLinks(String html,String lower) {
        int pos=0,count=0;
        while(pos<lower.length()&&count<25){
            int ai=lower.indexOf("<a ",pos);if(ai<0)break;
            int ae=lower.indexOf(">",ai);if(ae<0)break;
            int ac=lower.indexOf("</a>",ae);if(ac<0){pos=ae+1;continue;}
            String href=extractAttr(html.substring(ai,ae+1),"href");
            if(href!=null&&!href.startsWith("javascript")&&!href.startsWith("#")&&href.length()>1){
                href=HtmlDecoder.decode(href);
                String lt=HtmlDecoder.decode(stripTags(html.substring(ae+1,ac)).trim());
                if(lt.length()>0&&lt.length()<80){
                    items.addElement(new PageItem(PageItem.TYPE_LINK,lt,makeAbsolute(href)));count++;
                }
            }
            pos=ac+4;
        }
    }

    private int countByType(int type){
        int c=0;for(int i=0;i<items.size();i++)if(((PageItem)items.elementAt(i)).type==type)c++;return c;
    }
    private String extractBoldText(String html){
        String lo=html.toLowerCase();int bs=lo.indexOf("<b>");if(bs<0)return null;
        int be=lo.indexOf("</b>",bs);if(be<0)return null;return stripTags(html.substring(bs+3,be)).trim();
    }
    String stripTags(String html){
        StringBuffer sb=new StringBuffer();boolean in=false;
        for(int i=0;i<html.length();i++){char c=html.charAt(i);
            if(c=='<')in=true;else if(c=='>')in=false;else if(!in)sb.append(c);}
        return sb.toString();
    }
    public static String stripTagsStatic(String html){
        if(html==null)return "";
        StringBuffer sb=new StringBuffer();boolean in=false;
        for(int i=0;i<html.length();i++){char c=html.charAt(i);
            if(c=='<')in=true;else if(c=='>')in=false;else if(!in)sb.append(c);}
        return sb.toString();
    }
    static String extractAttr(String tag,String attr){
        if(tag==null||attr==null)return null;String lo=tag.toLowerCase();
        String a1=attr.toLowerCase()+"=\"";int i=lo.indexOf(a1);
        if(i>=0){int s=i+a1.length(),e=lo.indexOf("\"",s);if(e>s)return tag.substring(s,e);}
        String a2=attr.toLowerCase()+"='";i=lo.indexOf(a2);
        if(i>=0){int s=i+a2.length(),e=lo.indexOf("'",s);if(e>s)return tag.substring(s,e);}
        String a3=attr.toLowerCase()+"=";i=lo.indexOf(a3);
        if(i>=0){int s=i+a3.length(),e=s;
            while(e<lo.length()&&lo.charAt(e)!=' '&&lo.charAt(e)!='>'&&lo.charAt(e)!='/')e++;
            if(e>s)return tag.substring(s,e);}
        return null;
    }
    static String makeAbsolute(String url){
        if(url==null)return null;url=UrlUtils.stripProxy(url);if(url==null)return null;
        if(url.startsWith("http://")||url.startsWith("https://"))return url;
        if(url.startsWith("/"))return BASE_DOMAIN+url;
        return BASE_DOMAIN+"/"+url;
    }
}

// ======================== Captcha Screen ========================