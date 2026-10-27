# 衬线标题字体子集（trim-plan 决定 D3）

正文、按钮、列表、聊天一律用系统字体（`FontFamily.Default`），App 不再打包 Noto Sans SC。
衬线体 Noto Serif SC 只给**标题**用（`ui/theme/Type.kt` 里的 `NotoSerifSc`），并且只打包一个子集：
`res/font/noto_serif_sc_title.ttf`。本目录是重新生成它所需的全部输入。这个目录不参与 Gradle 构建。

## 子集包含什么

| 文件 | 内容 | 怎么来的 |
|---|---|---|
| `gb2312-level1.txt` | GB2312 一级汉字 3755 字（16–55 区） | `gen-charsets.js` 用 node 自带的 GB2312 解码表逐区位解码 |
| `symbols.txt` | ASCII 可见字符、GB2312 第 1 区（中文标点）、第 3 区（全角字母数字）、少量补充符号 | 同上 |
| `fixed-from-source.txt` | `app/src/main/java/whaleui/` 下所有 Kotlin 字符串字面量里的中文字 | 同上，扫描源码；衬线标题的固定文字是它的子集 |

字重：原字体是 wght 200–900 的可变字体；子集把轴裁到 **400–600**（标题 600，区块标签 400），默认 400。

## 重新生成（改了标题文字、或想放宽字表时）

```bash
# 1. 在仓库外装子集化工具（不要装进本仓库）
mkdir -p <仓库外目录>/font-subset && cd <仓库外目录>/font-subset && npm install subset-font

# 2. 准备完整字体（不进仓库）：Noto Serif SC 可变字体 NotoSerifSC[wght].ttf
#    来源：https://github.com/notofonts/noto-cjk （Serif/Variable/TTF/Subset/NotoSerifSC-VF.ttf）
#    或 Google Fonts「Noto Serif SC」。本仓库提交 48b700f 里的 res/font/noto_serif_sc.ttf 也是它。

# 3. 回到仓库根目录
node app/src/main/fonts-src/gen-charsets.js
node app/src/main/fonts-src/subset-serif.js <完整字体.ttf> <仓库外目录>/font-subset/node_modules
```

生成后提交 `res/font/noto_serif_sc_title.ttf` 和本目录里更新过的字表。

## 子集外的字

动态标题（比如以后把她的名字、任务名放进衬线标题）里如果出现子集外的字，Android 会按字形回退到系统字体
显示，不会出现方框；只是那个字是无衬线的。真要避免，就把字加进字表重新生成，或者那个标题改用系统字体。
