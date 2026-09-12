import { defineStore } from 'pinia'
import { authApi } from '@/api'

interface AuthUser {
  id: number
  username: string
  realName: string
  phone: string
  department: string
  roles: string[]
  roleNames?: string[]
  permissions?: string[]
  [key: string]: unknown
}

interface LoginResult {
  accessToken: string
  refreshToken: string
  user: AuthUser
  roles: unknown[]
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('ak_token') ?? '',
    user: null as AuthUser | null,
    roles: [] as unknown[],
    remember: localStorage.getItem('ak_remember') === '1',
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    displayName: (s) => s.user?.realName ?? s.user?.username ?? '',
    username: (s) => s.user?.username ?? '',
  },
  actions: {
    setLogin(payload: LoginResult) {
      this.token = payload.accessToken
      this.user = payload.user
      this.roles = payload.roles ?? []
      localStorage.setItem('ak_token', payload.accessToken)
    },
    setRemember(v: boolean) {
      this.remember = v
      localStorage.setItem('ak_remember', v ? '1' : '0')
    },
    logout() {
      this.token = ''
      this.user = null
      this.roles = []
      localStorage.removeItem('ak_token')
    },
    // 登录动作（组合 captcha+login，供页面调用）
    async login(payload: { account: string; password: string; captchaId?: string; captchaCode?: string; remember?: boolean }) {
      const res = await authApi.login(payload)
      this.setLogin(res as unknown as LoginResult)
      return res
    },
    /** 短信验证码登录（走 `/auth/login-by-sms`，与接口约定 §1 对齐） */
    async loginBySms(payload: { phone: string; smsCode: string; remember?: boolean }) {
      const res = await authApi.loginBySms(payload)
      this.setLogin(res as unknown as LoginResult)
      return res
    },
    async logoutRemote() {
      try {
        await authApi.logout()
      } finally {
        this.logout()
      }
    },
  },
})