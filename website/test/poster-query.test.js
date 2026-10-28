"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { posterQueryVariants } = require("../poster-query");
const { refineKinds, cleanTitle } = require("../tmdb-kind");

test("posterQueryVariants cleans release names", () => {
  assert.deepEqual(posterQueryVariants("Severance"), ["Severance"]);
  assert.ok(posterQueryVariants("Severance Season 1").includes("Severance"));
  assert.ok(posterQueryVariants("Менталист 1 сезон").includes("Менталист"));
  assert.ok(posterQueryVariants("Show 2-й сезон").includes("Show"));
  assert.ok(posterQueryVariants("Show - S01-10").includes("Show"));
  assert.ok(posterQueryVariants("Erai-raws Show - 01 ~ 10").includes("Show"));
  assert.ok(posterQueryVariants("WALL-E").includes("WALL·E"));
  assert.ok(posterQueryVariants("A-B C-D E-F G-H I-J K-L").length <= 5);
});

test("cleanTitle", () => {
  assert.deepEqual(cleanTitle("ВАЛЛ·И / WALL·E (2008) BDRip 1080p"), { query: "WALL·E", year: "2008" });
});

test("refineKinds without a key changes nothing and keeps series", async () => {
  const rs = [{ title: "Avatar S01 1080p", kind: "series" }, { title: "X (2000) 1080p", kind: "movie" }];
  await refineKinds(rs, "");
  assert.deepEqual(rs.map(r => r.kind), ["series", "movie"]);
});
