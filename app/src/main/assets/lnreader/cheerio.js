// A cheerio-shaped facade over the Jsoup handle table exposed as __lnrDom.
//
// Not a reimplementation of cheerio: only the surface plugins were measured to use across the
// published index, which is roughly two dozen methods dominated by text/find/attr/next/map/each.
// A node set is a comma-joined handle string; every method returns a new wrapper so chains work.
(function (global) {
  var dom = global.__lnrDom;

  function Sel(handles) {
    this._h = handles || '';
    this._ids = this._h ? this._h.split(',') : [];
    this.length = this._ids.length;
  }

  // Plugins branch on this to pick text nodes out of .contents(), and the value has to be readable
  // off a plain callback argument, so it is a property rather than a method.
  Object.defineProperty(Sel.prototype, 'nodeType', {
    get: function () { return this._h ? dom.nodeType(this._h) : 0; }
  });

  // Cheerio allows indexed access and plugins rely on it, so mirror handles onto numeric
  // properties as single-node wrappers. `prev` is the selection this one was derived from, which
  // is what .addBack() folds back in.
  // One node always has one handle, so repeats can be dropped by comparing handles directly.
  function dedupe(handles) {
    if (!handles) return '';
    var seen = {}, out = [];
    var parts = handles.split(',');
    for (var i = 0; i < parts.length; i++) {
      if (parts[i] && !seen[parts[i]]) { seen[parts[i]] = true; out.push(parts[i]); }
    }
    return out.join(',');
  }

  function wrap(handles, prev) {
    var s = new Sel(dedupe(handles));
    for (var i = 0; i < s._ids.length; i++) s[i] = new Sel(s._ids[i]);
    s._prev = prev;
    return s;
  }

  Sel.prototype.find = function (sel) { return wrap(dom.select(this._h, sel), this); };
  Sel.prototype.text = function () { return dom.text(this._h); };
  Sel.prototype.ownText = function () { return dom.ownText(this._h); };
  Sel.prototype.html = function () { return dom.html(this._h); };
  Sel.prototype.outerHtml = function () { return dom.outerHtml(this._h); };
  Sel.prototype.tagName = function () { return dom.tagName(this._h); };

  Sel.prototype.attr = function (name, value) {
    if (typeof name === 'undefined') return undefined;
    if (typeof value !== 'undefined') return this;   // parsers never write
    var v = dom.attr(this._h, name);
    return v === null ? undefined : v;
  };

  Sel.prototype.data = function (key) {
    var v = dom.data(this._h, key);
    return v === null ? undefined : v;
  };

  Sel.prototype.removeAttr = function (name) { dom.removeAttr(this._h, String(name)); return this; };
  Sel.prototype.prop = function (name) { return this.attr(name); };
  Sel.prototype.val = function () { return this.attr('value'); };

  Sel.prototype.next = function () { return wrap(dom.next(this._h), this); };
  Sel.prototype.prev = function () { return wrap(dom.prev(this._h), this); };
  Sel.prototype.parent = function () { return wrap(dom.parent(this._h)); };
  Sel.prototype.parents = function () { return wrap(dom.parent(this._h)); };
  Sel.prototype.children = function () { return wrap(dom.children(this._h), this); };
  Sel.prototype.siblings = function () { return wrap(dom.siblings(this._h), this); };
  // Text nodes included, unlike .children() — that difference is the whole point of the method.
  Sel.prototype.contents = function () { return wrap(dom.contents(this._h), this); };
  Sel.prototype.closest = function (sel) { return wrap(dom.closest(this._h, sel)); };

  Sel.prototype.first = function () { return wrap(this._ids.length ? this._ids[0] : ''); };
  Sel.prototype.last = function () {
    return wrap(this._ids.length ? this._ids[this._ids.length - 1] : '');
  };
  Sel.prototype.eq = function (i) {
    var idx = i < 0 ? this._ids.length + i : i;
    return wrap(this._ids[idx] || '');
  };
  Sel.prototype.slice = function (a, b) { return wrap(this._ids.slice(a, b).join(',')); };

  Sel.prototype.each = function (fn) {
    for (var i = 0; i < this._ids.length; i++) {
      var el = wrap(this._ids[i]);
      if (fn.call(el, i, el) === false) break;
    }
    return this;
  };

  // cheerio's .map returns a cheerio object whose .get() yields the raw array; plugins nearly
  // always call .get() or .toArray() straight after, so carry the values on the result.
  Sel.prototype.map = function (fn) {
    var out = [];
    for (var i = 0; i < this._ids.length; i++) {
      var el = wrap(this._ids[i]);
      out.push(fn.call(el, i, el));
    }
    var r = wrap(this._h);
    r.get = function () { return out; };
    r.toArray = function () { return out; };
    return r;
  };

  Sel.prototype.filter = function (pred) {
    var kept = [];
    for (var i = 0; i < this._ids.length; i++) {
      var el = wrap(this._ids[i]);
      var keep = typeof pred === 'function' ? pred.call(el, i, el)
                                            : dom.matches(this._ids[i], pred);
      if (keep) kept.push(this._ids[i]);
    }
    return wrap(kept.join(','));
  };

  Sel.prototype.not = function (sel) {
    var kept = [];
    for (var i = 0; i < this._ids.length; i++) {
      if (!dom.matches(this._ids[i], sel)) kept.push(this._ids[i]);
    }
    return wrap(kept.join(','));
  };

  // Combining selections yields document order, as jQuery guarantees.
  Sel.prototype.add = function (other) {
    var extra = (other && other._h) ? other._h : String(other || '');
    var combined = this._h && extra ? this._h + ',' + extra : (this._h || extra);
    return wrap(dom.documentOrder(combined), this._prev);
  };

  Sel.prototype.is = function (sel) { return dom.matches(this._h, sel); };
  Sel.prototype.hasClass = function (c) { return dom.hasClass(this._h, c); };
  Sel.prototype.addClass = function (c) { dom.addClass(this._h, c); return this; };
  Sel.prototype.removeClass = function (c) { dom.removeClass(this._h, c); return this; };
  Sel.prototype.remove = function () { dom.remove(this._h); return this; };
  Sel.prototype.empty = function () { dom.empty(this._h); return this; };
  Sel.prototype.append = function (h) { dom.append(this._h, String(h)); return this; };
  Sel.prototype.prepend = function (h) { dom.prepend(this._h, String(h)); return this; };
  Sel.prototype.replaceWith = function (h) { dom.replaceWith(this._h, String(h)); return this; };
  // Inserting plain text around nodes is how plugins rebuild paragraph breaks before reading
  // .text(), so these take content verbatim rather than expecting markup.
  Sel.prototype.before = function (h) { dom.before(this._h, String(h)); return this; };
  Sel.prototype.after = function (h) { dom.after(this._h, String(h)); return this; };
  // Folds the selection this one came from back in, e.g. find('*').addBack() to cover the root
  // and everything under it in one pass.
  Sel.prototype.addBack = function () {
    var prev = this._prev;
    if (!prev || !prev._h) return this;
    var combined = this._h ? this._h + ',' + prev._h : prev._h;
    return wrap(dom.documentOrder(combined), prev._prev);
  };
  Sel.prototype.index = function () { return dom.index(this._h); };
  Sel.prototype.end = function () { return this; };

  Sel.prototype.get = function (i) {
    if (typeof i === 'undefined') {
      var all = [];
      for (var k = 0; k < this._ids.length; k++) all.push(wrap(this._ids[k]));
      return all;
    }
    return wrap(this._ids[i] || '');
  };
  Sel.prototype.toArray = function () { return this.get(); };

  function load(html) {
    var root = String(dom.parse(html));

    var query = function (selector, context) {
      if (selector == null) return wrap('');
      if (selector instanceof Sel) return selector;
      var s = String(selector);
      // A markup string builds a fragment rather than querying.
      if (s.charAt(0) === '<') return wrap(String(dom.parseFragment(s)));
      var scope = (context instanceof Sel) ? context._h : root;
      return wrap(dom.select(scope, s));
    };

    query.root = function () { return wrap(root); };
    query.html = function (node) { return node ? node.html() : dom.html(root); };
    query.text = function (node) { return node ? node.text() : dom.text(root); };
    return query;
  }

  global.__cheerio = { load: load, default: { load: load } };
})(globalThis);
