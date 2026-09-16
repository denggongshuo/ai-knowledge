package com.knowledge.ai.service;

import com.knowledge.ai.entity.SysChunk;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片产出契约（D-5）的**双向一致性**取证。
 *
 * <h3>这条用例挂了三个批次，为什么必须补</h3>
 * <p>D-5 §三 的「给后续批次的硬要求」原文：落 {@code sys_chunk} 时必须补一条
 * 「实体字段集 == 契约字段集」的反射用例。这条要求从 S4-A 说到 S4-B 再到 S4-C
 * （契约文档 §三 · S4-M03-A §10-2 · S4-M05-B §10-2），**一直没落地**。
 *
 * <p>它的价值不在"少写一行就会报错"，而在于：**契约文档是 M07（整个 S5）的实现依据**。
 * 文档里写 `titlePath`、代码里落成 `title_path` 之外的别的名字，照着文档写的人
 * 会在 S5 全线返工 —— 而那时才发现，返工面是整个检索与问答模块。
 *
 * <h3>为什么解析文档，而不是在测试里再抄一份字段清单</h3>
 * <p>抄一份清单只能保证「代码 == 测试里的清单」，**保证不了「代码 == 文档」** ——
 * 而后者才是要防的事。所以这里直接读 D-5 §1.1 的表格，把**文档本身**当事实源。
 *
 * <p>代价是文档结构变了用例会红。这是**有意**的：契约文档的表格结构是稳定的，
 * 它变了本就该有人看一眼（这正是"契约"两个字的含义）。但解析不出来时
 * 报的是"文档结构变了"而不是"字段不匹配"，不会误导排查方向。
 *
 * <h3>与 ddl-auto: validate 的分工</h3>
 * <p>{@code validate} 核的是**实体 ↔ 数据库表**；本用例核的是**实体 ↔ 契约文档**。
 * 两者都绿，才等于"文档说的 = 代码写的 = 库里有的"。
 */
class ChunkContractTest {

    /** 契约文档的候选路径（surefire 的工作目录是模块目录，故根目录的 docs 要往上一级）。 */
    private static final List<Path> CONTRACT_DOC_CANDIDATES = List.of(
            Path.of("..", "docs", "后端开发文档", "10-M05-分片产出契约（D-5）.md"),
            Path.of("docs", "后端开发文档", "10-M05-分片产出契约（D-5）.md"));

