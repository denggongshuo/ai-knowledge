package com.knowledge.ai.core.security;

import com.knowledge.ai.core.ai.ModelProperties;
import com.knowledge.ai.core.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型 API Key 加解密（2026-10-11）。
 *
 * <h3>这一类的判据为什么重要</h3>
 * <p>密钥落库以后，**唯一**能证明「发出去的是明文 key、存下去的是密文」的地方就是这里。
 * 若把密文当明文交给客户端构造，症状是供应商回 401 —— 看着像"密钥配错了"，
 * 实际是"根本没解密"。所以往返用例不是形式，它是那条链路的机器判据。
 *
 * <h3>为什么能白盒直连（不起 Spring 上下文）</h3>
 * <p>{@link ModelKeyCipher#init()} 是 public 的，构造它只需要一个 {@link ModelProperties}。
 * 加解密本身与容器无关，起整个上下文测它只会让用例变慢且更难定位。
 */
class ModelKeyCipherTest {

    /** 一把足够长的样例明文（形似真实 key）。 */
    private static final String PLAIN = "sk-abcdefghijklmnopqrstuvwxyz0123456789";

    private static ModelKeyCipher cipherWith(String masterSecret) {
        ModelProperties props = new ModelProperties();
        props.setKeySecret(masterSecret);
        ModelKeyCipher cipher = new ModelKeyCipher(props);
        cipher.init();
        return cipher;
    }

    @Test
    @DisplayName("往返：解出来就是原来那把 —— 调用链拿到的必须是明文")
    void roundTrip() {
        ModelKeyCipher cipher = cipherWith("unit-test-master-secret");

        String stored = cipher.encrypt(PLAIN);

        assertThat(stored).as("带版本前缀，给将来换算法留出口").startsWith("v1:");
        assertThat(stored).as("密文不得包含明文（否则等于没加密）").doesNotContain(PLAIN).isNotEqualTo(PLAIN);
        assertThat(cipher.decrypt(stored)).contains(PLAIN);
    }

    @Test
    @DisplayName("随机 IV：同一明文两次加密结果不同（否则泄漏「这两条是同一把 key」）")
    void ivIsRandomPerCall() {
        ModelKeyCipher cipher = cipherWith("unit-test-master-secret");

        assertThat(cipher.encrypt(PLAIN)).isNotEqualTo(cipher.encrypt(PLAIN));
    }

    @Test
    @DisplayName("篡改密文 → 返回空而不抛：GCM 认证 tag 会拦下")
    void tamperedCiphertextIsRejected() {
        ModelKeyCipher cipher = cipherWith("unit-test-master-secret");
        String stored = cipher.encrypt(PLAIN);

        // 翻中间一个字符：它一定参与密文字节，而不像末尾字符那样可能落在 padding 位上
        int at = ModelKeyCipher.PREFIX.length() + 5;
        char original = stored.charAt(at);
        String tampered = stored.substring(0, at) + (original == 'A' ? 'B' : 'A') + stored.substring(at + 1);

        assertThat(cipher.decrypt(tampered)).as("解不开就如实返回空，由调用方回退 .env").isEmpty();
    }

    @Test
    @DisplayName("换主密钥 → 空（不是抛、也不是给半截值）")
    void wrongMasterSecretFailsClosed() {
        String stored = cipherWith("master-A").encrypt(PLAIN);

        assertThat(cipherWith("master-B").decrypt(stored)).isEmpty();
    }

    @Test
    @DisplayName("主密钥未配置：不拦启动（enabled=false）、存 Key 明确失败、解密返回空")
    void missingMasterSecretDegrades() {
        ModelKeyCipher cipher = cipherWith("");

        assertThat(cipher.enabled()).as("缺主密钥不该让应用起不来（密钥仍可回退 .env）").isFalse();
        assertThatThrownBy(() -> cipher.encrypt(PLAIN))
                .isInstanceOf(BizException.class)
                .as("保存 Key 这一步必须明确失败，并指出缺的是哪个变量")
                .hasMessageContaining("MODEL_KEY_SECRET");
        assertThat(cipher.decrypt("v1:whatever")).isEmpty();
    }

    @Test
    @DisplayName("认不出的格式 / 空值 → 空（不抛）：手工塞进库的脏数据不该打挂接口")
    void unrecognisedInputIsIgnored() {
        ModelKeyCipher cipher = cipherWith("unit-test-master-secret");

        assertThat(cipher.decrypt(null)).isEmpty();
        assertThat(cipher.decrypt("  ")).isEmpty();
        assertThat(cipher.decrypt("明文直接塞进来")).as("没有 v1: 前缀的一律不认").isEmpty();
        assertThat(cipher.decrypt("v1:" + "短")).as("长度不足 IV 的也拒").isEmpty();
    }

    @Test
    @DisplayName("长度边界：353 字符存得下且装得进 VARCHAR(512)；354 字符直接拒")
    void lengthBoundaryFollowsColumnWidth() {
        ModelKeyCipher cipher = cipherWith("unit-test-master-secret");
        String maxKey = "k".repeat(ModelKeyCipher.MAX_PLAINTEXT_CHARS);

        String stored = cipher.encrypt(maxKey);
        assertThat(stored.length())
                .as("必须装得进 api_key_cipher VARCHAR(512)，否则 MySQL 会静默截断")
                .isLessThanOrEqualTo(512);
        assertThat(cipher.decrypt(stored)).contains(maxKey);

        assertThatThrownBy(() -> cipher.encrypt("k".repeat(ModelKeyCipher.MAX_PLAINTEXT_CHARS + 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过长");
    }
}
