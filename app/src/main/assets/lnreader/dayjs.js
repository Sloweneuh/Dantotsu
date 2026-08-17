// A small real dayjs rather than a stub.
//
// Plugins format chapter release dates with it, and a fake that echoed its input produced
// unparseable dates downstream. Covers the tokens actually seen in the published plugins
// (YYYY/MM/DD/HH/mm/ss) plus valueOf/add/subtract, and tolerates the relative-time plugin being
// extended onto it without implementing it.
(function (global) {
  function pad(n) { return n < 10 ? '0' + n : String(n); }

  function D(input) {
    if (!(this instanceof D)) return new D(input);
    this.d = (input == null) ? new Date()
           : (input instanceof Date ? input : new Date(input));
    if (isNaN(this.d.getTime())) this.d = new Date();
  }

  D.prototype.format = function (fmt) {
    var d = this.d;
    if (!fmt) return d.toISOString();
    return String(fmt)
      .replace(/YYYY/g, d.getFullYear())
      .replace(/MM/g, pad(d.getMonth() + 1))
      .replace(/DD/g, pad(d.getDate()))
      .replace(/HH/g, pad(d.getHours()))
      .replace(/mm/g, pad(d.getMinutes()))
      .replace(/ss/g, pad(d.getSeconds()));
  };

  D.prototype.valueOf = function () { return this.d.getTime(); };
  D.prototype.toDate = function () { return this.d; };
  D.prototype.toISOString = function () { return this.d.toISOString(); };
  D.prototype.isValid = function () { return !isNaN(this.d.getTime()); };

  var UNIT_MS = { second: 1000, minute: 60000, hour: 3600000, day: 86400000, week: 604800000 };

  D.prototype.subtract = function (n, unit) {
    var key = String(unit || '').toLowerCase().replace(/s$/, '');
    var ms = UNIT_MS[key] || 0;
    return new D(new Date(this.d.getTime() - n * ms));
  };
  D.prototype.add = function (n, unit) { return this.subtract(-n, unit); };

  var dayjs = function (input) { return new D(input); };
  dayjs.extend = function () { return dayjs; };
  dayjs.unix = function (s) { return new D(s * 1000); };

  global.__dayjs = dayjs;
})(globalThis);
