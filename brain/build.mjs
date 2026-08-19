import esbuild from "esbuild";
await esbuild.build({
  entryPoints: ["src/main.ts"],
  bundle: true,
  platform: "node",
  format: "esm",
  outfile: "dist/brain.mjs",
  external: ["node:*", "@earendil-works/*", "typebox"],
  banner: { js: `import{createRequire}from"node:module";const require=createRequire(import.meta.url);` },
});
console.log("built dist/brain.mjs");