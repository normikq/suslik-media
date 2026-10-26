(function () {
  "use strict";

  var strings = {};

  function getNested(obj, path) {
    if (!obj || !path) return undefined;
    var parts = path.split(".");
    var current = obj;
    for (var i = 0; i < parts.length; i++) {
      if (current === null || current === undefined || typeof current !== "object") {
        return undefined;
      }
      current = current[parts[i]];
    }
    return current;
  }

  function format(template, params) {
    if (!template || typeof template !== "string") return "";
    if (!params) return template;
    return template.replace(/\{(\w+)\}/g, function (match, key) {
      return Object.prototype.hasOwnProperty.call(params, key) ? String(params[key]) : match;
    });
  }

  function tr(key, params, fallback) {
    var val = getNested(strings, key);
    if (typeof val === "string") {
      return format(val, params);
    }
    if (fallback !== undefined && fallback !== null) {
      return format(String(fallback), params);
    }
    return key;
  }

  function applyI18n(root) {
    var container = root || document.body;
    if (!container) return;

    var elements = container.querySelectorAll("[data-i18n]");
    for (var i = 0; i < elements.length; i++) {
      var el = elements[i];
      var key = el.getAttribute("data-i18n");
      if (key) {
        var text = tr(key, null, el.textContent);
        if (text) el.textContent = text;
      }
    }

    var placeholders = container.querySelectorAll("[data-i18n-placeholder]");
    for (var j = 0; j < placeholders.length; j++) {
      var pEl = placeholders[j];
      var pKey = pEl.getAttribute("data-i18n-placeholder");
      if (pKey) {
        var pText = tr(pKey, null, pEl.getAttribute("placeholder") || "");
        if (pText) pEl.setAttribute("placeholder", pText);
      }
    }

    var titles = container.querySelectorAll("[data-i18n-title]");
    for (var k = 0; k < titles.length; k++) {
      var tEl = titles[k];
      var tKey = tEl.getAttribute("data-i18n-title");
      if (tKey) {
        var tText = tr(tKey, null, tEl.getAttribute("title") || "");
        if (tText) tEl.setAttribute("title", tText);
      }
    }

    var arias = container.querySelectorAll("[data-i18n-aria-label]");
    for (var m = 0; m < arias.length; m++) {
      var aEl = arias[m];
      var aKey = aEl.getAttribute("data-i18n-aria-label");
      if (aKey) {
        var aText = tr(aKey, null, aEl.getAttribute("aria-label") || "");
        if (aText) aEl.setAttribute("aria-label", aText);
      }
    }
  }

  function setLocaleData(data) {
    if (data && typeof data === "object") {
      strings = data;
      if (typeof document !== "undefined") {
        applyI18n();
        try {
          window.dispatchEvent(new CustomEvent("i18nready", { detail: { locale: "ru" } }));
        } catch (_) {}
      }
    }
  }

  function init() {
    if (typeof fetch === "function") {
      fetch("locales/ru.json")
        .then(function (res) {
          if (res.ok) return res.json();
          throw new Error("HTTP " + res.status);
        })
        .then(function (data) {
          setLocaleData(data);
        })
        .catch(function () {
          // If fetch fails or runs offline, fallback strings remain
        });
    }
  }

  if (typeof window !== "undefined") {
    window.tr = tr;
    window.applyI18n = applyI18n;
    window.setLocaleData = setLocaleData;

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", init);
    } else {
      init();
    }
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { tr: tr, applyI18n: applyI18n, setLocaleData: setLocaleData };
  }
})();
