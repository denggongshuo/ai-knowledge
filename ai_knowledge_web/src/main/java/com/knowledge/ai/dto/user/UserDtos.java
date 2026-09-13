package com.knowledge.ai.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 当前用户域出入参（M01 · T-111 / T-112）。
 *
 * <p>字段名以**前端实际消费的字段名**为准（前端零改动是硬约束）：
 * <ul>
 *   <li>{@code ProfileView.vue} 的「基本资料」读 {@code username / realName / phone /
 *       email / department / position / intro / roleNames}；</li>
 *   <li>{@code userApi.loginLogs} 读 {@code list[].{id,method,ip,device,result,time}}；</li>
 *   <li>{@code userApi.permissions} 读 {@code codes}。</li>
 * </ul>
 *
 * <p>{@code GET /user/profile} 直接复用 {@code AuthDtos.UserView}（它是登录响应里
 * 那个用户对象的同一个类型）—— 个人信息页要的字段是它的子集。刻意**不新建一个
 * ProfileView DTO**：同一份"用户视图"出现两个类型，迟早会在某个字段上漂移，
 * 而登录页与个人信息页显示不一致是最难解释的一类缺陷。
 */
public final class UserDtos {

    private UserDtos() {
    }

    // ==================== 入参 ====================

    /**
     * 更新基本资料（{@code PUT /user/profile}，《10》§51）。
     *
     * <p>四个字段都是**可改**的；用户名 / 手机号 / 部门刻意不在这里 ——
     * 用户名创建后不可改（页面输入框是 disabled），手机号要验证码换绑
     * （{@code /user/change-phone}），部门由管理员维护（页面明确标注）。
     *
     * <p>全部允许为空：前端是"整表单提交"，用户只改昵称时其余字段会带原值过来；
     * 若某个字段被传成 {@code null}，语义是"不动它"而不是"清空它"。
     */
    public record UpdateProfileRequest(
            @Size(max = 64, message = "姓名长度需 64 位以内")
            String realName,

            @Email(message = "邮箱格式不正确")
            @Size(max = 128, message = "邮箱长度需 128 位以内")
            String email,

            @Size(max = 64, message = "职位长度需 64 位以内")
            String position,

            @Size(max = 500, message = "个人简介长度需 500 位以内")
            String intro) {
    }

    /**
     * 换绑手机号（{@code POST /user/change-phone}，前端 {@code userApi.changePhone}）。
     *
     * <p><b>必须验码</b> —— 本段此前写的是「S1 接口保留但不做验证码校验，因为
     * {@code SmsScene} 只有 login/register/reset-password 三个值、没有可用于换绑的场景」，
     * 那段说明**已经过期**（2026-09-13 订正）：{@code SmsScene.BIND} 已存在，
     * {@code UserService#changePhone} 也已强制调 {@code smsCodeService.verify}。
     * 至此前端换绑弹窗的「获取验证码」按钮也已接上（原先它是唯一的无校验理由），
     * 发码与校验的 scene 都是 {@code bind}，**目标号码是新手机号**。
     *
     * <p>{@code code} 刻意不加 {@code @NotBlank}：空码与错码对用户是同一件事，
     * 都该由 {@code SmsCodeService#verify} 报 {@code SMS_CODE_INVALID} ——
     * 而不是一个"参数校验失败"（那会把"你没填码"和"码填错了"变成两种提示，
     * 用户看不出区别，却多了一处需要维护的分支）。
     */
    public record ChangePhoneRequest(
            @NotBlank(message = "请输入新手机号")
            String phone,

            String code) {
    }

    /**
     * 换绑邮箱（{@code POST /user/change-email}）。
     *
     * <p>同样**必须验码**，但验证码的目标是**用户当前已绑定的手机号**（不是新邮箱）——
     * v1 没有邮件通道（《04》明确不做邮箱验证），{@code SmsCodeService} 是唯一的验证码设施。
     * 给一个"发不出去的验证码"设计流程，只会得到一个永远走不通的接口。
     * 没绑手机号的账号因此无法自助换邮箱，这个限制是刻意的。
     */
    public record ChangeEmailRequest(
            @NotBlank(message = "请输入新邮箱")
            @Email(message = "邮箱格式不正确")
            String email,

            String code) {
    }

    // ==================== 出参 ====================

    /** 保存基本资料（前端忽略响应体，只判成功与否）。 */
    public record ProfileUpdateResponse(boolean saved) {
    }

    /**
     * 修改密码（前端忽略响应体，成功即本地登出）。
     *
     * <p>{@code reloginRequired} 恒定 {@code true}：改密会撤销该用户全部 refreshToken
     * （G1-8），并把当前 accessToken 拉黑 —— 当前会话必然要重新登录。
     * 保留这个字段是给"将来改成只踢其它设备"留的开关。
     */
    public record ChangePasswordResponse(boolean changed, boolean reloginRequired) {
    }

    /** 换绑手机号结果。 */
    public record ChangePhoneResponse(boolean changed, String phone) {
    }

    /** 换绑邮箱结果。{@code emailVerified} 为 {@code false} 表示还需用户点确认邮件（v1 不发邮件，恒 false）。 */
    public record ChangeEmailResponse(boolean changed, boolean emailVerified) {
    }

    /** 头像上传结果：{@code url} 是可回显的预签名地址。 */
    public record AvatarResponse(String url) {
    }

    /** 我的权限（{@code GET /user/permissions}，《10》§56：扁平权限码数组，多角色取并集）。 */
    public record PermissionsView(List<String> codes) {
    }

    /**
     * 安全概览（{@code GET /user/security}，《10》§57 / §367）。
     *
     * <p>{@code phone} / {@code email} 是**脱敏后**的展示值（{@code 138****0001}）——
     * 这个接口的用途是"让用户确认绑的是哪个号"，给全量值没有额外价值，
     * 却把敏感信息放进了另一个可被读取的响应里。
     */
    public record SecurityView(
            String phone,
            String email,
            boolean mfaEnabled,
            String passwordUpdatedAt,
            List<RecentDeviceView> recentDevices) {
    }

    /**
     * 安全概览里的"最近登录设备"。
     *
     * <p>{@code current} 的判定口径：**取最近一次成功登录的那条为 {@code true}**。
     * 为什么不做成精确的"当前会话"：本项目是无状态鉴权，服务端不维护会话表，
     * 拿不到"哪台设备正在看这个页面"。最近一次成功登录是最接近的近似，
     * 也与前端 mock 的口径一致。
     */
    public record RecentDeviceView(
            String device,
            String ip,
            String location,
            String loggedInAt,
            boolean current) {
    }
}
