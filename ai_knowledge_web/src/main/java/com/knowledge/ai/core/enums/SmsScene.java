package com.knowledge.ai.core.enums;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import lombok.Getter;

/**
 * 短信验证码场景（{@code POST /auth/sms-code} 的 {@code scene}）。
 *
 * <p><b>字段名是 {@code scene}，不是 {@code purpose}</b> —— 这条口径已冻结在《10》§1，
 * 改回会静默丢参数（前端 `api/index.ts` 的 `smsCode()` 传的就是 {@code scene}）。
 *
 * <p>枚举名与线值不同：{@code reset-password} 带连字符，Java 枚举名只能是
 * {@code RESET_PASSWORD}，因此线值单独维护在 {@link #wire}。
 * **不要用 {@code name()} 序列化**（会变成 {@code RESET_PASSWORD}，前端认不出）。
 */
@Getter
public enum SmsScene {

    /** 短信登录（S1 暂未开放，见 {@code SMS_LOGIN_UNSUPPORTED}）。 */
    LOGIN("login"),

    /** 注册。 */
    REGISTER("register"),

    /** 忘记密码。 */
    RESET_PASSWORD("reset-password"),

    /**
     * 换绑手机 / 邮箱（T-111 的 {@code /user/change-phone}、{@code /user/change-email}）。
     *
     * <p><b>这是对《10》§1 的纯新增补充，理由如下</b>：《10》§1 把 {@code scene}
     * 写死为 {@code login / register / reset-password} 三个值，但同一份文档 §2
     * 又要求「换绑手机（需验证码）」「换绑邮箱（需验证码）」——
     * **这两个要求在没有对应 scene 时无法实现**（验证码按 {@code scene + target}
     * 存键，没有 scene 就无处校验，等于不校验）。
     *
     * <p>补法遵循项目既有的「超集、纯新增、可逆」口径（与 {@code AuthDtos} 的
     * 双字段策略同一原则）：新增一个线值 {@code bind}，不动任何既有值，
     * 前端不受影响（它现在根本不发这个 scene）。将来若前端改为直接复用
     * {@code reset-password}，删掉这一项即可，属完全可逆的操作。
     */
    BIND("bind"),
    ;

    /** 对前端暴露的线值。 */
    private final String wire;

    SmsScene(String wire) {
        this.wire = wire;
    }

    /**
     * 由线值解析。
     *
     * @throws BizException {@link ErrorCode#SMS_SCENE_INVALID} 取值不在枚举内
     */
    public static SmsScene parse(String raw) {
        if (raw != null) {
            for (SmsScene scene : values()) {
                if (scene.wire.equalsIgnoreCase(raw) || scene.name().equalsIgnoreCase(raw)) {
                    return scene;
                }
            }
        }
        throw BizException.of(ErrorCode.SMS_SCENE_INVALID);
    }
}
