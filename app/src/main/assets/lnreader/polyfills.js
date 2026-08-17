// Web globals QuickJS does not provide but plugins assume.
//
// Kept to what the published plugins were observed to reach for; this is not an attempt at a
// browser environment.
(function (global) {
  var bridge = global.__lnrUrl;

  // --- URL / URLSearchParams -----------------------------------------------------------------
  // Plugins mostly use `new URL(scrapedPath, plugin.site)` to absolutise what they scraped.
  // Resolution itself is done by the host, which has a correct implementation already.

  function URLSearchParams(init) {
    this._pairs = [];
    if (typeof init === 'string' && init.length) {
      var q = init.charAt(0) === '?' ? init.slice(1) : init;
      var parts = q.split('&');
      for (var i = 0; i < parts.length; i++) {
        if (!parts[i]) continue;
        var eq = parts[i].indexOf('=');
        var k = eq === -1 ? parts[i] : parts[i].slice(0, eq);
        var v = eq === -1 ? '' : parts[i].slice(eq + 1);
        this._pairs.push([decodeURIComponent(k), decodeURIComponent(v.replace(/\+/g, ' '))]);
      }
    } else if (init && typeof init === 'object') {
      for (var key in init) {
        if (init.hasOwnProperty(key)) this._pairs.push([key, String(init[key])]);
      }
    }
  }
  URLSearchParams.prototype.get = function (k) {
    for (var i = 0; i < this._pairs.length; i++) if (this._pairs[i][0] === k) return this._pairs[i][1];
    return null;
  };
  URLSearchParams.prototype.getAll = function (k) {
    var out = [];
    for (var i = 0; i < this._pairs.length; i++) if (this._pairs[i][0] === k) out.push(this._pairs[i][1]);
    return out;
  };
  URLSearchParams.prototype.has = function (k) { return this.get(k) !== null; };
  URLSearchParams.prototype.set = function (k, v) {
    for (var i = 0; i < this._pairs.length; i++) {
      if (this._pairs[i][0] === k) { this._pairs[i][1] = String(v); return; }
    }
    this._pairs.push([k, String(v)]);
  };
  URLSearchParams.prototype.append = function (k, v) { this._pairs.push([k, String(v)]); };
  URLSearchParams.prototype.delete = function (k) {
    this._pairs = this._pairs.filter(function (p) { return p[0] !== k; });
  };
  URLSearchParams.prototype.forEach = function (fn) {
    for (var i = 0; i < this._pairs.length; i++) fn(this._pairs[i][1], this._pairs[i][0], this);
  };
  URLSearchParams.prototype.toString = function () {
    return this._pairs.map(function (p) {
      return encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1]);
    }).join('&');
  };

  function URL(input, base) {
    var parsed = JSON.parse(bridge.parse(String(input), base == null ? null : String(base)));
    if (parsed.error) throw new TypeError('Invalid URL: ' + input + ' (' + parsed.error + ')');
    this.href = parsed.href;
    this.protocol = parsed.protocol;
    this.hostname = parsed.hostname;
    this.port = parsed.port;
    this.host = parsed.host;
    this.pathname = parsed.pathname;
    this.search = parsed.search;
    this.hash = parsed.hash;
    this.origin = parsed.origin;
    this.searchParams = new URLSearchParams(parsed.search);
  }
  URL.prototype.toString = function () { return this.href; };

  if (typeof global.URL === 'undefined') global.URL = URL;
  if (typeof global.URLSearchParams === 'undefined') global.URLSearchParams = URLSearchParams;

  // --- FormData -------------------------------------------------------------------------------
  // The most-used missing global by a wide margin: 89 of the 266 published plugins construct one,
  // almost always to POST a search. fetchApi below detects it and hands the pairs to the host,
  // which builds a real multipart body.

  function FormData() { this._pairs = []; }
  FormData.prototype.append = function (k, v) { this._pairs.push([String(k), String(v)]); };
  FormData.prototype.set = function (k, v) {
    this.delete(k);
    this.append(k, v);
  };
  FormData.prototype.get = function (k) {
    for (var i = 0; i < this._pairs.length; i++) if (this._pairs[i][0] === k) return this._pairs[i][1];
    return null;
  };
  FormData.prototype.getAll = function (k) {
    return this._pairs.filter(function (p) { return p[0] === k; }).map(function (p) { return p[1]; });
  };
  FormData.prototype.has = function (k) { return this.get(k) !== null; };
  FormData.prototype.delete = function (k) {
    this._pairs = this._pairs.filter(function (p) { return p[0] !== k; });
  };
  FormData.prototype.forEach = function (fn) {
    for (var i = 0; i < this._pairs.length; i++) fn(this._pairs[i][1], this._pairs[i][0], this);
  };
  FormData.prototype.entries = function () { return this._pairs.slice(); };
  FormData.prototype.keys = function () {
    return this._pairs.map(function (p) { return p[0]; });
  };
  FormData.prototype.values = function () {
    return this._pairs.map(function (p) { return p[1]; });
  };
  if (typeof global.FormData === 'undefined') global.FormData = FormData;

  // --- Headers --------------------------------------------------------------------------------
  // Header names are case-insensitive, which is the only reason a plain object is not enough.

  function Headers(init) {
    this._map = {};
    if (init instanceof Headers) {
      var self = this;
      init.forEach(function (v, k) { self.append(k, v); });
    } else if (init && typeof init === 'object') {
      for (var k in init) if (init.hasOwnProperty(k)) this.append(k, init[k]);
    }
  }
  Headers.prototype.append = function (k, v) {
    var key = String(k).toLowerCase();
    this._map[key] = this._map.hasOwnProperty(key) ? this._map[key] + ', ' + String(v) : String(v);
  };
  Headers.prototype.set = function (k, v) { this._map[String(k).toLowerCase()] = String(v); };
  Headers.prototype.get = function (k) {
    var key = String(k).toLowerCase();
    return this._map.hasOwnProperty(key) ? this._map[key] : null;
  };
  Headers.prototype.has = function (k) { return this.get(k) !== null; };
  Headers.prototype.delete = function (k) { delete this._map[String(k).toLowerCase()]; };
  Headers.prototype.forEach = function (fn) {
    for (var k in this._map) if (this._map.hasOwnProperty(k)) fn(this._map[k], k, this);
  };
  Headers.prototype.keys = function () { return Object.keys(this._map); };
  if (typeof global.Headers === 'undefined') global.Headers = Headers;

  // --- TextEncoder / TextDecoder ----------------------------------------------------------------
  // UTF-8 only, which is all the plugins that use them ask for.

  if (typeof global.TextEncoder === 'undefined') {
    global.TextEncoder = function TextEncoder() {
      this.encoding = 'utf-8';
      this.encode = function (str) {
        var s = unescape(encodeURIComponent(String(str == null ? '' : str)));
        var bytes = new Uint8Array(s.length);
        for (var i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i);
        return bytes;
      };
    };
    global.TextDecoder = function TextDecoder() {
      this.encoding = 'utf-8';
      this.decode = function (bytes) {
        if (!bytes) return '';
        var s = '';
        var view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
        for (var i = 0; i < view.length; i++) s += String.fromCharCode(view[i]);
        return decodeURIComponent(escape(s));
      };
    };
  }

  // --- timers ---------------------------------------------------------------------------------
  // No event loop to schedule on. Zero-delay callbacks are run inline so a plugin that defers a
  // step still completes; a real delay cannot be honoured, and pretending otherwise by blocking
  // the JS thread would be worse than running early.
  if (typeof global.setTimeout === 'undefined') {
    global.setTimeout = function (fn) {
      if (typeof fn === 'function') fn();
      return 0;
    };
    global.clearTimeout = function () {};
    global.setInterval = function () { return 0; };
    global.clearInterval = function () {};
  }

  // --- base64 ---------------------------------------------------------------------------------
  if (typeof global.btoa === 'undefined') {
    var CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    global.btoa = function (input) {
      var str = String(input), out = '';
      for (var block = 0, charCode, i = 0, map = CHARS;
           str.charAt(i | 0) || (map = '=', i % 1);
           out += map.charAt(63 & block >> 8 - i % 1 * 8)) {
        charCode = str.charCodeAt(i += 3 / 4);
        if (charCode > 0xFF) throw new Error('btoa: character out of range');
        block = block << 8 | charCode;
      }
      return out;
    };
    global.atob = function (input) {
      var str = String(input).replace(/=+$/, ''), out = '';
      for (var bc = 0, bs = 0, buffer, i = 0;
           (buffer = str.charAt(i++));
           ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4)
             ? out += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
        buffer = CHARS.indexOf(buffer);
      }
      return out;
    };
  }
})(globalThis);
