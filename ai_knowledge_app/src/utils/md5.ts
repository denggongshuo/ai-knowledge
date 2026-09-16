/**
 * 纯 JS 增量 MD5（RFC 1321）—— **零依赖**。
 *
 * <h3>为什么自己写而不是引 `spark-md5`</h3>
 * 本仓的前端依赖面从 S0 起刻意维持极小（`package.json` 的 `dependencies` 只有
 * vue / vue-router / pinia / element-plus 四项）。本功能是**一次性算法**，
 * 不是一个会持续演化的库 —— 为省 80 行而多一个"要跟着升级锁文件"的第三方包不值。
 *
 * <p>⚠️ **不要改用 `crypto.subtle.digest`**：SubtleCrypto 提供 SHA-1/256/384/512，
 * **不提供 MD5**。换成 SHA-256 技术上可行，但去重键的语义会改，
 * 得同步改《06》§3.2 与 `T-501` 的口径 —— 那是需求变更，不是实现细节。
 *
 * <h3>与后端的一致性</h3>
 * 后端在 `DocService#md5Of` 也用 MD5（`MessageDigest`）重算一遍，两者必须逐位相同。
 * 不一致的后果不是报错，而是 `check-md5` **永远不命中** —— 表现为"秒传静默失效"，
 * 界面上看不出来（用户只是每次都得重传）。所以判据 3/4 就是钉这一条的。
 *
 * <h3>用法</h3>
 * ```ts
 * const hex = await md5OfFile(file)   // 32 位小写十六进制
 * ```
 */

/** 每轮的左移位数（64 步，按 16 步一组共用）。 */
const SHIFTS = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
]

/** 正弦常量表 K[i] = floor(2^32 × |sin(i+1)|)。 */
const K = new Uint32Array(64)
for (let i = 0; i < 64; i++) {
  K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296)
}

/**
 * 读一大块时用的缓冲区。
 *
 * <p>4MB 是折中：太小则 `arrayBuffer()` 与循环的调用次数多；太大则一次性占用的
 * 内存多（本函数不是流式的 —— `File.slice()` 得到的 Blob 要整个转成 ArrayBuffer 才能
 * 逐字处理）。100MB 文件按 4MB 分 25 块。
 */
const CHUNK_SIZE = 4 * 1024 * 1024

