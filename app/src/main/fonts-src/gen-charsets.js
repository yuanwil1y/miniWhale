// 生成衬线标题子集的字表（只依赖 node 自带的 ICU，不联网）。
//
//   node app/src/main/fonts-src/gen-charsets.js
//
// 产出（都在本目录）：
//   gb2312-level1.txt   GB2312 一级汉字（16–55 区，3755 字，按拼音序）——由编码表直接解码得到
//   symbols.txt         ASCII 可见字符 + GB2312 第 1 区（中文标点）+ 第 3 区（全角 ASCII）+ 少量补充
//   fixed-from-source.txt  whaleui 源码里所有字符串字面量中出现的中文字（衬线标题的固定文字是它的子集）
//
// 子集最终字符 = 三个文件的并集，由 subset-serif.js 读取。
'use strict';
const fs = require('fs');
const path = require('path');

const here = __dirname;
const repoMain = path.resolve(here, '..'); // app/src/main
const dec = new TextDecoder('gb2312', { fatal: true });

// WHATWG 的 gb2312 解码器实际是 GBK，会把 GB2312 未定义的区位也解出字来，所以要显式给出每区的列数上限。
function gbRow(row, lastCol = 94) {
  const out = [];
  for (let col = 1; col <= lastCol; col++) {
    try {
      const ch = dec.decode(Uint8Array.from([0xa0 + row, 0xa0 + col]));
      if (ch && ch !== '�') out.push(ch);
    } catch (_) { /* 该区位未定义 */ }
  }
  return out;
}

// 1) GB2312 一级字
const level1 = [];
for (let row = 16; row <= 55; row++) level1.push(...gbRow(row, row === 55 ? 89 : 94)); // 55 区只有 89 字
if (level1.length !== 3755) throw new Error('GB2312 一级字数量不对：' + level1.length);

// 2) 符号：ASCII 可见字符、中文标点（第 1 区）、全角 ASCII（第 3 区）、补充
const symbols = new Set();
for (let c = 0x20; c <= 0x7e; c++) symbols.add(String.fromCharCode(c));
for (const ch of gbRow(1)) symbols.add(ch);
for (const ch of gbRow(3)) symbols.add(ch);
for (const ch of '「」『』〈〉《》【】〔〕〖〗·・–—―…‥～〜￥€℃°％‰№×÷　') symbols.add(ch);

// 3) 源码固定文字
const fixed = new Set();
const isCjk = (cp) => (cp >= 0x3400 && cp <= 0x9fff) || (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0x20000 && cp <= 0x3ffff);
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.kt')) {
      const src = fs.readFileSync(p, 'utf8');
      // 只取字符串字面量（"..." 与 """..."""），注释里的字不算
      const lits = src.match(/"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"/g) || [];
      for (const lit of lits) for (const ch of lit) if (isCjk(ch.codePointAt(0))) fixed.add(ch);
    }
  }
}
walk(path.join(repoMain, 'java', 'whaleui'));

const sorted = (set) => [...set].sort((a, b) => a.codePointAt(0) - b.codePointAt(0));
fs.writeFileSync(path.join(here, 'gb2312-level1.txt'), level1.join('') + '\n');
fs.writeFileSync(path.join(here, 'symbols.txt'), sorted(symbols).join('') + '\n');
fs.writeFileSync(path.join(here, 'fixed-from-source.txt'), sorted(fixed).join('') + '\n');

const l1 = new Set(level1);
const extra = sorted(fixed).filter((ch) => !l1.has(ch));
console.log(`GB2312 一级 ${level1.length}；符号 ${symbols.size}；源码中文 ${fixed.size}（其中一级字外 ${extra.length}：${extra.join('')}）`);
