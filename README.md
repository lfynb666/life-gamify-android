# Life Gamification 安卓客户端

完整用户端应用，WebView加载gamify页面 + 原生银行短信监听。

## 架构

- **主界面**：全屏WebView，直接加载服务端的gamify页面（任务、商店、统计、时间线、互动、成就、财务、收入）
- **登录认证**：用户在WebView中登录，JWT持久化在本地，原生层共享同一个token
- **短信监听**：后台BroadcastReceiver监听银行动账短信，自动通过已有的 `/api/finance/sms` 接口上报
- **AI解析**：服务端用百炼qwen-turbo解析任意格式银行短信，不依赖正则
- **JS Bridge**：网页可通过 `window.NativeBridge` 调用原生能力（短信状态、权限请求等）

## 支持的银行

自动检测，不限银行。短信包含"人民币"、"余额"、"交易"等关键词即触发。

已知发送号码：
- 95599（农业银行）
- 95588（工商银行）
- 95533（建设银行）
- 95566（中国银行）
- 95555（招商银行）

## 构建

推送到GitHub后由CI/CD自动构建。也可本地构建：

```bash
cd android-sms-reporter
./gradlew assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. 安装APK
2. 授予短信权限和通知权限
3. 在APP内登录账号（与网页端相同）
4. 登录后自动进入任务中心，所有gamify功能可用
5. 银行短信自动在后台监听并上报

## JS Bridge接口

网页端可调用：
- `NativeBridge.getAuthToken()` - 获取本地存储的JWT
- `NativeBridge.setAuthToken(token)` - 保存JWT到本地
- `NativeBridge.getSmsStats()` - 获取短信监听统计
- `NativeBridge.hasSmsPermission()` - 检查短信权限
- `NativeBridge.requestSmsPermission()` - 请求短信权限
- `NativeBridge.getPlatform()` - 返回 "android"
- `NativeBridge.showToast(msg)` - 显示原生提示

## 注意事项

- 部分手机需要加入"电池优化白名单"
- MIUI/ColorOS等需要额外开启"自启动"权限
- 首次安装需手动打开APP授权一次
