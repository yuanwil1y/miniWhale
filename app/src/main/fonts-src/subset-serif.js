// 把完整的 Noto Serif SC（可变字体）裁成标题用子集，写到 res/font/noto_serif_sc_title.ttf。
//
// 用法（先跑 gen-charsets.js 更新字表）：
//   node app/src/main/fonts-src/subset-serif.js <完整字体.ttf> [subset-font 所在的 node_modules 目录]
//
// 依赖 npm 包 subset-font（harfbuzz 的 hb-subset，wasm 版）。它不装进本仓库：
// 在仓库外任意目录 `npm install subset-font`，然后把那里的 node_modules 路径作为第二个参数传进来。
'use strict';
const fs = require('fs');
const path = require('path');

const [, , fontPath, modulesDir] = process.argv;
if (!fontPath) {
  console.error('用法：node subset-serif.js <NotoSerifSC 完整可变字体.ttf> [node_modules 目录]');
  process.exit(2);
}
const subsetFont = require(modulesDir ? path.join(modulesDir, 'subset-font') : 'subset-font');

const here = __dirname;
const out = path.resolve(here, '..', 'res', 'font', 'noto_serif_sc_title.ttf');
const read = (name) => fs.readFileSync(path.join(here, name), 'utf8').replace(/\r?\n/g, '');
const chars = new Set([...read('gb2312-level1.txt'), ...read('symbols.txt'), ...read('fixed-from-source.txt')]);
const text = [...chars].join('');

(async () => {
  const input = fs.readFileSync(fontPath);
  const result = await subsetFont(input, text, {
    targetFormat: 'sfnt',
    // 标题实际用到 400（区块标签）、500、600（标题）；把 wght 轴从 200–900 裁到 400–600，默认 400。
    variationAxes: { wght: { min: 400, max: 600, default: 400 } },
  });
  fs.writeFileSync(out, result);
  console.log(`字符 ${chars.size} 个；${input.length} B → ${result.length} B（${(result.length / 1048576).toFixed(2)} MB）→ ${out}`);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
