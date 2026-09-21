/**
 * 探针素材生成器 —— **真字节造图，不靠记忆里的 base64**（`T-O26`）。
 *
 * <h2>为什么要有这个文件</h2>
 * `S7-M06-C` 的步 0 探针连翻两次车，两次都不是"模型的问题"：
 * <ol>
 *   <li>v1 用 `max_tokens=64` ⇒ HTTP 200 + **空回复**，差点被读成"模型不支持图片"；</li>
 *   <li>v2 用了**我凭记忆写的 base64** ⇒ HTTP 400，而现象看起来像"模型不收图" ——
 *       **一次假失败**，查下去只会往错的方向走。</li>
 * </ol>
 * 两次的共同形状：**素材本身不可复算**。记忆里的字节串既不能核对、也不能改一个像素再看差异。
 * 本文件把素材变成**代码**：`solidPng(64, 64, [220, 20, 20])` 在任何机器上都是同一张图，
 * 而"改一个像素"这件事变成了一次参数修改。
 *
 * <h2>为什么手写 PNG 编码而不是引依赖</h2>
 * 一张纯色 PNG 的编码是**确定的**（IHDR + IDAT(zlib) + IEND + 三个 CRC32），
 * 全部在 `node:zlib` 与四十行代码之内。为它引一个图像库等于让探针脚本背上一棵依赖树，
 * 而探针脚本的第一要求是"跑得起来、看得懂"。
 *
 * <h2>⚠️ 它只造纯色图</h2>
 * 纯色图能验「模型到底看没看见图」（问颜色即可，答案唯一），
 * **不能**验"它读不读得出图里的文字"。后者要真图表 —— 用仓库里 `docs/面试/图表/*.png`。
 * 这个边界写在这里，免得下一个人拿它去验 OCR 然后得出"模型不行"。
 */

import { deflateSync } from 'node:zlib'

/** CRC32（PNG 每个 chunk 末尾四字节）。表驱动会更快，但这里只跑几次。 */
function crc32(buf) {
  let c = ~0
  for (const b of buf) {
    c ^= b
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1))
  }
  return ~c >>> 0
}

/** 一个 PNG chunk：长度 + 类型 + 数据 + CRC。 */
function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}

/** PNG 文件头（8 字节魔数）。 */
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

/**
 * 造一张纯色 PNG。
 *
 * @param {number} w 宽（像素）
 * @param {number} h 高（像素）
 * @param {[number, number, number]} rgb 颜色
 * @returns {Buffer} 完整的 PNG 字节
 */
export function solidPng(w, h, [r, g, b]) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 2 // color type: truecolor (RGB)
  ihdr[10] = 0 // compression
  ihdr[11] = 0 // filter
  ihdr[12] = 0 // interlace

  const raw = Buffer.alloc(h * (1 + w * 3))
  for (let y = 0; y < h; y++) {
    const off = y * (1 + w * 3)
    raw[off] = 0 // 每行的 filter 字节：0 = None
    for (let x = 0; x < w; x++) {
      raw[off + 1 + x * 3] = r
      raw[off + 2 + x * 3] = g
      raw[off + 3 + x * 3] = b
    }
  }

  return Buffer.concat([
    PNG_MAGIC,
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

/**
 * 自检：造出来的**必须真的是 PNG**。
 *
 * <p>为什么要把这件事写成断言而不是"看着对"：本文件的存在理由就是
 * 「素材不可复算会造假失败」。一个"造出来的字节不是 PNG"的生成器会**原样复制**那个失败
 * —— 现象还是 HTTP 400，还是看起来像"模型不收图"。所以这里自己先验一遍：
 * 魔数、IHDR 里的宽高、以及末尾的 IEND。
 *
 * @returns {{bytes: number, width: number, height: number, head: string}}
 * @throws {Error} 任一项不符
 */
export function selfTest() {
  const png = solidPng(8, 4, [255, 0, 0])
  const head = png.subarray(0, 8).toString('hex')
  if (head !== '89504e470d0a1a0a') {
    throw new Error(`造出来的不是 PNG：魔数 = ${head}`)
  }
  if (png.readUInt32BE(16) !== 8 || png.readUInt32BE(20) !== 4) {
    throw new Error(`IHDR 里的宽高不对：${png.readUInt32BE(16)}×${png.readUInt32BE(20)}`)
  }
  if (png.subarray(png.length - 8, png.length - 4).toString('ascii') !== 'IEND') {
    throw new Error('末尾不是 IEND')
  }
  return { bytes: png.length, width: 8, height: 4, head }
}

// `node lib/test-image.mjs` 直接跑 = 自检
if (process.argv[1] && process.argv[1].endsWith('test-image.mjs')) {
  const r = selfTest()
  console.log(`✅ 素材生成器自检通过：${r.width}×${r.height} · ${r.bytes} 字节 · 头=${r.head}`)
}