/** 计算文件的 MD5，返回 32 位小写十六进制。 */
export async function md5OfFile(file: File): Promise<string> {
  // 状态寄存器初值（RFC 1321 §3.3）
  let a0 = 0x67452301
  let b0 = 0xefcdab89
  let c0 = 0x98badcfe
  let d0 = 0x10325476

  const total = file.size
  // 分块读：每一块内部按 64 字节分组处理；**跨块的 64 字节分组由 carry 承接** ——
  // 这条是整份实现里最容易写错的地方（不能简单地"每块各算各的"，MD5 是按整条
  // 消息的字节流分组的，4MB 不是 64 的整数倍时分组会跨块）。
  let carry = new Uint8Array(0)

  for (let offset = 0; offset < total; offset += CHUNK_SIZE) {
    const slice = file.slice(offset, Math.min(offset + CHUNK_SIZE, total))
    const bytes = new Uint8Array(await slice.arrayBuffer())

    let buf: Uint8Array
    if (carry.length === 0) {
      buf = bytes
    } else {
      buf = new Uint8Array(carry.length + bytes.length)
      buf.set(carry, 0)
      buf.set(bytes, carry.length)
    }

    const usable = buf.length - (buf.length % 64)
    for (let p = 0; p < usable; p += 64) {
      const m = readBlock(buf, p)
      const [na, nb, nc, nd] = compress(a0, b0, c0, d0, m)
      a0 = (a0 + na) >>> 0
      b0 = (b0 + nb) >>> 0
      c0 = (c0 + nc) >>> 0
      d0 = (d0 + nd) >>> 0
    }    carry = usable === buf.length ? new Uint8Array(0) : buf.slice(usable)
  }

  // ---------- 收尾：填充 + 追加原始长度（小端 64 位比特数）----------
  // 规则：先补一个 0x80，再补若干个 0，直到「总长 ≡ 56 (mod 64)」，最后 8 字节放长度。
  // 于是长度字段的**字节偏移**只能是 56 或 120 —— 取哪个取决于 L+1 是否已越过 56。
  // ⚠️ 判据是 `L + 1 <= 56`（即 L ≤ 55），不是 `L + 9 <= 56` —— 后者会把 L ∈ [48,55]
  // 误判成需要额外一整块，而那一块里 0x80 之后的 0 填充位置就全错了（整份摘要作废）。
  const padStart = carry.length + 1
  const lenOffset = padStart <= 56 ? 56 : 120
  // 缓冲区按最大可能长度申请（120 + 8 = 128）；Uint8Array 越界写**静默丢弃**，
  // 少申请一个字节不会报错，只会让摘要算错 —— 所以这里宁可多给。
  const tail = new Uint8Array(Math.max(lenOffset + 8, carry.length + 128))
  tail.set(carry, 0)
  tail[carry.length] = 0x80
  // 长度按**比特**计：JS 的 Number 在 2^53 内精确，100MB × 8 = 8e8 远小于它，
  // 所以低 32 位用乘法、高 32 位用除法即可，不需要 BigInt。
  const bitLo = (total * 8) >>> 0
  const bitHi = Math.floor((total * 8) / 4294967296) >>> 0
  writeLE32(tail, lenOffset, bitLo)
  writeLE32(tail, lenOffset + 4, bitHi)

  for (let p = 0; p < lenOffset + 8; p += 64) {
    const m = readBlock(tail, p)
    const [na, nb, nc, nd] = compress(a0, b0, c0, d0, m)
    a0 = (a0 + na) >>> 0
    b0 = (b0 + nb) >>> 0
    c0 = (c0 + nc) >>> 0
    d0 = (d0 + nd) >>> 0
  }

  return hexLE(a0) + hexLE(b0) + hexLE(c0) + hexLE(d0)
}

/** 从 64 字节小端读成 16 个 32 位字。 */
function readBlock(buf: Uint8Array, p: number): Uint32Array {
  const m = new Uint32Array(16)
  for (let i = 0; i < 16; i++) {
    const o = p + i * 4
    m[i] = (buf[o]! | (buf[o + 1]! << 8) | (buf[o + 2]! << 16) | (buf[o + 3]! << 24)) >>> 0
  }
  return m
}

function writeLE32(buf: Uint8Array, p: number, v: number) {
  buf[p] = v & 0xff
  buf[p + 1] = (v >>> 8) & 0xff
  buf[p + 2] = (v >>> 16) & 0xff
  buf[p + 3] = (v >>> 24) & 0xff
}

/** 输出用**小端**字节序拼接（RFC 1321 的 digest 是 A/B/C/D 各自小端）。 */
function hexLE(v: number): string {
  let s = ''
  for (let i = 0; i < 4; i++) {
    s += ((v >>> (i * 8)) & 0xff).toString(16).padStart(2, '0')
  }
  return s
}

/** 四轮 64 步压缩函数。返回四个新状态值（未与旧值相加）。 */
function compress(a0: number, b0: number, c0: number, d0: number, m: Uint32Array): [number, number, number, number] {
  let a = a0
  let b = b0
  let c = c0
  let d = d0

  for (let i = 0; i < 64; i++) {
    let f: number
    let g: number
    if (i < 16) {
      f = (b & c) | (~b & d)
      g = i
    } else if (i < 32) {
      f = (d & b) | (~d & c)
      g = (5 * i + 1) % 16
    } else if (i < 48) {
      f = b ^ c ^ d
      g = (3 * i + 5) % 16
    } else {
      f = c ^ (b | ~d)
      g = (7 * i) % 16
    }

    const tmp = d
    d = c
    c = b
    const sum = (a + f + K[i]! + m[g]!) >>> 0
    const rot = ((sum << SHIFTS[i]!) | (sum >>> (32 - SHIFTS[i]!))) >>> 0
    b = (b + rot) >>> 0
    a = tmp
  }

  return [a, b, c, d]
}