    /** 表格行：{@code | `字段名` | ...}，只取第一列。 */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|\\s*`([^`]+)`\\s*\\|.*$");

    /**
     * 契约里的字段名 → 本表的列名。
     *
     * <p><b>唯一一处不一致</b>：契约叫 {@code chunkId}，本表的列叫 {@code id}。
     * 理由写在 D-5 §1.1 ——「Milvus 主键 {@code id} 与之同值」，
     * 即这个 id 同时是行主键与向量主键，两处同一个值，名字却按各自语境取。
     * 其余字段按 camelCase → snake_case 机械换算。
     */
    private static final Map<String, String> FIELD_NAME_ALIASES = Map.of("chunkId", "id");

    /**
     * 审计列 —— 不是"这个块是什么"，而是"这一行是什么时候写的"，故不进契约清单。
     * 白名单写在这里而不是"排除所有非 @Column 字段"，是为了让新增审计列**也必须显式加进来**。
     */
    private static final Set<String> AUDIT_COLUMNS = Set.of("created_at");

    // ==================================================================

    @Test
    @DisplayName("D-5 §1.1：实体字段集 == 契约字段集（双向比对，SE5 返工面的防线）")
    void entityFieldsMatchContractFields() {
        Set<String> contractColumns = normalizeContractFields(readContractFields());
        Set<String> entityColumns = entityBusinessColumns();

        assertThat(contractColumns)
                .as("D-5 §1.1 解析出 0 个字段 —— 多半是契约文档的表格结构变了，"
                        + "不是字段真的没了。先看 §1.1 的表还是不是「| `字段名` | ...|」的形状")
                .isNotEmpty();

        // 正向：实体有的，契约必须写
        Set<String> notInContract = new LinkedHashSet<>(entityColumns);
        notInContract.removeAll(contractColumns);
        assertThat(notInContract)
                .as("实体的这些列在 D-5 §1.1 里没有 —— 加列必须同步改契约文档，"
                        + "否则 M07 照着契约写完后才发现字段名对不上，返工面是整个 S5")
                .isEmpty();

        // 反向：契约写的，实体必须有
        Set<String> missingOnEntity = new LinkedHashSet<>(contractColumns);
        missingOnEntity.removeAll(entityColumns);
        assertThat(missingOnEntity)
                .as("D-5 §1.1 写了这些字段，但 sys_chunk 的实体里没有 —— "
                        + "契约许诺了不存在的东西，比少写更糟：照着它写的人会编译不过")
                .isEmpty();
    }

    @Test
    @DisplayName("D-5 §1.3：入库列名是 chunk_type，不是 chunkType（废弃的第三种写法）")
    void columnNameIsChunkTypeNotChunkType() {
        assertThat(entityColumns())
                .as("D-5 §1.3 明确写了「❌ 废弃的第三种写法 chunkType —— 任何地方都不要用」，"
                        + "而入库列名固定为 chunk_type。落错名字会让 Milvus 的标量字段与库表分叉")
                .contains("chunk_type")
                .doesNotContain("chunkType", "chunkType_");

        assertThat(entityColumns())
                .as("其余字段走 camelCase → snake_case 的机械换算，不该出现驼峰列名")
                .noneMatch(c -> c.chars().anyMatch(Character::isUpperCase));
    }

    @Test
    @DisplayName("D-5 §1.1：空值口径 —— parent_id 可空（顶层块为 NULL）、raw_content 不可空")
    void nullabilityMatchesContract() {
        assertThat(nullable("parentId"))
                .as("D-5 §1.2：顶层块的 parentId 为 null，这是最容易被写成 not null 的一条；"
                        + "而 0 不是哨兵值，不允许用 0 代替 null")
                .isTrue();
        assertThat(nullable("titlePath"))
                .as("标题之前的块（封面段）没有路径")
                .isTrue();
        assertThat(nullable("pageNo"))
                .as("非分页格式（TXT/MD）与 Tika 路径无页号，是格式事实不是缺陷")
                .isTrue();

        assertThat(nullable("rawContent"))
                .as("O8：块存事实。原始内容为空意味着这个块没有存在的理由 —— "
                        + "允许 null 会让空块混进向量库")
                .isFalse();
        assertThat(nullable("docId")).isFalse();
        assertThat(nullable("kbId")).isFalse();
        assertThat(nullable("orderNo")).isFalse();
    }

    @Test
    @DisplayName("O8：raw_content 这一列在契约的 §1.7 里有依据（不是随手加的列）")
    void rawContentIsGroundedInTheViewSeparationClause() throws IOException {
        String doc = Files.readString(contractDoc(), StandardCharsets.UTF_8);

        assertThat(doc)
                .as("O8 的核心是「块存事实，视图是投影」。契约里必须有 §1.7 这一段，"
                        + "否则 raw_content 这列就成了无据可依的字段 —— 下一个人会以为它是冗余的")
                .contains("### 1.7 块与视图分离")
                .contains("rawContent")
                .contains("永不改写");
    }

    // ==================================================================
    // 私有
    // ==================================================================

    /** 定位契约文档；找不到就**失败**（不是跳过 —— `Assumptions` 会静默假绿）。 */
    private static Path contractDoc() throws IOException {
        for (Path candidate : CONTRACT_DOC_CANDIDATES) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("找不到契约文档 D-5，候选路径都试过了：" + CONTRACT_DOC_CANDIDATES
                + "（当前工作目录：" + Path.of(".").toAbsolutePath() + "）");
    }

    /**
     * 从 D-5 §1.1 的表格里抽出字段名。
     *
     * <p>只认「{@code | `xxx` | ...}」形状的行 —— §1.1 的表第一列都是反引号包起来的字段名，
     * 而正文里的比较符号、注释行都不长这样，所以不需要额外的段落边界判断。
     * 上界取 §1.2 的标题行（`### 1.2`），避免把后面的表也扫进来。
     */
    private static List<String> readContractFields() {
        try {
            List<String> lines = Files.readAllLines(contractDoc(), StandardCharsets.UTF_8);
            List<String> fields = new ArrayList<>();
            boolean inside = false;
            for (String line : lines) {
                if (line.startsWith("### 1.1")) {
                    inside = true;
                    continue;
                }
                if (inside && line.startsWith("### 1.2")) {
                    break;
                }
                if (!inside) {
                    continue;
                }
                Matcher m = TABLE_ROW.matcher(line.strip());
                if (m.matches()) {
                    fields.add(m.group(1));
                }
            }
            return fields;
        } catch (IOException ex) {
            throw new IllegalStateException("读契约文档失败：" + ex.getMessage(), ex);
        }
    }

    /** 契约字段名 → 本表列名：先查别名表，否则 camelCase → snake_case。 */
    private static Set<String> normalizeContractFields(List<String> fields) {
        Set<String> out = new LinkedHashSet<>();
        for (String f : fields) {
            out.add(FIELD_NAME_ALIASES.getOrDefault(f, camelToSnake(f)));
        }
        return out;
    }

    private static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 实体上所有 {@code @Column} 的列名（不含审计列）。 */
    private static Set<String> entityBusinessColumns() {
        Set<String> out = new LinkedHashSet<>(entityColumns());
        out.removeAll(AUDIT_COLUMNS);
        return out;
    }

    /**
     * 实体上所有列名（含审计列）。
     *
     * <p>{@code @Id} 主键按 JPA 惯例**不带 {@code @Column}**（列名默认取字段名），
     * 所以这里对没有 {@code @Column} 的字段再放行一种情形：它必须有 {@code @Id}。
     * 其余没注解的字段仍然是错的 —— 那才是"没人管的列"。
     */
    private static Set<String> entityColumns() {
        Set<String> out = new LinkedHashSet<>();
        for (Field field : SysChunk.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                out.add(column.name());
                continue;
            }
            assertThat(field.getAnnotation(Id.class))
                    .as("字段 %s 既没有 @Column 也不是 @Id —— 它的列名是隐式的（按字段名推导），"
                            + "既不会被 ddl-auto: validate 核到，也不会进契约比对，等于一个没人管的列",
                            field.getName())
                    .isNotNull();
            out.add(field.getName());
        }
        return out;
    }

    private static boolean nullable(String fieldName) {
        try {
            Field field = SysChunk.class.getDeclaredField(fieldName);
            Column column = field.getAnnotation(Column.class);
            assertThat(column).as("%s 缺 @Column", fieldName).isNotNull();
            return column.nullable();
        } catch (NoSuchFieldException ex) {
            throw new AssertionError("SysChunk 里没有字段 " + fieldName
                    + " —— 契约改了而实体没跟上，或字段被改名了", ex);
        }
    }
}
