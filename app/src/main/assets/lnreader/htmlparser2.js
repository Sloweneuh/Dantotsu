// The streaming Parser from htmlparser2, over the Jsoup-backed event stream in __lnrDom.
//
// Several plugins pull a single value out of a page with a SAX pass rather than a selector. Only
// the surface those plugins use is here: construct with handlers, write chunks, end. Jsoup does
// the actual parsing, so this replays a finished document rather than tokenising incrementally —
// handlers therefore fire on end(), not during write().
(function (global) {
  var dom = global.__lnrDom;

  function Parser(handlers, options) {
    this.handlers = handlers || {};
    this.options = options || {};
    this.buffer = '';
  }

  Parser.prototype.write = function (chunk) {
    if (chunk != null) this.buffer += String(chunk);
    return this;
  };

  Parser.prototype.end = function (chunk) {
    if (chunk != null) this.buffer += String(chunk);

    var h = this.handlers;
    var lowerCaseTags = this.options.lowerCaseTags !== false;
    var events;
    try {
      events = JSON.parse(dom.parseEvents(this.buffer));
    } catch (e) {
      if (h.onerror) h.onerror(e);
      events = [];
    }

    for (var i = 0; i < events.length; i++) {
      var ev = events[i];
      if (ev.t === 'o') {
        var name = lowerCaseTags ? String(ev.n).toLowerCase() : ev.n;
        if (h.onopentagname) h.onopentagname(name);
        if (h.onattribute) {
          for (var key in ev.a) {
            if (ev.a.hasOwnProperty(key)) h.onattribute(key, ev.a[key]);
          }
        }
        if (h.onopentag) h.onopentag(name, ev.a || {});
      } else if (ev.t === 'x') {
        if (h.ontext) h.ontext(ev.v);
      } else if (ev.t === 'c') {
        if (h.onclosetag) h.onclosetag(lowerCaseTags ? String(ev.n).toLowerCase() : ev.n);
      }
    }

    if (h.onend) h.onend();
    return this;
  };

  Parser.prototype.parseComplete = function (html) {
    this.buffer = '';
    return this.write(html).end();
  };

  Parser.prototype.reset = function () {
    this.buffer = '';
    if (this.handlers.onreset) this.handlers.onreset();
    return this;
  };

  Parser.prototype.pause = function () { return this; };
  Parser.prototype.resume = function () { return this; };

  global.__htmlparser2 = {
    Parser: Parser,
    // Plugins that want a tree already reach for cheerio, so the DOM-building half of
    // htmlparser2 is deliberately routed there rather than reimplemented.
    parseDocument: function (html) { return global.__cheerio.load(html); },
    parseDOM: function (html) { return global.__cheerio.load(html).root(); }
  };
})(globalThis);
