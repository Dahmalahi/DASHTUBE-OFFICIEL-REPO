// Cloudflare Worker proxy for 2yxa.mobi – J2ME compatible
// - Resolves routing parameters and fallback routes cleanly
// - Streams raw binary image assets (zkod.php) directly without breaking
// - Rewrites image targets and form actions for valid proxy navigation
// - Handles POST payload bodies seamlessly for captcha processing
// - Injects REFRESH button on conversion pages

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    let targetUrl = url.searchParams.get('url');

    // If there's no ?url= parameter, check if we are intercepting a relative path
    if (!targetUrl) {
      const path = url.pathname === '/' ? '/mov.php' : url.pathname;
      targetUrl = 'https://video.2yxa.mobi' + path + url.search;
    }

    // Decode the URL
    let decoded;
    try {
      decoded = decodeURIComponent(targetUrl);
    } catch (e) {
      decoded = targetUrl;
    }

    // Build the absolute fetch URL target
    let fetchUrl;
    if (decoded.startsWith('http://') || decoded.startsWith('https://')) {
      fetchUrl = decoded;
    } else {
      fetchUrl = 'https://video.2yxa.mobi' + (decoded.startsWith('/') ? decoded : '/' + decoded);
    }

    // Prepare header configurations to completely emulate a real web browser
    const headers = new Headers();
    headers.set('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
    headers.set('Accept', 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8');
    headers.set('Accept-Language', 'en-US,en;q=0.5');
    headers.set('Referer', 'https://video.2yxa.mobi/');
    headers.set('Connection', 'keep-alive');
    headers.set('Upgrade-Insecure-Requests', '1');

    // Forward inbound session cookies securely
    const cookie = request.headers.get('Cookie');
    if (cookie) {
      headers.set('Cookie', cookie);
    }

    // Read the request method
    const method = request.method;
    let body = undefined;

    // CRITICAL: Ensure full payload transmission for form submissions
    if (method === 'POST') {
      const contentType = request.headers.get('content-type') || '';
      if (contentType) {
        headers.set('Content-Type', contentType);
      }
      // Clone the raw stream body for payload submission validation
      body = await request.clone().arrayBuffer();
    }

    // Perform the actual fetch against 2yxa backend nodes
    let response;
    try {
      response = await fetch(fetchUrl, {
        method: method,
        headers: headers,
        body: body
      });
    } catch (err) {
      return new Response(`Proxy error: ${err.message}`, { status: 502 });
    }

    // Handle Image asset streaming directly to stop data corruption strings
    const respContentType = response.headers.get('Content-Type') || '';
    if (respContentType.includes('image/') || fetchUrl.includes('zkod.php')) {
      const imgHeaders = new Headers();
      response.headers.forEach((value, key) => {
        const lower = key.toLowerCase();
        if (lower === 'set-cookie' || lower === 'cache-control' || lower === 'content-type') {
          imgHeaders.append(key, value);
        }
      });
      if (!imgHeaders.has('Content-Type')) {
        imgHeaders.set('Content-Type', 'image/jpeg');
      }
      return new Response(response.body, {
        status: response.status,
        headers: imgHeaders
      });
    }

    // Read response body as clean HTML text layout
    let html = await response.text();

    // Map output headers back to the application container framework
    const responseHeaders = new Headers();
    response.headers.forEach((value, key) => {
      const lower = key.toLowerCase();
      if (lower === 'set-cookie') {
        responseHeaders.append('Set-Cookie', value);
      } else if (lower === 'content-type') {
        responseHeaders.set('Content-Type', value);
      }
    });
    if (!responseHeaders.has('Content-Type')) {
      responseHeaders.set('Content-Type', 'text/html; charset=utf-8');
    }

    const proxyBase = `${url.protocol}//${url.host}`;

    // --- 1. Image URL rewriting parser ---
    html = html.replace(/<img\s+([^>]*?\s+)?src="\/([^"]+)"/gi, (match, attributes, path) => {
      attributes = attributes || '';
      let absoluteTarget = `https://video.2yxa.mobi/${path}`;
      
      if ((path.startsWith('zkod.php') || html.includes('код с картинки') || html.includes('code de l\'image')) && url.search) {
        const currentQuery = url.search.substring(1);
        if (!absoluteTarget.includes(currentQuery)) {
          const separator = absoluteTarget.includes('?') ? '&' : '?';
          absoluteTarget += separator + currentQuery;
        }
      }

      const encoded = encodeURIComponent(absoluteTarget);
      return `<img ${attributes}src="${proxyBase}/?url=${encoded}"`;
    });

    // --- 2. ADVANCED FIX: Robust Form Action Translation ---
    // This regular expression captures forms regardless of attribute positions or capitalization
    html = html.replace(/<form\s+([^>]*?\s+)?action="\/([^"]+)"([^>]*)>/gi, (match, beforeAttr, path, afterAttr) => {
      beforeAttr = beforeAttr || '';
      afterAttr = afterAttr || '';
      
      let absoluteTarget = `https://video.2yxa.mobi/${path}`;
      
      // Inject the current tracking session token query directly into the submission path 
      // if it isn't already present, securing a direct lane to the conversion step
      if (url.search) {
        const currentQuery = url.search.substring(1);
        if (!absoluteTarget.includes(currentQuery)) {
          const separator = absoluteTarget.includes('?') ? '&' : '?';
          absoluteTarget += separator + currentQuery;
        }
      }

      const encoded = encodeURIComponent(absoluteTarget);
      return `<form ${beforeAttr}action="${proxyBase}/?url=${encoded}"${afterAttr}>`;
    });

    // --- 3. Inject manual refresh triggers for active conversions ---
    const isCaptcha = html.includes('zkod.php') || html.includes('секретный код') || html.includes('entrez le code') || html.includes('код с картинки');
    
    if (!isCaptcha) {
      if (html.includes('class="vse"') && html.includes('обновить')) {
        const match = html.match(/<a\s+href="([^"]+)"[^>]*>.*?обновить.*?<\/a>/i);
        if (match && match[1]) {
          const refreshUrl = match[1];
          const button = `<br/><form action="${refreshUrl}" method="get"><input type="submit" value="REFRESH" /></form><br/>`;
          html = html.replace('</body>', button + '</body>');
        }
      }
    }

    return new Response(html, { headers: responseHeaders });
  }
};