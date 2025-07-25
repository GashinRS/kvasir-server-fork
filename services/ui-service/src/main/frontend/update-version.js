const fs = require("fs");

const version = require("./package.json").version;
const buildDate = new Date().toISOString();

const content = `export const version = '${version}';
export const buildDate = '${buildDate}';`

fs.writeFileSync("./src/environments/version.prod.ts", content);

console.log("Updated version!", { version, buildDate });
