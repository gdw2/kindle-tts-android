(function () {
  if (window.__kindleTtsInstalled) return;
  window.__kindleTtsInstalled = true;

  var frameId = 'f' + Math.random().toString(36).slice(2) + Date.now().toString(36);
  var lastExtractedText = '';
  var lastStrategy = 'none';
  var busy = false;

  var skipSelectors = [
    '#content-overlays',
    '#kindle-library-header',
    'nav',
    'header',
    'footer',
    '[role="banner"]',
    '[role="navigation"]',
    'script',
    'style',
    'noscript',
    'template',
    'svg',
    'button',
    'input',
    'select',
    'textarea',
    'canvas',
    '[aria-hidden="true"]'
  ];

  var turnSelectors = [
    '#kr-next',
    '.kr-nav-right',
    '[data-testid="next-page"]',
    '[aria-label="Next page"]',
    '[aria-label="Next Page"]',
    'button[title*="Next" i]',
    '[id*="next-page"]',
    '.next-page'
  ];

  var excludedUrls = [/kindle-library/i, /\/landing/i, /signin/i, /\/ap\//i, /\/hz\//i];

  var chromeLine = /^(page|next|previous|next page|previous page|kindle|library|table of contents|go to|search|settings|close|back|menu|more)$/i;

  function post(obj) {
    try {
      KindleBridge.postMessage(JSON.stringify(obj));
    } catch (e) {
      /* bridge not available on this page */
    }
  }

  function visibleLength() {
    try {
      return document.body && document.body.innerText ? document.body.innerText.length : 0;
    } catch (e) {
      return 0;
    }
  }

  function frameDepth() {
    var depth = 0;
    try {
      var w = window;
      while (w.parent && w.parent !== w) {
        depth++;
        w = w.parent;
        if (depth > 8) break;
      }
    } catch (e) {
      /* cross-origin parent */
    }
    return depth;
  }

  function isExcluded() {
    return excludedUrls.some(function (re) { return re.test(location.href); });
  }

  function postReady() {
    post({
      type: 'ready',
      frameId: frameId,
      url: location.href,
      title: document.title || '',
      textLength: visibleLength(),
      pageImages: pageImageElements().length,
      depth: frameDepth(),
      top: window === window.top
    });
    if (location.pathname.indexOf('kindle-library') !== -1) {
      var lib = document.getElementById('library');
      if (lib && lib.getBoundingClientRect().height < 100) {
        fixKindleLibrary();
      }
    }
  }

  function pageImageElements() {
    var imgs = Array.prototype.slice.call(document.querySelectorAll('.kg-full-page-img img'));
    if (!imgs.length) {
      imgs = Array.prototype.slice.call(document.images).filter(function (img) {
        return img.src && img.src.indexOf('blob:') === 0;
      });
    }
    return imgs;
  }

  function sortedPageImages() {
    return pageImageElements()
      .map(function (img) {
        var r = img.getBoundingClientRect();
        return { img: img, y: r.top, h: r.height, w: r.width };
      })
      .sort(function (a, b) { return a.y - b.y; });
  }

  function handleScrollNext(requestId) {
    try {
      var items = sortedPageImages().filter(function (item) { return item.h > 200; });
      if (!items.length) {
        post({ type: 'scrolled', requestId: requestId, frameId: frameId, error: 'no page images' });
        return;
      }
      var current = null;
      for (var i = 0; i < items.length; i++) {
        if (items[i].y <= 0 && items[i].y + items[i].h > 0) current = items[i];
      }
      if (!current) {
        for (var j = 0; j < items.length; j++) {
          if (items[j].y >= 0) { current = items[j]; break; }
        }
      }
      if (!current) current = items[items.length - 1];
      // Advance exactly one visible page image. Using the next loaded image's
      // offset skips a screen when that image has not loaded yet.
      var step = Math.max(200, current.h || window.innerHeight);
      var before = current.img.src;
      window.scrollBy(0, step);
      setTimeout(function () {
        post({
          type: 'scrolled',
          requestId: requestId,
          frameId: frameId,
          step: step,
          before: before
        });
      }, 500);
    } catch (e) {
      post({ type: 'scrolled', requestId: requestId, frameId: frameId, error: String(e) });
    }
  }

  function handlePageInfo(requestId) {
    try {
      var images = sortedPageImages().map(function (item, index) {
        return {
          index: index,
          y: item.y,
          height: item.h,
          width: item.w,
          naturalWidth: item.img.naturalWidth,
          naturalHeight: item.img.naturalHeight,
          src: item.img.src
        };
      });
      var bodyText = (document.body && document.body.innerText) || '';
      var label = (bodyText.match(/Page\s+\d+\s+of\s+\d+/) || [null])[0];
      console.log('KTT pageInfo images=' + images.length + ' label=' + label);
      post({
        type: 'pageInfo',
        requestId: requestId,
        frameId: frameId,
        url: location.href,
        label: label,
        images: images
      });
    } catch (e) {
      console.error('KTT pageInfo error: ' + e);
      post({ type: 'pageInfo', requestId: requestId, frameId: frameId, error: String(e), images: [] });
    }
  }

  function handlePageImage(requestId, index, src) {
    try {
      var items = sortedPageImages();
      var item = null;
      if (src) {
        for (var i = 0; i < items.length; i++) {
          if (items[i].img.src === src) { item = items[i]; break; }
        }
      }
      if (!item) item = items[index];
      if (!item) {
        post({ type: 'pageImage', requestId: requestId, error: 'no page image at index ' + index });
        return;
      }
      var img = item.img;
      function send() {
        try {
          var canvas = document.createElement('canvas');
          canvas.width = img.naturalWidth;
          canvas.height = img.naturalHeight;
          var ctx = canvas.getContext('2d', { willReadFrequently: true });
          ctx.drawImage(img, 0, 0);
          var data = canvas.toDataURL('image/jpeg', 0.8).split(',')[1];
          post({
            type: 'pageImage',
            requestId: requestId,
            frameId: frameId,
            index: index,
            src: img.src,
            width: img.naturalWidth,
            height: img.naturalHeight,
            data: data
          });
        } catch (e) {
          console.error('KTT pageImage error: ' + e);
          post({ type: 'pageImage', requestId: requestId, error: String(e) });
        }
      }
      if (img.complete && img.naturalWidth > 0) {
        send();
      } else {
        img.addEventListener('load', send, { once: true });
        setTimeout(send, 2000);
      }
    } catch (e) {
      console.error('KTT pageImage outer error: ' + e);
      post({ type: 'pageImage', requestId: requestId, error: String(e) });
    }
  }

  /**
   * Kindle's library relies on percentage heights, but WebView reports vh as 0
   * in some Android builds, collapsing the grid to zero height.
   */
  function fixKindleLibrary() {
    if (location.pathname.indexOf('kindle-library') === -1) return;
    var h = window.innerHeight;
    document.documentElement.style.setProperty('height', h + 'px', 'important');
    document.body.style.setProperty('height', h + 'px', 'important');
    var lib = document.getElementById('library');
    if (!lib) return;
    var top = lib.getBoundingClientRect().top || 48;
    var el = lib;
    while (el && el !== document.body) {
      el.style.setProperty('height', (el === lib ? Math.max(200, h - top) : h) + 'px', 'important');
      el.style.setProperty('min-height', '0', 'important');
      el.style.setProperty('overflow', 'visible', 'important');
      el = el.parentElement;
    }
    lib.style.setProperty('height', Math.max(200, h - top) + 'px', 'important');
    lib.style.setProperty('overflow-y', 'auto', 'important');
  }

  function hideChrome() {
    var hidden = [];
    skipSelectors.forEach(function (selector) {
      var nodes;
      try { nodes = document.querySelectorAll(selector); } catch (e) { nodes = []; }
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el === document.body) continue;
        hidden.push([el, el.style.display]);
        el.style.display = 'none';
      }
    });
    return hidden;
  }

  function restoreChrome(hidden) {
    for (var i = 0; i < hidden.length; i++) {
      hidden[i][0].style.display = hidden[i][1];
    }
  }

  function cleanText(raw) {
    var lines = raw.replace(/\r/g, '').split('\n');
    var out = [];
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].replace(/[ \t\u00a0]+/g, ' ').trim();
      if (!line) continue;
      if (chromeLine.test(line)) continue;
      if (/^\d{1,4}$/.test(line)) continue;
      if (out.length && out[out.length - 1] === line) continue;
      out.push(line);
    }
    return out.join('\n\n');
  }

  function extractText() {
    if (!document.body || isExcluded()) return '';
    var hidden = hideChrome();
    var raw = '';
    try {
      raw = document.body.innerText || document.body.textContent || '';
    } finally {
      restoreChrome(hidden);
    }
    var text = cleanText(raw);
    lastStrategy = 'innerText';

    // Kindle has historically rendered words as absolutely positioned spans (class k4w).
    // If innerText collapsed the spacing, rebuild from those spans.
    var spaces = (text.match(/ /g) || []).length;
    if (text.length > 200 && spaces < text.length / 80) {
      var spans = document.querySelectorAll('span.k4w');
      if (spans.length > 10) {
        var parts = [];
        for (var i = 0; i < spans.length; i++) {
          var part = (spans[i].innerText || spans[i].textContent || '').trim();
          if (part) parts.push(part);
        }
        var alternate = cleanText(parts.join(' '));
        if (alternate.length > text.length * 0.5) {
          text = alternate;
          lastStrategy = 'word-spans';
        }
      }
    }
    return text;
  }

  function clickFirst(selectors) {
    for (var s = 0; s < selectors.length; s++) {
      var nodes;
      try { nodes = document.querySelectorAll(selectors[s]); } catch (e) { nodes = []; }
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var rect = el.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) continue;
        if (el.disabled) continue;
        try {
          el.click();
          return selectors[s];
        } catch (e) {
          /* try the next candidate */
        }
      }
    }
    return null;
  }

  function pressArrowRight() {
    var keys = ['ArrowRight', 'PageDown', ' '];
    for (var i = 0; i < keys.length; i++) {
      try {
        var down = new KeyboardEvent('keydown', { key: keys[i], code: keys[i], bubbles: true, cancelable: true });
        document.dispatchEvent(down);
        window.dispatchEvent(down);
        var up = new KeyboardEvent('keyup', { key: keys[i], code: keys[i], bubbles: true, cancelable: true });
        document.dispatchEvent(up);
        window.dispatchEvent(up);
      } catch (e) {
        /* keep trying the remaining keys */
      }
    }
    return 'arrow-key';
  }

  function clickRightMargin() {
    try {
      var x = Math.max(1, window.innerWidth - 12);
      var y = Math.floor(window.innerHeight / 2);
      var el = document.elementFromPoint(x, y);
      if (el) {
        el.click();
        return 'margin-click';
      }
    } catch (e) {
      /* ignore */
    }
    return null;
  }

  function finishTurn(requestId, strategy) {
    var text = extractText();
    var changed = text.length > 0 && text !== lastExtractedText;
    lastExtractedText = text;
    post({
      type: 'turned',
      requestId: requestId,
      frameId: frameId,
      url: location.href,
      strategy: strategy,
      changed: changed,
      text: text
    });
  }

  function turnPage(requestId) {
    if (busy) {
      post({ type: 'turned', requestId: requestId, frameId: frameId, changed: false, strategy: 'busy', text: lastExtractedText });
      return;
    }
    busy = true;
    var strategy = clickFirst(turnSelectors);
    if (!strategy) strategy = pressArrowRight();

    setTimeout(function () {
      var text = extractText();
      if (text !== lastExtractedText) {
        busy = false;
        finishTurn(requestId, strategy);
        return;
      }
      var fallback = clickRightMargin();
      setTimeout(function () {
        busy = false;
        finishTurn(requestId, fallback ? strategy + '+' + fallback : strategy);
      }, 900);
    }, 900);
  }

  function handleExtract(requestId) {
    var text = extractText();
    lastExtractedText = text;
    post({
      type: 'extracted',
      requestId: requestId,
      frameId: frameId,
      url: location.href,
      strategy: lastStrategy,
      text: text
    });
  }

  function poll() {
    var raw;
    try {
      raw = KindleBridge.poll(frameId);
    } catch (e) {
      return;
    }
    if (!raw) return;
    console.log('KTT command ' + raw.slice(0, 120));
    var command;
    try {
      command = JSON.parse(raw);
    } catch (e) {
      return;
    }
    try {
      if (command.type === 'extract') {
        handleExtract(command.requestId);
      } else if (command.type === 'turn') {
        turnPage(command.requestId);
      } else if (command.type === 'probe') {
        postReady();
      } else if (command.type === 'pageInfo') {
        handlePageInfo(command.requestId);
      } else if (command.type === 'pageImage') {
        handlePageImage(command.requestId, command.index, command.src);
      } else if (command.type === 'scrollNext') {
        handleScrollNext(command.requestId);
      }
    } catch (e) {
      console.error('KTT command error: ' + e);
      post({ type: 'error', requestId: command.requestId, frameId: frameId, error: String(e) });
    }
  }

  function start() {
    postReady();
    setInterval(postReady, 2500);
    setInterval(poll, 120);
    if (location.pathname.indexOf('kindle-library') !== -1) {
      setTimeout(fixKindleLibrary, 300);
      setTimeout(fixKindleLibrary, 1500);
      setTimeout(fixKindleLibrary, 4000);
      window.addEventListener('resize', fixKindleLibrary);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
  window.addEventListener('load', postReady);
  window.__kttDebug = {
    frameId: frameId,
    pageInfo: handlePageInfo,
    images: pageImageElements
  };
})();
