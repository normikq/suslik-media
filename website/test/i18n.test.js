"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { tr, setLocaleData } = require("../public/i18n");

test("i18n: ru.json exists, is valid JSON, and has no alldebrid mentions", () => {
  const filePath = path.join(__dirname, "../public/locales/ru.json");
  assert.ok(fs.existsSync(filePath), "locales/ru.json must exist");
  const raw = fs.readFileSync(filePath, "utf8");
  assert.doesNotThrow(() => JSON.parse(raw), "locales/ru.json must be valid JSON");
  assert.equal(/alldebrid/i.test(raw), false, "locales/ru.json must not contain 'alldebrid'");

  const data = JSON.parse(raw);
  assert.ok(data.app && data.library && data.search && data.auth && data.history);
});

test("i18n: tr() resolves nested keys and formats params", () => {
  const ruData = JSON.parse(fs.readFileSync(path.join(__dirname, "../public/locales/ru.json"), "utf8"));
  setLocaleData(ruData);

  assert.equal(tr("app.name"), "Suslik Media");
  assert.equal(tr("library.loaded", { count: 42 }), "Загружено раздач: 42. Список сохранён в браузере.");
  assert.equal(tr("nonexistent.key", null, "По умолчанию"), "По умолчанию");
  assert.equal(tr("nonexistent.key"), "nonexistent.key");
});
