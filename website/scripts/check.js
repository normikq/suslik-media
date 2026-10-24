const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const root = path.resolve(__dirname, "..");
function findJsFiles(dir) {
  let results = [];
  try {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        results = results.concat(findJsFiles(full));
      } else if (entry.isFile() && entry.name.endsWith(".js")) {
        results.push(path.relative(root, full));
      }
    }
  } catch {}
  return results;
}

const files = [
  "server.js",
  ...findJsFiles(path.join(root, "src")),
  "search.js",
  "search-ru.js",
  "classify.js",
  "tmdb-kind.js",
  "poster-query.js",
  path.join("public", "redesign.js"),
  path.join("public", "home.js")
];
let failed = false;

function checkJavaScript(label, source) {
  const result = spawnSync(process.execPath, ["--check", "-"], {
    input: source,
    encoding: "utf8",
  });
  if (result.status !== 0) {
    failed = true;
    process.stderr.write(`ОШИБКА синтаксиса: ${label}\n${result.stderr}`);
  } else {
    console.log(`OK: ${label}`);
  }
}

for (const file of files) {
  checkJavaScript(file, fs.readFileSync(path.join(root, file), "utf8"));
}

for (const file of [path.join("public", "index.html")]) {
  const html = fs.readFileSync(path.join(root, file), "utf8");
  const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)];
  if (!/<html[^>]*lang=["']ru["']/i.test(html)) {
    failed = true;
    console.error(`ОШИБКА: ${file} не объявляет русский язык (lang="ru").`);
  }
  let inlineCount = 0;
  for (let i = 0; i < scripts.length; i += 1) {
    const code = scripts[i][1];
    if (code.trim()) {
      inlineCount += 1;
      checkJavaScript(`${file}: inline script ${i + 1}`, code);
    }
  }
  console.log(`Проверено встроенных скриптов в ${file}: ${inlineCount}`);
}

if (failed) process.exit(1);
console.log("Статическая проверка завершена. Это не заменяет интеграционные тесты и проверку в браузере.");
