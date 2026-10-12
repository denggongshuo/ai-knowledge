package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.security.ModelKeyCipher;
import com.knowledge.ai.entity.SysModelConfig;
import com.knowledge.ai.repository.SysModelConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 密钥解析的唯一入口 {@link DbModelConfigSource#plaintextKeyOf}（2026-10-11）。
 *
 * <h3>为什么它必须单独有用例</h3>
 * <p>密钥有两条来源（行上的密文 / `.env` 同名配置），优先级只有一处决定。这里验的就是那处：
 * <ul>
 *   <li>有密文 → **明文**进 spec（⇒ 客户端构造拿到的是明文，不是密文）；</li>
 *   <li>没密文 / 密文解不开 → 回退 `.env`，且**不抛**。</li>
 * </ul>
 * 若这两条反过来，症状分别是「拿一串 base64 去当 Bearer token（供应商 401）」与
 * 「一行脏密文把整条问答链路打挂」—— 两种都不会在编译期或启动期暴露。
 *
 * <p>不起 Spring 上下文：本类没有容器相关行为，起上下文只会让用例变慢。
 * 那个 mock 的 repository 在所有用例里**都没被调用** —— 解析密钥不查库。
 */
class DbModelConfigSourceTest {

    private static final String ENV_KEY = "sk-from-env-0123456789";

    private static final String DB_KEY = "sk-from-db-abcdefghijklmn";

    private static ModelProperties propertiesWithEnvKey() {
        ModelProperties props = new ModelProperties();
        props.getChat().setBaseUrl("https://api.example.test/v1");
        props.getChat().setApiKey(ENV_KEY);
        props.getChat().setModel("some-model");
        props.setKeySecret("unit-test-master-secret");
        return props;
    }

    private static SysModelConfig chatRow(String cipherText) {
        SysModelConfig row = new SysModelConfig();
        row.setId(7L);
        row.setModelType(ModelType.CHAT);
        row.setBaseUrl("https://api.example.test/v1");
        row.setModelName("some-model");
        row.setApiKeyCipher(cipherText);
        row.setIsActive(true);
        return row;
    }

    private static DbModelConfigSource sourceOf(ModelProperties props, ModelKeyCipher cipher) {
        return new DbModelConfigSource(mock(SysModelConfigRepository.class), props, cipher);
    }

    @Test
    @DisplayName("行上有密文 → 解出来的**明文**进 spec（真正的调用凭证不是密文）")
    void storedCipherIsDecryptedIntoTheSpec() {
        ModelProperties props = propertiesWithEnvKey();
        ModelKeyCipher cipher = new ModelKeyCipher(props);
        cipher.init();
        DbModelConfigSource source = sourceOf(props, cipher);
        SysModelConfig row = chatRow(cipher.encrypt(DB_KEY));

        assertThat(source.plaintextKeyOf(row)).isEqualTo(DB_KEY);

        String apiKey = source.specOf(row).apiKey();
        assertThat(apiKey).as("喂给 ModelClientFactory 的必须是明文").isEqualTo(DB_KEY);
        assertThat(apiKey).as("绝不能是密文 —— 那会变成拿 base64 当 Bearer token").doesNotContain("v1:");
        assertThat(source.specOf(row).describe()).as("可进日志的摘要只报有无，不带 key").doesNotContain(DB_KEY);
    }

    @Test
    @DisplayName("密文解不开 / 没有密文 → 回退 .env（不抛、不把密文当明文）")
    void fallsBackToEnvironmentKey() {
        ModelProperties props = propertiesWithEnvKey();
        ModelKeyCipher cipher = new ModelKeyCipher(props);
        cipher.init();
        DbModelConfigSource source = sourceOf(props, cipher);

        assertThat(source.plaintextKeyOf(chatRow(null))).as("种子行就是这种（api_key_cipher 恒 NULL）")
                .isEqualTo(ENV_KEY);
        assertThat(source.plaintextKeyOf(chatRow("   "))).isEqualTo(ENV_KEY);
        assertThat(source.plaintextKeyOf(chatRow("v1:bm90LXJlYWxseS1hLWNpcGhlcg==")))
                .as("脏密文只该降级，不该抛").isEqualTo(ENV_KEY);
        assertThat(source.specOf(chatRow("v1:bm90LXJlYWxseS1hLWNpcGhlcg==")).apiKey()).isEqualTo(ENV_KEY);
    }

    @Test
    @DisplayName("主密钥轮换过 → 已存的密文全部解不开，如实回退 .env（而不是报错）")
    void rotatedMasterSecretFallsBackInsteadOfFailing() {
        ModelProperties props = propertiesWithEnvKey();
        ModelKeyCipher oldCipher = new ModelKeyCipher(props);
        oldCipher.init();
        String stored = oldCipher.encrypt(DB_KEY);

        props.setKeySecret("rotated-master-secret");
        ModelKeyCipher newCipher = new ModelKeyCipher(props);
        newCipher.init();
        DbModelConfigSource source = sourceOf(props, newCipher);

        assertThat(source.plaintextKeyOf(chatRow(stored)))
                .as("页面改过的那把 key 会静默失效，但问答仍用 .env 的那把活着")
                .isEqualTo(ENV_KEY);
    }
}
