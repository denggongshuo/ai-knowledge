package com.knowledge.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **探针产物覆盖率闸**（`#130`）—— 漏登记一张表，这里当场点名。
 *
 * <h3>它防的是哪一种漏</h3>
 * <p>不是"删得不对"，而是"**根本没人想到这张表**"。本仓为此付过四次学费：
 * `sys_chunk` 表 → Milvus 向量 → 按代分区 → `sys_async_task`。
 * 每一次的形态都一样：**新增了持久化产物，而清理函数不认识它**。
 * 光靠注释提醒一定会漏 —— `<b>光靠注释提醒一定会漏</b>` 这句话是 `S4-D2` 写下的，
 * 而 `#130` 又在**用例侧**复现了一遍。
 *
 * <h3>⚠️ 闸自身必须防"空扫描"</h3>
 * <p>一道扫不到任何表的闸会**恒通过** —— 那正是它要防的那种假绿。
 * 所以除了"扫到的都在登记表里"，还要断言"**扫得到东西**"（见
 * {@link #scanIsNotEmptyAndSeesTheKnownTables}）。
 * 这与脚本侧 `#136` 的教训是同一条：**「闸说干净」与「真的干净」是两件事**。
 */
class ProbeArtifactCoverageTest extends ApiTestSupport {

    /**
     * 归属列的匹配模式 —— 与脚本侧 `SCOPE_PATTERNS` 保持一致（那边在 `probe-teardown.mjs`）。
     *
     * <h3>🔴 `T-O15`（2026-09-20）：从**精确相等**改成**包含**</h3>
     * <p>原来扫的是 `column_name in ('kb_id','doc_id','user_id')` —— 精确匹配。
     * 于是**换个列名就能从闸下面走过去**：`target_user_id` / `last_user_id` /
     * `owner_user_id` 都带归属语义，却一个都不在扫描集里。
     * `V31` 的头注释点名警告过这个形状（「叫 `target_user_id` 就能躲开覆盖率闸」），
     * 而 `S8-M10-A` 的 `sys_qa_miss` 又正是一个**真的需要这么命名**的例子
     * （它是聚合行，`last_user_id` = 最近提问人，叫 `user_id` 是用名字撒谎）——
     * ⇒ 与其每次都靠"记得手工登记"，不如让闸自己长眼睛。
     *
     * <p>⚠️ 放宽的代价是**可能多扫到不相干的表**（误报）。实测当时全库只多出
     * `sys_qa_miss`（已登记）与 `sys_retrieval_trace.kb_ids`（本就登记过），
     * 没有误报。真出现误报时**登记或豁免它**，别把模式改回精确匹配。
     */
    private static final List<String> SCOPE_PATTERNS = List.of("%kb_id%", "%doc_id%", "%user_id%");

    /** 扫出"带归属列"的表。这**就是**闸的输入 —— 权威来源是数据库，不是谁的记忆。 */
    private Set<String> scannedTables() {
        String where = SCOPE_PATTERNS.stream()
                .map(p -> "column_name like ?")
                .collect(java.util.stream.Collectors.joining(" or "));
        List<String> names = jdbc.queryForList(
                "select distinct table_name from information_schema.columns "
                        + "where table_schema = database() and (" + where + ") "
                        + "order by table_name",
                String.class, SCOPE_PATTERNS.toArray());
        return new LinkedHashSet<>(names);
    }

    @Test
    @DisplayName("凡带 kb_id/doc_id/user_id 的表，必须已登记或已豁免（漏一张就点名）")
    void everyAttributableTableIsRegistered() {
        Set<String> scanned = scannedTables();
        Set<String> known = new LinkedHashSet<>(ProbeArtifacts.REGISTERED.keySet());
        known.addAll(ProbeArtifacts.EXEMPT.keySet());

        Set<String> unknown = new LinkedHashSet<>(scanned);
        unknown.removeAll(known);

        assertThat(unknown)
                .as("这些表带归属列但**没人处理** —— 到 ProbeArtifacts.REGISTERED 加一行（写明怎么清）；"
                        + "若它本就不该被清，加到 EXEMPT 并**写明理由**。"
                        + "⚠️ 上一次漏的是 sys_async_task，实测代价是 76 行/轮的无主残留")
                .isEmpty();
    }

    @Test
    @DisplayName("T-O15：换个列名也躲不开闸（`target_user_id` / `last_user_id` 都要被扫到）")
    void renamedOwnershipColumnIsStillCaught() {
        // ① 模式本身不能被悄悄窄回去 —— 否则这条闸又退回"精确匹配"，
        //    而"谁把模式改了"没有任何现象
        assertThat(SCOPE_PATTERNS)
                .as("归属列的匹配模式被改了。若是有意收窄，请同时说明为什么"
                        + "「换个列名就躲开」这件事不再要紧")
                .containsExactly("%kb_id%", "%doc_id%", "%user_id%");

        // ② 用**真库真 LIKE 语义**验一遍几个改名写法。
        //    ⚠️ 这里刻意不建临时表去端到端验：MySQL 的 DDL 是隐式提交的，
        //    建表会**污染同一次跑批里别的类**（覆盖率闸那一瞬间会看到一张未登记的表）。
        //    跑在真 SQL 引擎上的 LIKE 语义 + 上面那条模式断言，两条合起来足够。
        assertThat(matchesScope("target_user_id")).as("V31 头注点名的那个躲法").isTrue();
        assertThat(matchesScope("last_user_id")).as("sys_qa_miss 的列（T-O15 的由来）").isTrue();
        assertThat(matchesScope("owner_user_id")).isTrue();
        assertThat(matchesScope("kb_ids")).as("sys_retrieval_trace 的逗号串列").isTrue();
        assertThat(matchesScope("operator_id")).as("只是长得像，别把无关列也拉进来").isFalse();
    }

    /** 用真库的 `LIKE` 判一个列名会不会被闸扫到（模式语义由 DB 决定，不由我复述）。 */
    private boolean matchesScope(String columnName) {
        String where = SCOPE_PATTERNS.stream()
                .map(p -> "? like ?")
                .collect(java.util.stream.Collectors.joining(" or "));
        Object[] args = SCOPE_PATTERNS.stream()
                .flatMap(p -> java.util.stream.Stream.of(columnName, p))
                .toArray();
        return Boolean.TRUE.equals(jdbc.queryForObject("select (" + where + ")", Boolean.class, args));
    }

    @Test
    @DisplayName("🔴 盲区台账非空，且台账里每一张都真的登记过（否则盲区=免检区）")
    void blindSpotsAreStillAccountedFor() {
        // 本闸的输入是"带归属列的表"，所以**没有归属列**的表它结构上看不见。
        // 那类表只能靠一本**显式台账**兜住 —— 台账里的每一张都必须已在 REGISTERED 里
        // 且写明怎么清。缺了这条，"闸看不见"就会悄悄退化成"没人管"。
        assertThat(ProbeArtifacts.NO_OWNERSHIP_COLUMN)
                .as("盲区台账不该为空 —— 空的台账意味着要么真的没有（那很好），"
                        + "要么是有人把 `sys_model_usage` 这类表从台账里删掉了而没想清楚。"
                        + "本仓已知至少有一张（sys_model_usage），故此处断言非空")
                .isNotEmpty();
        assertThat(ProbeArtifacts.REGISTERED.keySet())
                .as("盲区台账里的每一张都必须同时出现在 REGISTERED 里 —— 否则它是个免检区")
                .containsAll(ProbeArtifacts.NO_OWNERSHIP_COLUMN.keySet());
    }

    @Test
    @DisplayName("闸不能是空扫描：扫得到东西，且看得见已知的那几张")
    void scanIsNotEmptyAndSeesTheKnownTables() {
        Set<String> scanned = scannedTables();

        assertThat(scanned)
                .as("一张表都没扫到 —— 闸恒通过，等于没有。先确认 information_schema 查得到本库")
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(scanned)
                .as("闸看不见已知表 —— 说明扫描条件写坏了（记住 #136：恒返回 0 的闸比没有闸更危险）")
                // ⚠️ 刻意**不**断言 `sys_user` / `sys_kb`：那两个自己**没有**归属列
                //（它们是**被指向**的那一方），本来就不该出现在扫描集里
                .contains("sys_async_task", "sys_login_log", "sys_chunk", "sys_document",
                        "sys_parse_issue", "sys_kb_grant");
    }

    @Test
    @DisplayName("登记表里不许留已经不存在/改名的表（防登记表本身腐化）")
    void registeredTablesStillExist() {
        // ⚠️ 全集必须是**库里的真表清单**，不能拿"带归属列的表"（`scanned`）凑数 ——
        // 登记表里有几张表**根本没有归属列**：`sys_kb`/`sys_user` 是**被指向**的那一方，
        // `sys_chat_message` 则是靠 `session_id` 顺上去的。用 `scanned` 当全集会把它们
        // 误判成"库里有张不存在的表"（本批实测踩过）
        Set<String> mustExist = new LinkedHashSet<>(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = database()",
                String.class));

        assertThat(ProbeArtifacts.REGISTERED.keySet())
                .as("登记表里有一张表在库里找不到 —— 要么表改名了（登记表要跟着改），要么拼错了")
                .allSatisfy(table -> assertThat(mustExist)
                        .as("登记的表 %s 在库里不存在", table)
                        .contains(table));
    }

    @Test
    @DisplayName("两侧登记集合对账：Java 与脚本侧 probe-teardown.mjs 的表名取差集应为空")
    void registriesAgreeWithTheScriptSide() throws Exception {
        // ⚠️ 两侧刻意**不合并**（一个 JS 一个 Java，跨语言共用只有"起子进程"或"复制一份"，
        // 都比现在更糟），所以**必须对账** —— 否则两边各自演化，迟早一边登记了一边没登记。
        // 实测价值：本批这条对账当场抓出 `sys_chat_message` 在 Java 侧**清了但没登记**
        // （它没有归属列，覆盖率闸扫不到，只有取差集才看得出来）。
        Path script = Path.of("..", "ai_knowledge_app", "scripts", "lib", "probe-teardown.mjs");
        assertThat(Files.exists(script))
                .as("找不到脚本侧登记表：%s —— 本仓是 monorepo，两边必须能互相看见", script.toAbsolutePath())
                .isTrue();

        String text = Files.readString(script, StandardCharsets.UTF_8);
        // 只取 `KB_ARTIFACTS` / `USER_ARTIFACTS` 两段：`OUT_OF_SCOPE`（显式豁免）不算登记
        int exemptAt = text.indexOf("export const OUT_OF_SCOPE");
        String registryPart = exemptAt > 0 ? text.substring(0, exemptAt) : text;

        Set<String> scriptTables = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("table: '(sys_[a-z_]+)'").matcher(registryPart);
        while (matcher.find()) {
            scriptTables.add(matcher.group(1));
        }
        assertThat(scriptTables)
                .as("从脚本侧一个表名都没解出来 —— 对账会成为空断言（记住 #136：恒通过的闸比没有闸危险）")
                .hasSizeGreaterThanOrEqualTo(10);

        Set<String> diff = new LinkedHashSet<>(scriptTables);
        diff.removeAll(ProbeArtifacts.REGISTERED.keySet());
        diff.removeAll(ProbeArtifacts.EXEMPT.keySet());
        assertThat(diff)
                .as("这些表**脚本侧登记了、Java 侧没有** —— 两边各有一份登记表，漏一边就等于漏一半")
                .isEmpty();

        Set<String> reverse = new LinkedHashSet<>(ProbeArtifacts.REGISTERED.keySet());
        reverse.removeAll(scriptTables);
        assertThat(reverse)
                .as("这些表**Java 侧登记了、脚本侧没有** —— 同上，反过来也是漏")
                .isEmpty();

        // 🔴 `#149`：**上面那两条对账都看不见非表产物**。
        //    它们用的是 `table: 'sys_xxx'` 那个正则，而 MinIO 对象 / Milvus 向量与分区
        //    **结构上不在它的输入里** ⇒ 这一类谁都不管，而闸看起来是绿的。
        //    这是 `#130` 那一族（**闸覆盖了一类产物、另一类不在它的输入里**）的第四次变形。
        //
        //    把它冻住，让"新增一个非表产物"**必须过一次人的判断**：
        //    Java 侧谁来收它？收不了的话，为什么登记？
        //    —— 而不是靠下一个人恰好想起来还有这么一类。
        Set<String> scriptNonTables = new LinkedHashSet<>();
        Matcher nonTable = Pattern.compile("id: '([^']+)', kind: '(?!sql)[^']+'")
                .matcher(registryPart);
        while (nonTable.find()) {
            scriptNonTables.add(nonTable.group(1));
        }
        assertThat(scriptNonTables)
                .as("脚本侧的**非表产物**与冻结列表不一致（解出 %s）。"
                        + "新增或改名时要与 `ProbeArtifacts.NON_TABLE` 一起改，"
                        + "并在那边写清「Java 侧谁来收它」", scriptNonTables)
                .containsExactlyInAnyOrderElementsOf(ProbeArtifacts.NON_TABLE);
    }

    /**
     * `T-O28` 那一族的**第二处**：MinIO 前缀清单**两侧对账**。
     *
     * <h3>为什么这条闸必须有（而不是"记得两边都改"）</h3>
     * <p>MinIO 的归属有两套完全不同的口径，各自有一份清单：
     * <ul>
     *   <li><b>Java 侧</b>{@code ProbeCleanup.MINIO_PREFIXES}（九个）—— 归属是
     *       <b>库里的列</b>或<b>路径里的 userId</b>，兜底扫描用它；</li>
     *   <li><b>脚本侧</b>{@code probe-teardown.mjs} 的 {@code USER_MINIO_PREFIXES} ——
     *       那些**不挂在任何探针库下**、只能按 `{前缀}{userId}/…` 单独收的。</li>
     * </ul>
     * <p>🔴 第二份**没有任何机器检查**，而它已经漏过一次：`S7-M06-E` 新造了
     * `batch-download/` 与 `doc-export/`，脚本侧这两个是**事后补的**
     * （`probe-teardown.mjs` 里那行注释原话：「新前缀当时只加进了 Java 侧的登记表，
     * 脚本侧这个函数漏了 —— 而'脚本侧漏了'的表现是 **E2E 每跑一轮桶里多几个对象**，
     * 且没有任何闸会点名」）。
     * <p>⚠️ 漏了**不报错**：`ls` 一个不存在的目录就是空输出 ⇒ 清理"成功"、对象一个没少。
     * 所以这里两条方向都要对：
     * <ol>
     *   <li><b>脚本侧 → Java</b>：逐条必须被 Java 认识（拼错/改名 ⇒ 脚本侧清理变成空操作）；</li>
     *   <li><b>Java（`Owner.PATH_USER_ID`）→ 脚本侧**：一个都不能漏 ——
     *       那四个前缀的 Java 兜底**只在"账号行已经没了"时才扫得到**，
     *       账号还在的时候它们**只**能靠脚本侧收。</li>
     * </ol>
     */
    @Test
    @DisplayName("MinIO 前缀两侧对账：脚本侧 USER_MINIO_PREFIXES 与 Java 的「路径即归属」逐条相等且无拼错")
    void minioPrefixListsAgreeWithTheScriptSide() throws Exception {
        Path script = Path.of("..", "ai_knowledge_app", "scripts", "lib", "probe-teardown.mjs");
        assertThat(Files.exists(script))
                .as("找不到脚本侧登记表：%s —— 本仓是 monorepo，两边必须能互相看见", script.toAbsolutePath())
                .isTrue();
        String text = Files.readString(script, StandardCharsets.UTF_8);

        Matcher block = Pattern.compile("export const USER_MINIO_PREFIXES = \\[([^\\]]*)\\]")
                .matcher(text);
        assertThat(block.find())
                .as("在 probe-teardown.mjs 里找不到 `USER_MINIO_PREFIXES = [...]` 这一段 —— "
                        + "它改名了？那这条对账会静默失效（恒通过的闸比没有闸危险，见 `#136`）")
                .isTrue();
        Set<String> scriptPrefixes = new LinkedHashSet<>();
        Matcher one = Pattern.compile("'([^']+)'").matcher(block.group(1));
        while (one.find()) {
            scriptPrefixes.add(one.group(1));
        }
        // 防空断言：解析写错时这里会是 0 个，而下面两条 `containsAll` 对空集**恒真**
        assertThat(scriptPrefixes)
                .as("从脚本侧一个前缀都没解出来 —— 这条对账会变成空断言")
                .hasSizeGreaterThanOrEqualTo(3);

        assertThat(ProbeCleanup.coveredMinioPrefixes())
                .as("脚本侧写了 Java 侧不认识的前缀（%s）—— 那种拼错的后果是 `teardownUserProbe` "
                        + "一个对象都删不到，**而且不报错**（`ls` 一个不存在的目录是空输出）",
                        scriptPrefixes)
                .containsAll(scriptPrefixes);

        assertThat(scriptPrefixes)
                .as("这些前缀在 Java 侧是 `Owner.PATH_USER_ID`，脚本侧却没有 ⇒ 用探针账号造出来的"
                        + "那些对象要靠下一轮 `mvn test` 的 Java 兜底才被扫走"
                        + "（`T-O19` 那条「会造行的脚本自己不清」的同形）。Java 侧当前是：%s",
                        ProbeCleanup.pathUserIdPrefixes())
                .containsAll(ProbeCleanup.pathUserIdPrefixes());
    }

    @Test
    @DisplayName("清理器与登记表对得上：按 kb/user 删的那些表名都在登记表里")
    void cleanupTargetsAreRegistered() {
        // 这条防的是"登记表写了、清理器没删"（或反过来）——
        // 两边脱节时，前者给的是**假安全感**，后者给的是**没人知道它在删什么**
        assertThat(List.of("sys_async_task", "sys_parse_issue", "sys_chunk", "sys_document", "sys_kb_grant"))
                .as("按 kb_id 清的表")
                .allSatisfy(t -> assertThat(ProbeArtifacts.REGISTERED).containsKey(t));
        assertThat(List.of("sys_login_log", "sys_user_role", "sys_user",
                        "sys_chat_session", "sys_retrieval_trace", "sys_retrieval_trace_chunk"))
                .as("按 user_id 清的表")
                .allSatisfy(t -> assertThat(ProbeArtifacts.REGISTERED).containsKey(t));
    }
}
