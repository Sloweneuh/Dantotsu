// The @libs/* modules an LNReader plugin bundle imports, plus fetchApi over the host bridge.
//
// The host call is synchronous (OkHttp blocking on a worker thread), so every promise here is
// already settled by the time it is returned — but they are real promises, because the bundles
// await them and this engine drains microtasks properly.
(function (global) {
  var bridge = global.__lnrHttp;

  function makeResponse(raw) {
    var parsed = JSON.parse(raw);
    return {
      ok: parsed.status >= 200 && parsed.status < 300,
      status: parsed.status,
      statusText: parsed.statusText,
      url: parsed.url,
      headers: {
        get: function (k) { return parsed.headers[String(k).toLowerCase()] || null; }
      },
      text: function () { return Promise.resolve(parsed.body); },
      json: function () { return Promise.resolve(JSON.parse(parsed.body)); }
    };
  }

  // A request body arrives as a plain string, a FormData, or a URLSearchParams. The last two
  // cannot be stringified and posted as-is — a multipart body has to be assembled with a boundary,
  // and urlencoded pairs have to be escaped — so the kind is passed alongside and the host builds
  // the real body. Returns [kind, payload].
  function encodeBody(body) {
    if (body == null) return ['none', null];
    if (typeof FormData !== 'undefined' && body instanceof FormData) {
      return ['form-data', JSON.stringify(body.entries())];
    }
    if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
      return ['urlencoded', body.toString()];
    }
    return ['raw', String(body)];
  }

  function fetchApi(url, init) {
    init = init || {};
    var headers = init.headers || {};
    // Header bags arrive as plain objects or Headers-likes; normalise to a plain object.
    if (typeof headers.forEach === 'function' && !Array.isArray(headers)) {
      var flat = {};
      headers.forEach(function (v, k) { flat[k] = v; });
      headers = flat;
    }
    var encoded = encodeBody(init.body);
    var raw = bridge.request(
      String(url),
      String(init.method || 'GET'),
      JSON.stringify(headers),
      encoded[1],
      encoded[0]
    );
    return Promise.resolve(makeResponse(raw));
  }

  function fetchText(url, init) {
    return fetchApi(url, init).then(function (r) { return r.text(); });
  }

  // Backed by the app's preference store and namespaced per plugin, so values survive a restart
  // and one plugin cannot read another's. Values are stringified going in and revived coming out,
  // because plugins store objects here as readily as strings.
  function makeStore() {
    var bridge = global.__lnrStorage;
    return {
      get: function (k) {
        var raw = bridge.get(String(k));
        if (raw === null || raw === undefined) return undefined;
        try { return JSON.parse(raw); } catch (e) { return raw; }
      },
      set: function (k, v) { bridge.set(String(k), JSON.stringify(v)); },
      delete: function (k) { bridge.delete(String(k)); },
      clearAll: function () { bridge.clear(); }
    };
  }

  var NOVEL_STATUS = {
    Unknown: 'Unknown',
    Ongoing: 'Ongoing',
    Completed: 'Completed',
    Licensed: 'Licensed',
    PublishingFinished: 'Publishing Finished',
    Cancelled: 'Cancelled',
    OnHiatus: 'On Hiatus',
    STUB: 'STUB',
    Inactive: 'Inactive'
  };

  var DEFAULT_COVER =
    'https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true';

  // AES-GCM, matching the @noble/ciphers shape the upstream lib re-exports: gcm(key, nonce)
  // returns an object with encrypt/decrypt over Uint8Array. The cipher itself is the platform's.
  function gcm(key, nonce, aad) {
    var crypto = global.__lnrCrypto;
    function toB64(bytes) {
      var s = '';
      var view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || []);
      for (var i = 0; i < view.length; i++) s += String.fromCharCode(view[i]);
      return global.btoa(s);
    }
    function fromB64(b64) {
      var s = global.atob(b64);
      var out = new Uint8Array(s.length);
      for (var i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
      return out;
    }
    return {
      encrypt: function (plaintext) {
        return fromB64(crypto.gcm(
          'encrypt', toB64(key), toB64(nonce), toB64(plaintext), aad ? toB64(aad) : null
        ));
      },
      decrypt: function (ciphertext) {
        return fromB64(crypto.gcm(
          'decrypt', toB64(key), toB64(nonce), toB64(ciphertext), aad ? toB64(aad) : null
        ));
      }
    };
  }

  global.__libs = {
    '@libs/fetch': { fetchApi: fetchApi, fetchText: fetchText, fetchFile: fetchText },
    '@libs/novelStatus': { NovelStatus: NOVEL_STATUS },
    '@libs/defaultCover': { defaultCover: DEFAULT_COVER },
    '@libs/aes': { gcm: gcm },
    // Same constants under the path a couple of plugins import them from directly.
    '@/types/constants': { NovelStatus: NOVEL_STATUS, defaultCover: DEFAULT_COVER },
    '@libs/isAbsoluteUrl': {
      isUrlAbsolute: function (u) { return /^https?:\/\//i.test(String(u)); }
    },
    '@libs/filterInputs': {
      FilterTypes: {
        TextInput: 'Text',
        Picker: 'Picker',
        CheckboxGroup: 'Checkbox',
        Switch: 'Switch',
        ExcludableCheckboxGroup: 'XCheckbox'
      }
    },
    // Per-plugin key/value the host owns. In-memory here; the real implementation should back
    // this with the app's preference store, keyed by plugin id.
    '@libs/storage': {
      storage: makeStore(),
      localStorage: makeStore(),
      sessionStorage: makeStore()
    }
  };
})(globalThis);
