package com.knowledge.ai.core.security;

import com.knowledge.ai.core.ai.ModelProperties;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * 模型 API Key 的加密存储（`app.model.key-secret` ← `.env` 的 {@code MODEL_KEY_SECRET}）。
 *
 * <h3>它解决什么</h3>
 * <p>`S6-M09-A` 期间密钥**不落库**（`api_key_cipher` 恒 `NULL`），一律按类型取 `.env` 的同名配置。
 * 那条裁定的代价写在 `plan/2026-09-18-S6-M09-A.md` §9-9：**新增一条指向别家供应商的模型时，
 * 它的密钥无处可取**，只能借用同类型在 `.env` 的那一把。本类让「密钥随行落库」成为可能 ——
 * 这正是《09-需求》§1.5 / §1.15 / §1.16 原本就要求的（加密存储、不回显明文、日志不出现明文）。
 *
 * <h3>算法与存储格式</h3>
 * <ul>
 *   <li><b>AES-256-GCM</b>，纯 JDK {@code javax.crypto}，**不引第三方依赖**（加解密工具在
 *       `S6-M09-A` 立据时是缺失项，见该计划 §3.3）；</li>
 *   <li>每次加密用**随机 12 字节 IV**、128-bit 认证 tag ⇒ 同一明文两次加密结果不同；</li>
 *   <li>存储串 = {@code "v1:" + Base64(IV ‖ 密文 ‖ tag)}。版本前缀是给**将来轮换算法**留的口子：
 *       换算法时新串用 `v2:`，读侧按前缀分派，老密文仍可解。</li>
 * </ul>
 *
 * <h3>主密钥怎么来</h3>
 * <p>{@code SHA-256(secret)} → 定长 32 字节，任意长度的 secret（含带 `-`/`_` 的 base64）都能用。
 * 用 SHA-256 而不是 PBKDF2：这里的 secret 是**运维生成的高熵随机串**，不是人类口令，
 * 慢哈希没有收益。
 *
 * <h3>🔴 三条失败语义（都刻意与 {@link JwtService} 不同）</h3>
 * <ul>
 *   <li><b>secret 没配</b>：只 WARN，**不抛** —— 应用照常启动，密钥继续从 `.env` 取。
 *       对比 {@code JwtService.init()}：那边缺密钥就整个鉴权无意义、必须启动失败；
 *       这边缺它只是"不能在页面上存 Key"，把能跑的系统弄成起不来是本末倒置。
 *       但{@code encrypt} 会抛 —— 保存这一步必须**明确失败**，不能静默存个空。</li>
 *   <li><b>解密失败</b>（secret 轮换 / 密文被改）：GCM 的认证 tag 会当场校验失败 →
 *       只记 ERROR，返回 {@link Optional#empty()}，由调用方**回退 `.env` 密钥**。
 *       **绝不把密文当明文交出去**（那会变成拿一串 base64 去当 Bearer token）。</li>
 *   <li><b>超长</b>：先算长度再落库，超了直接拒并说明上限 —— 不给 MySQL 静默截断的机会
 *       （截断的密文解不开，而那时已经存进去了）。</li>
 * </ul>
 *
 * <h3>日志纪律</h3>
 * <p>本类任何日志都**只打异常类型与计数**，绝不打 secret、明文或密文。方法参数里出现密钥时
 * 连异常消息都不直接抛给日志（{@code AEADBadTagException} 的消息本身无害，但守住格式更省心）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelKeyCipher {

    /** 存储串的格式版本前缀。 */
    static final String PREFIX = "v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** GCM 推荐 IV 长度（字节）。 */
    private static final int IV_BYTES = 12;

    /** 认证 tag 位数。 */
    private static final int TAG_BITS = 128;

    private static final int TAG_BYTES = TAG_BITS / 8;

    /**
     * 存储串上限 = {@code sys_model_config.api_key_cipher} 的列宽（`V1__init_skeleton_schema.sql:189`）。
     *
     * <p>列宽够用，因此**不需要新增迁移**：12(IV) + 16(tag) + 明文 → Base64 后，
     * 100 字符的 key 只有 175 字符。上限见 {@link #MAX_PLAINTEXT_CHARS}。
     */
    private static final int MAX_STORED_CHARS = 512;

    /**
     * 明文字符数上限 —— 由列宽反推：{@code (512 - 3) / 4 * 3 - 12 - 16 = 353}。
     *
     * <p>按 ASCII 字符算；key 本来就是 ASCII，不必为多字节字符再留余量。
     */
    static final int MAX_PLAINTEXT_CHARS = (MAX_STORED_CHARS - PREFIX.length()) / 4 * 3 - IV_BYTES - TAG_BYTES;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ModelProperties modelProperties;

    /** {@code null} = 主密钥未配置，加密不可用（见类注释第 1 条失败语义）。 */
    private SecretKey secretKey;

    /**
     * 派生主密钥。由容器在 `@PostConstruct` 调用；**public 是为了让不依赖 Spring 上下文的
     * 白盒用例能直接 `new ModelKeyCipher(props).init()`**（否则那些用例只能靠起整个上下文）。
     */
    @PostConstruct
    public void init() {
        String master = modelProperties.getKeySecret();
        if (master == null || master.isBlank()) {
            log.warn("未配置模型密钥主密钥（.env 的 MODEL_KEY_SECRET / app.model.key-secret）——"
                    + "配置页保存 API Key 会被拒绝；取密钥仍按 .env 的同名配置回退，问答链路不受影响");
            return;
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance(DIGEST_ALGORITHM)
                    .digest(master.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("模型 API Key 加密已就绪（AES-256-GCM，主密钥来自 MODEL_KEY_SECRET）");
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法，正常 JDK 走不到这儿；真走到了说明运行环境坏了
            throw new IllegalStateException("JVM 缺少 SHA-256，无法启用模型密钥加密", ex);
        }
    }

    /** 加密是否可用（主密钥已配置）。 */
    public boolean enabled() {
        return secretKey != null;
    }

    /**
     * 加密一条 API Key。
     *
     * @throws BizException 主密钥未配置 / 明文为空 / 超出可存储长度 —— 这三种都是**调用方能修的**
     *                      （前两种归运维，第三种归填 key 的人），所以给业务码而不是 500
     */
    public String encrypt(String plaintext) {
        if (!enabled()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "服务端未配置密钥加密主密钥（MODEL_KEY_SECRET），无法加密保存 API Key ——"
                            + "请联系运维在 .env 中补上并重启后端");
        }
        if (plaintext == null || plaintext.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "API Key 不能为空");
        }
        if (plaintext.length() > MAX_PLAINTEXT_CHARS) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "API Key 过长（" + plaintext.length() + " 字符，上限 " + MAX_PLAINTEXT_CHARS
                            + "）—— 请核对是否误粘了别的内容");
        }

        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(sealed, 0, payload, iv.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            // 加密失败是环境问题（JDK/Provider），不是用户输入问题 → 500 更如实
            log.error("API Key 加密失败：{}", ex.getClass().getSimpleName());
            throw new IllegalStateException("API Key 加密失败", ex);
        }
    }

    /**
     * 解密一条 API Key。**任何失败都返回空**，绝不抛、绝不返回半个值。
     *
     * <p>返回空的原因由调用方按「回退 `.env` 同名配置」处理（`DbModelConfigSource#plaintextKeyOf`）。
     */
    public Optional<String> decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        if (!stored.startsWith(PREFIX)) {
            // 认不出的格式：可能是手工塞进库的，也可能是将来 v2 的串被老代码读到了
            log.error("API Key 密文格式认不出（期望 {} 前缀）：长度={}", PREFIX, stored.length());
            return Optional.empty();
        }
        if (!enabled()) {
            log.error("库里有加密的 API Key，但主密钥未配置 —— 无法解密，将回退 .env 密钥");
            return Optional.empty();
        }

        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            log.error("API Key 密文不是合法 Base64，将回退 .env 密钥");
            return Optional.empty();
        }
        if (payload.length <= IV_BYTES) {
            log.error("API Key 密文长度不足（{} 字节），将回退 .env 密钥", payload.length);
            return Optional.empty();
        }

        byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
        byte[] sealed = Arrays.copyOfRange(payload, IV_BYTES, payload.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            return Optional.of(new String(cipher.doFinal(sealed), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            // ⚠️ 这里不打明文/密文，只打异常类型 —— 日志本身也是泄漏面
            log.error("API Key 密文解密失败（{}）：主密钥可能已轮换或密文被改动，将回退 .env 密钥",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
