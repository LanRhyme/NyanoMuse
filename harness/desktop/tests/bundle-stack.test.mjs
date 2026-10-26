// The composition the app boots: one list, three files. `src/main.ts` writes the profile's
// bundle stack, `scripts/smoke.mjs` boots the same stack for its check, and
// `scripts/prepare-dsh.mjs` installs every package the stack names. A bundle added to one
// and not to the others is an app that fails to boot on the machine while the check here
// passes, so the three lists are read and compared.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), "utf8");

/** The quoted strings of an array literal that follows `name` in a source file. */
function listOf(source, name) {
  const at = source.indexOf(name);
  assert.ok(at >= 0, `no ${name}`);
  const open = source.indexOf("[", at);
  const close = source.indexOf("]", open);
  assert.ok(open >= 0 && close > open, `no array after ${name}`);
  return [...source.slice(open, close).matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

test("the bundle stack main.ts writes is the one smoke boots and prepare-dsh installs", () => {
  const main = listOf(read("../src/main.ts"), "const BUNDLES =");
  const smoke = listOf(read("../scripts/smoke.mjs"), "const BUNDLES =");
  assert.deepEqual(smoke, main, "scripts/smoke.mjs boots a different stack than src/main.ts writes");
  // every bundle that is not the harness's own is installed by name, with a pinned version
  const extra = listOf(read("../scripts/prepare-dsh.mjs"), "const EXTRA_BUNDLES =");
  const installed = extra.map((spec) => spec.split("@")[0]);
  for (const name of main) {
    if (name.startsWith("@deepseek-ai/")) continue;
    // the bundle itself is installed from the tarball this repository packs, not from npm
    if (name === "dsh-nanomuse") continue;
    assert.ok(installed.includes(name), `${name} is mounted but never installed`);
  }
  assert.match(read("../scripts/prepare-dsh.mjs"), /run\(pnpm, \["pack"/, "the bundle is packed, not installed from npm");
  // and each pinned plugin names a version, so a release cannot drift under the app
  for (const spec of extra) assert.match(spec, /^[^@]+@\d+\.\d+\.\d+$/, spec);
  // the harness and its schedule bundle come first: a bundle layer sees only the rows before it
  assert.equal(main[0], "@deepseek-ai/dsh-base");
  assert.equal(main[1], "@deepseek-ai/dsh-web-app");
});
