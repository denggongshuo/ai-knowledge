package com.knowledge.ai.support;

import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.core.security.CaptchaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 端到端测试基类：起真服务、发真 HTTP、读真响应。
 *
 * <h3>为什么不 mock</h3>
 * <p>本项目 S1 的门禁全部落在"真实行为"上：令牌黑名单、并发刷新去重、
 * 登录风控计数、审计落库。这些用 mock 测等于没测 —— 它们的行为**只在真实
 * 并发与真实 Redis 下才成立**。因此统一用
 * {@code @SpringBootTest(RANDOM_PORT)} + {@link RestClient}。
 *
 * <h3>为什么要有这个基类</h3>
 * <p>S1 有 {@code AuthFlowTest}（认证）与 {@code UserFlowTest}（个人信息）两个测试类，
 * 它们要的是同一套能力：发请求、读 {@code {code,message,data,traceId}}、造/删测试账号。
 * 每个类各抄一份的结果是**两份会各自演化的 HTTP 工具**，
 * 而测试工具的漂移比业务代码的漂移更隐蔽（它让断言悄悄测错东西）。
 *
 * <h3>两条测试纪律（踩过才写下来）</h3>
 * <ol>
 *   <li><b>账号隔离</b>：登录风控按账号计数。多个用例共用 {@code admin} 试错密码的话，
 *       先跑的把账号锁掉、后面的全部假失败。凡是制造失败计数的用例一律自带账号名。</li>
 *   <li><b>风控用例必须自清 Redis 键</b>：计数与锁定键 TTL 是 10 分钟，
 *       不清的话同一小时内重跑就会直接命中 {@code LOGIN_LOCKED}，
 *       表现为"改一行无关代码就变红"。</li>
 * </ol>
 *
 * <p>测试用例自己造的账号统一登记到 {@link #createdUsers}，由
 * {@link #removeCreatedUsers()} 在每个用例结束后删除 —— 即便用例中途断言失败也会清。
 * 这比让每个用例写 {@code finally { deleteUser(...) }} 更可靠：漏写一次就会留下
 * 一个占用用户名/手机号的垃圾账号，让下一次运行以"用户名已存在"这种莫名其妙的方式失败。
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiTestSupport {

    /** `T-O10` 的对象兜底用（`sweepOrphanMinioObjects`）。 */
    @Autowired
    protected io.minio.MinioClient minioClient;

    /** 同上。从配置读，与生产同一个桶。 */
    @org.springframework.beans.factory.annotation.Value("${minio.bucket:rag-documents}")
    protected String minioBucket;


    /** 业务错误码：成功。所有断言都用它做基准，避免各处硬编码 0。 */
    protected static final int CODE_OK = 0;

    /** 种子管理员（V2 插入），明文密码与前端 mock 的演示口令一致。 */
    protected static final String ADMIN = "admin";
    protected static final String ADMIN_PASSWORD = "Admin@123";
    protected static final String ADMIN_PHONE = "13800000001";
    protected static final String ADMIN_EMAIL = "admin@corp.com";

    /** 注册 / 重置用到的默认明文密码（含大小写与数字，满足强度策略）。 */
    protected static final String DEFAULT_PASSWORD = "Init@12345";

    /**
     * 伪代码短信验证码：S1 不发真实短信，校验值固定。
     *
     * <p>之所以能在测试里直接用它而**不必先调 {@code /auth/sms-code}}：
     * {@code SmsCodeService#verify} 在 Redis 里找不到码时会退回与固定码比对。
     * 这条放宽是刻意的（开发环境跳过"等短信"），生产换随机码时删掉即可。
     * 顺带的好处是测试**不消耗发码限流额度**（同目标 1 小时 ≤5 次）。
     */
    protected static final String FIXED_SMS_CODE = "000000";

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    /** 直接向验证码仓库要一张"知道答案"的图，省掉测试里的看图识码。 */
    @Autowired
    protected CaptchaStore captchaStore;

    /** 不抛异常的 HTTP 客户端：{@link #call} 用 {@code exchange} 拿原始状态码。 */
    protected final RestClient client = RestClient.create();

    /** 本用例创建过的账号（用户名），由 {@link #removeCreatedUsers()} 统一清理。 */
    private final List<String> createdUsers = new ArrayList<>();

    // ==================================================================
    // 清理
    // ==================================================================

    /** 兜底清理：用例失败也会执行，避免残留账号污染后续运行。 */
    @AfterEach
    void removeCreatedUsers() {
        if (!createdUsers.isEmpty()) {
            // 一次清完（原先是逐个账号跑两三条 SQL）
            ProbeCleanup.cleanUsers(jdbc, List.copyOf(createdUsers));
        }
        createdUsers.clear();
    }

    /**
     * 本次用例开始前 `sys_login_log` 的最大 id —— 收尾把本用例造的登录日志删干净。
     *
     * <h3>为什么登录日志要清（`#98`）</h3>
     * <p>实测：一轮全量 **+242 行**，而**测试侧此前没有任何一个类删过它**。
     * 它们在库里长得和真实登录一模一样（这正是 append-only 表的本意），
     * 于是"清不掉"与"不该清"很容易被混为一谈。判据只有一条：
     * **这些行是测试跑出来的，不是用户登出来的** —— 而水位线是唯一能区分这两者的东西
     * （探针账号的由 {@link ProbeCleanup#cleanUsers} 点名清；
     * 而 `admin` 这类**种子账号**不会被删，只能靠水位线）。
     *
     * <h3>⚠️ 为什么是水位线而不是"按 username 清"</h3>
     * <p>种子账号（`admin`）的登录日志按 username 清就等于把**种子的**日志也删了，
     * 而测试恰恰要断言"登录会留痕"。水位线只删**本次用例之后新增的**，
     * 两种需求同时成立。
     */
    private long loginLogFloor;

    @BeforeEach
    void markLoginLogFloor() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_login_log", Long.class);
        loginLogFloor = max == null ? 0L : max;
    }

    /**
     * 收尾：删掉本用例期间新增的登录日志。
     *
     * <p>⚠️ 与 {@link #removeCreatedUsers} 一样放在 `@AfterEach` ——
     * 用例**失败时更要清**，否则一次红会污染后面所有对"日志条数"敏感的用例。
     */
    @AfterEach
    void removeLoginLogsCreatedByThisTest() {
        jdbc.update("delete from sys_login_log where id > ?", loginLogFloor);
    }

    /**
     * 收尾兜底：扫掉**无主**的 kb 族产物（`kb_id` 指向一个已经不存在的库）。
     *
     * <h3>这一条是 `#130` 的解药</h3>
     * <p>"每个用例类自己清干净"已经被证明会漏 —— 十来个类各写一份 `cleanProbeData`，
     * 而其中漏掉的那张表（`sys_async_task`）一轮就留 **76 行**，且因为 `sys_kb` 先被删，
     * 那些行在库里**谁也看不见**。兜底换了个问法：**不问"你清了没有"，
     * 只问"库里还剩不剩无主的东西"** —— 于是漏一处的代价从"永久累积"变成"多一次 DELETE"。
     *
     * <p>⚠️ 顺序：JUnit 先跑**子类**的 `@AfterEach`（各用例类自己的清理，会把 `sys_kb` 删掉），
     * 再跑本类的 —— 所以到这里，那些没被清掉的产物正好**变成了无主**，一次扫干净。
     */
    @AfterEach
    void sweepOrphanKbArtifacts() {
        ProbeCleanup.sweepOrphanKbArtifacts(jdbc);
        // `T-616`：**三个坐标全空**的终态任务行 —— 上面那条（按 kb_id）与 `cleanUsers`
        //（按 owner_id）结构上都够不着它们（定时任务的 `TaskSubject.none()`）。
        // ⚠️ 必须排在通知兜底**之前**：清掉任务行后，它们产的**广播通知**才变成无主、
        //    被 `sweepOrphanNotices` 同批扫走。
        ProbeCleanup.sweepOrphanSystemTasks(jdbc);
        // `T-905` 的用量表是**第三种形态的盲区**：它一个归属列都没有，
        // 上面那套"无主产物"问法（扫 kb_id 悬空）与覆盖率闸（扫带归属列的表）**都够不着它**。
        // 唯一可用且有意义的维度是**日期**（它是按天聚合的）。理由与代价写在
        // `ProbeCleanup#sweepProbeUsage` 的 javadoc 里，台账在 `ProbeArtifacts.NO_OWNERSHIP_COLUMN`。
        ProbeCleanup.sweepProbeUsage(jdbc);
        // `T-A04`：两张**预计算汇总表**（检索质量 / 使用统计）。它们是**缓存**——
        // 源是 `sys_retrieval_trace`，整张删掉也能重算 ⇒ 按日期一把清没有信息损失。
        // ⚠️ 它们**有** `kb_id`/`user_id` 列（所以覆盖率闸会扫到），但那两列是**分组维度**
        // 且有哨兵 0，按它们删会漏掉大半 —— 理由写在 `ProbeCleanup#sweepProbePrecompute`。
        ProbeCleanup.sweepProbePrecompute(jdbc);
        // `T-O10`：**对象那一侧的兜底**（此前只有"每个类各自删自己造的那些"）。
        // 与上面两条**同一个问法**、同一处调用 —— 分开放会各自演化。
        // ⚠️ 本调用用**默认时间下限**（盖过"先落桶、后落行"那个窗口），
        //    否则它会把**正在上传的对象删掉** —— 用防残留的机制制造数据丢失。
        int orphanObjects = ProbeCleanup.sweepOrphanMinioObjects(
                minioClient, minioBucket, jdbc, ProbeCleanup.DEFAULT_MIN_AGE);
        if (orphanObjects > 0) {
            // **> 0 是信号，不是正常现象** —— 说明有类漏清了（与 kb 族那条同源）
            log.warn("[探针兜底] 删掉 {} 个无主 MinIO 对象 —— 有类漏清了，请补它的收尾", orphanObjects);
        }
        // `#210`：**打点明细的兜底**。它与上面三条的处境不同 —— 那张表**两个归属列都没有**
        // （无 `user_id`、无 `kb_id`），成了孤儿就**没有任何维度认领得了**，
        // 所以只能问"主行还在不在"这个**与归属无关**的观测点。
        // ⚠️ 它抓的是"用例收尾的两条 DELETE 夹住了异步打点提交"那个竞态（难复现，实测单独跑 3 轮均为 0），
        //    理由与"为什么不会误删在途数据"写在 `ProbeCleanup#sweepOrphanTraceChunks` 的 javadoc 里。
        int orphanTraceChunks = ProbeCleanup.sweepOrphanTraceChunks(jdbc);
        if (orphanTraceChunks > 0) {
            log.warn("[探针兜底] 删掉 {} 行孤儿打点明细 —— 清理与异步写库的窗口撞上了（#210）",
                    orphanTraceChunks);
        }
        // `T-B03`（本批）：**通知的兜底**。这一条是**本批新造出来的需求** ——
        // `NoticePublisher` 接线之后，凡是跑完异步任务的用例都会产通知行（此前一行都不产），
        // 所以"谁清通知"必须同批回答，否则就是`#130` 那个形状的第 N 次复现。
        // ⚠️ 它**必须排在 kb 族兜底之后**：那条把任务行/用户行删掉，通知才**变成**无主。
        //    顺序反了会漏一轮（这一轮的残留要等下一轮才被扫）。
        int orphanNotices = ProbeCleanup.sweepOrphanNotices(jdbc);
        if (orphanNotices > 0) {
            // ⚠️ 这条**不是告警级** —— 它 >0 通常只是"任务行先被删了"，属于**预期顺序**。
            //    与上面几条不同，别把它当成"有类漏清了"的信号（见 `sweepOrphanNotices` 的 javadoc）。
            log.info("[探针兜底] 清掉 {} 行无主通知（所含已读/设置行同批清了）", orphanNotices);
        }
        // `T-A00`（本批）：**未命中池的兜底**。与通知同一条来历 —— `MissSink` 接线之后，
        // 凡是走真 HTTP 提问且被判未命中的用例都会往池子里写，而那是**旁路**写的
        // （不在任何用例的清理代码里）。⚠️ 同样排在后面：`cleanUsers` 先删人，行才变无主。
        int orphanMisses = ProbeCleanup.sweepOrphanMisses(jdbc);
        if (orphanMisses > 0) {
            log.info("[探针兜底] 清掉 {} 行无主未命中问题（最近提问人已删）", orphanMisses);
        }
    }

    /**
     * 删掉一个测试账号及其全部产物。
     *
     * <p>⚠️ `S5` 起委托给 {@link ProbeCleanup} —— 原先这里只有两行
     * （`sys_user_role` + `sys_user`），比它该删的**少三张表**。
     * 其中最要命的是 `sys_login_log`：**测试侧一个类都没删过**，
     * 实测每轮全量留 **+242 行**（`#98`），而它们的 `user_id` 指向已删账号 ⇒ 全是孤儿。
     */
    protected void deleteUser(String username) {
        ProbeCleanup.cleanUsers(jdbc, List.of(username));
    }

    /** 登记一个待清理账号（一般情况下用 {@link #createUser} 就够了）。 */
    protected void trackUser(String username) {
        createdUsers.add(username);
    }

    /** 清 Redis 键。风控计数器 / 宽限窗口用后即焚，避免污染后续用例与同一小时内的重跑。 */
    protected void cleanupRedisKeys(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        redis.delete(List.of(keys));
    }

    // ==================================================================
    // HTTP
    // ==================================================================

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * 发一次请求并返回原始响应（**不抛异常**）。
     *
     * <p>用 {@code exchange} 而不是 {@code retrieve().body()}：后者遇到 4xx/5xx
     * 会抛 {@code HttpClientErrorException}，而本测试恰恰有多个用例要断言 401，
     * 用异常表达会让"断言状态码"变成"断言异常类型"，读起来更绕。
     *
     * @param accessToken 需要鉴权的请求传入；为 {@code null} 时不带 {@code Authorization}
     */
    protected RawResponse call(String method, String path, Object body, String accessToken) {
        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method))
                .uri(url(path))
                .contentType(MediaType.APPLICATION_JSON);
        if (accessToken != null && !accessToken.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        RestClient.RequestHeadersSpec<?> headers = (body == null) ? spec : spec.body(body);
        return headers.exchange((request, response) -> new RawResponse(
                response.getStatusCode().value(),
                response.bodyTo(String.class),
                response.getHeaders().getValuesAsList("Set-Cookie")));
    }

    protected RawResponse call(String method, String path, Object body) {
        return call(method, path, body, null);
    }

    protected RawResponse post(String path, Object body) {
        return call("POST", path, body);
    }

    protected RawResponse post(String path, Object body, String accessToken) {
        return call("POST", path, body, accessToken);
    }

    protected RawResponse put(String path, Object body, String accessToken) {
        return call("PUT", path, body, accessToken);
    }

    protected RawResponse get(String path) {
        return call("GET", path, null);
    }

    protected RawResponse get(String path, String accessToken) {
        return call("GET", path, null, accessToken);
    }

    /**
     * 发一次 GET，**路径里的百分号编码原样保留**（`S4-E2` 新增）。
     *
     * <p>为什么 {@link #get(String, String)} 不够用：它走
     * {@code RestClient.uri(String)}，而那是个 **URI 模板** —— 模板里的 {@code %}
     * 会被再编码一次（{@code %23} → {@code %2523}），服务端解码后拿到的是字面量
     * {@code %23}。凡是查询串里**必须**带 {@code #}（如审计的
     * {@code keyword=文档%23123}）的用例都会因此静默拿不到结果。
     *
     * <p>而 {@code #} 不编码也不行 —— 它是 URL 的 fragment 分隔符。
     * 两头都不能省，所以只能走 {@code URI.create} + {@code uri(URI)}：
     * 前者不做编码、后者不做模板展开。
     *
     * <p>这**不是**为测试造的偏门路径：前端的 axios 用 params 传参，
     * {@code #} 正是被编成 {@code %23} 发出去的。
     *
     * <p>⚠️ <b>调用方必须传入**完整**百分号编码的查询串</b>（含非 ASCII —— 用
     * {@code URLEncoder}）。只编 {@code #} 而把中文原样留着，Tomcat 会在请求行
     * 校验阶段直接 400，响应体是一段 HTML，症状是"取 {@code $['code']} 失败"，
     * 看着像接口坏了。
     */
    protected RawResponse getEncoded(String pathWithEncodedQuery, String accessToken) {
        RestClient.RequestHeadersSpec<?> spec = client.method(HttpMethod.GET)
                .uri(java.net.URI.create(url(pathWithEncodedQuery)));
        if (accessToken != null && !accessToken.isBlank()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return spec.exchange((request, response) -> new RawResponse(
                response.getStatusCode().value(),
                response.bodyTo(String.class),
                response.getHeaders().getValuesAsList("Set-Cookie")));
    }

    /**
     * 发一次 {@code multipart/form-data} 请求（头像上传这类接口用）。
     *
     * <p>必须手工构造 {@link MultipartBodyBuilder} —— 直接塞 {@code byte[]} 进去
     * 会被按 JSON 序列化，服务端拿不到 {@code file} part，报的是含混的 400，
     * 看起来像"接口写错了"，实际是测试把请求拼错了。
     */
    protected RawResponse postMultipart(String path, byte[] content, String filename,
                                        String contentType, String accessToken) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.parseMediaType(contentType));

        RestClient.RequestBodySpec spec = client.post().uri(url(path))
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (accessToken != null && !accessToken.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return spec.body(builder.build()).exchange((request, response) -> new RawResponse(
                response.getStatusCode().value(),
                response.bodyTo(String.class),
                response.getHeaders().getValuesAsList("Set-Cookie")));
    }

    /** 原始响应：状态码 + 响应体 + Set-Cookie 头。 */
    protected record RawResponse(int status, String body, List<String> setCookies) {

        /** 业务码（{@code code} 字段）。 */
        public int code() {
            return JsonPath.read(body, "$.code");
        }

        /** 业务提示文案。 */
        public String message() {
            return JsonPath.read(body, "$.message");
        }

        /** 链路标识（G0-2 要求每个响应都带）。 */
        public String traceId() {
            return JsonPath.read(body, "$.traceId");
        }

        /**
         * 读 {@code data} 下的字段。
         *
         * @param path 以 {@code $} 开头的 JSONPath 片段，如 {@code $.accessToken}、{@code $.roles[0].code}
         */
        public <T> T data(String path) {
            // 用 "$.data" + path.substring(1) 而不是 "$.data" + path：
            // 后者会拼出 "$.data$.accessToken"，JsonPath 把中间那段当成属性名 "data$"，
            // 报 "Missing property in path $['data$']" —— 看起来像响应体缺 data，
            // 实际是表达式拼错，排查方向会被带偏。
            return JsonPath.read(body, "$.data" + path.substring(1));
        }

        /** 断言业务成功并返回自身，让"连续几步都应成功"的用例读起来是一条链。 */
        public RawResponse assertOk(String step) {
            assertEquals(CODE_OK, code(), () -> step + " 应成功，实际响应：" + body);
            return this;
        }
    }

    // ==================================================================
    // 账号夹具
    // ==================================================================

    /** 一个用例自己造出来的账号：登录后拿到的令牌一并在内，省掉二次登录。 */
    protected record TestUser(String username, String password, String phone,
                              String accessToken, String refreshToken) {
    }

    /** 密码登录。 */
    protected RawResponse login(String account, String password) {
        return post("/api/auth/login", Map.of("account", account, "password", password, "remember", true));
    }

    /** 用种子管理员登录，供需要令牌的用例复用。 */
    protected RawResponse loginAsAdmin() {
        RawResponse response = login(ADMIN, ADMIN_PASSWORD);
        assertEquals(CODE_OK, response.code(), "种子管理员应能登录，实际：" + response.body());
        return response;
    }

    /**
     * 造一个可登录的普通用户（注册 + 登录），并登记到清理列表。
     *
     * @param prefix 用例前缀，便于在日志 / 残留数据里认出是谁造的
     */
    protected TestUser createUser(String prefix) {
        String username = prefix + "." + System.currentTimeMillis() + "." + Math.floorMod(System.nanoTime(), 1000);
        String phone = uniquePhone();

        post("/api/auth/register", Map.of(
                "username", username,
                "phone", phone,
                "smsCode", FIXED_SMS_CODE,
                "password", DEFAULT_PASSWORD,
                "realName", "测试用户")).assertOk("注册测试用户");

        trackUser(username);

        RawResponse logged = login(username, DEFAULT_PASSWORD);
        assertEquals(CODE_OK, logged.code(), () -> "新注册用户应能登录，实际：" + logged.body());
        return new TestUser(username, DEFAULT_PASSWORD, phone,
                logged.data("$.accessToken"), logged.data("$.refreshToken"));
    }

    /** 生成一个本次运行内唯一的手机号（11 位，符合 {@code ^1[3-9]\d{9}$}）。 */
    protected static String uniquePhone() {
        return "139" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L));
    }

    /** 手机号脱敏的期望值，与 {@code UserPrincipalAssembler.maskPhone} 同一口径。 */
    protected static String masked(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
