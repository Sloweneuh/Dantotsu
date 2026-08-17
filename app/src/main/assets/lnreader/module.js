// CommonJS host: resolves the module ids plugin bundles require, then evaluates a bundle in its
// own module scope and hands back its default export.
(function (global) {
  var registry = {};
  var libs = global.__libs || {};
  for (var k in libs) if (libs.hasOwnProperty(k)) registry[k] = libs[k];

  registry['cheerio'] = global.__cheerio;
  registry['dayjs'] = global.__dayjs;
  registry['htmlparser2'] = global.__htmlparser2;

  global.__require = function (id) {
    if (registry.hasOwnProperty(id)) return registry[id];
    throw new Error('Module not provided by host: ' + id);
  };

  global.__loadPlugin = function (source) {
    var module = { exports: {} };
    var fn = new Function('module', 'exports', 'require', 'globalThis', source);
    fn(module, module.exports, global.__require, global);
    var ex = module.exports;
    return (ex && ex.default) ? ex.default : ex;
  };
})(globalThis);
