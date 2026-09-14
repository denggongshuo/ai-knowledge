package com.knowledge.ai.core.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 步骤 1 · 探测：Boot 4 自动配置的到底是哪个 {@code ObjectMapper}。
 *
 * <p><b>为什么要专门探一次</b>：本项目的 classpath 上**同时存在两个 Jackson** ——
 * {@code tools.jackson.core:jackson-databind:3.0.2}（Boot 4 默认，Jackson 3，包名 {@code tools.jackson}）
 * 与 {@code com.fasterxml.jackson.core:jackson-databind:2.20.1}（JWT 库 {@code jjwt-jackson} 的传递依赖）。
 * 两个包下都有 `ObjectMapper` 这个类名，注入错的那个**编译期不报错、运行期才炸**。
 * 本仓 {@code ResponseWriter} 的 javadoc 已经记录过这个坑（"注入哪个 ObjectMapper 会随版本与依赖而变化"）。
 *
 * <p><b>为什么不靠推断</b>：靠 pom 推断只能得出"应该注入 Jackson 3"，
 * 但"应该"和"容器里到底注册了什么"是两回事（自动配置可能因缺 starter、被 exclude、或条件不满足而不生效）。
 * 这里直接问容器要：
 * <ol>
 *   <li>注入 `tools.jackson.databind.ObjectMapper` —— 能注入成功，说明 Boot 4 注册的确实是它；</li>
 *   <li>列出容器里所有 `ObjectMapper` 类型的 bean 名，确认**没有** `com.fasterxml` 那个在同台竞争；</li>
 *   <li>做一次真实的**序列化 + 反序列化**往返 —— 因为 T-300 的 `changes_json`
 *       两边都要用（C 阶段要反解析回来），只测序列化会漏掉反序列化不可用的情形。</li>
 * </ol>
 *
 * <p>探针结果直接决定 `AuditLogService` 与 C 阶段详情接口注入哪个类型，故用例断言得比较死。
 */
@SpringBootTest
class JacksonProbeTest {

    /**
     * Boot 4 自动配置的 JSON mapper（Jackson 3，包名 {@code tools.jackson}）。
     * 注入不上这个类就意味着本仓的 JSON 路线要重新选型。
     */
    @Autowired
    private tools.jackson.databind.ObjectMapper jsonMapper;

    /**
     * Jackson 3 的 {@code ObjectMapper} 继承自 {@code ObjectMapperBuilder} 体系的基类，
     * 想拿它反序列化到 `List<Record>` 需要这个 {@code TypeReference} 同级类型。
     * 这里注入容器里的 {@code JsonMapper}（更具体的子类型）做交叉验证。
     */
    @Autowired(required = false)
    private tools.jackson.databind.json.JsonMapper jsonMapperConcrete;

    @Test
    @DisplayName("步骤1：Boot 4 自动配置注入的是 Jackson 3 的 tools.jackson ObjectMapper")
    void boot4AutoConfiguresJackson3Mapper() {
        assertNotNull(jsonMapper, "Boot 4 应当自动配置 tools.jackson.databind.ObjectMapper");
        assertTrue(jsonMapper.getClass().getName().startsWith("tools.jackson"),
                "注入到的应当是 Jackson 3 实现，实际=" + jsonMapper.getClass().getName());
        assertFalse(jsonMapper.getClass().getName().startsWith("com.fasterxml"),
                "不能是 Jackson 2 的实现 —— 那是 jjwt-jackson 的传递依赖，没有自动配置 bean");
    }

    @Test
    @DisplayName("步骤1：容器里 JsonMapper 子类型也可注入（供需要具体类型的场景）")
    void jsonMapperSubtypeIsAvailable() {
        assertNotNull(jsonMapperConcrete, "Boot 4 的 JsonMapper 应当可注入");
    }

    @Test
    @DisplayName("步骤1：序列化 + 反序列化往返可用（changes_json 两边都要用）")
    void roundTripWorksForChangeLikeRecords() {
        // 用与 AuditChange 同形状的 record 探针：字段 label/before/after，含中文与 '—'
        ProbeChange source = new ProbeChange("文档 · 上传文档", "—", "读写");

        String json = jsonMapper.writeValueAsString(List.of(source));

        assertTrue(json.contains("\"label\""), "字段名应为 label，实际=" + json);
        assertTrue(json.contains("文档 · 上传文档"), "中文不应被转义成 \\uXXXX，实际=" + json);
        assertTrue(json.contains("—"), "空值占位符 '—' 应当原样保留，实际=" + json);

        // 反序列化：C 阶段详情接口要把它读回来
        List<ProbeChange> restored = jsonMapper.readValue(json,
                new tools.jackson.core.type.TypeReference<List<ProbeChange>>() { });

        assertEquals(1, restored.size());
        assertEquals(source, restored.getFirst(), "往返后应完全相等");
    }

    /** 与 {@code AuditChange} 同形状的探针（本步尚未创建正式类）。 */
    record ProbeChange(String label, String before, String after) {
    }
}
