# 托予净化

[简体中文](README.md) | [繁體中文](README.zh-TW.md) | [English](README.en.md)

一个面向小米 / 红米设备的轻量工具，解决两件日常小事：**让屏幕不熄灭**，以及**把系统里的广告清干净**。

不用 root，不用刷机，装好即用。

---

## 目录

- [功能一览](#功能一览)
- [安装](#安装)
- [屏幕控制](#屏幕控制)
- [系统去广告](#系统去广告)
- [GKD 应用内广告](#gkd-应用内广告)
- [权限说明](#权限说明)
- [常见问题](#常见问题)
- [相关项目](#相关项目)
- [自行构建](#自行构建)
- [许可](#许可)

---

## 功能一览

| 功能 | 作用 | 前置条件 |
|---|---|---|
| 屏幕常亮 | 取消自动熄屏，看文档/导航时屏幕不会黑 | Shizuku（或一次 adb 授权） |
| 自动变暗 | 关闭系统的自动降低亮度 | Shizuku（或一次 adb 授权） |
| 本地广告拦截 | 通过本地 VPN 把广告域名解析到 `0.0.0.0` | 授予 VPN 权限 |
| MIUI 推荐关闭 | 关掉负一屏视频流、推荐入口、个性化广告 | Shizuku |
| 完整拦截清单 | 19 万条域名的通用黑名单，可开关 | 无需额外权限 |
| GKD 集成 | 一键安装 GKD，复制订阅链接 | 无 |

---

## 安装

1. 从 [Releases](../../releases) 下载最新版本的 `托予净化-x.y.z.apk`
2. 在手机上点击安装
3. 首次打开会弹出引导，按提示完成 Shizuku 授权

> 如果提示「未知来源应用」，在系统设置里允许本次安装即可。

---

## 屏幕控制

### 前置条件：修改系统设置的权限

「屏幕常亮」和「自动变暗」需要写入系统设置，这要用到 `WRITE_SECURE_SETTINGS` 权限。它是系统级权限，**应用无法自行申请**，只有两种方式授予：

#### 方式一：Shizuku（推荐，不需要电脑）

1. 打开应用，在「屏幕控制」页顶部找到 **Shizuku** 卡片
2. 如果显示「还没安装 Shizuku」，点 **安装 Shizuku**（应用内已内嵌安装包）
3. 装好后打开 Shizuku，按它自己的引导 **启动服务**
   - Android 11 及以上可以用「无线调试」启动，全程不需要电脑
   - 详细步骤见 [Shizuku 官方文档](https://shizuku.rikka.app/guide/setup/)
4. 回到本应用，点 **授权 Shizuku**，在弹窗中允许
5. 授权完成后，应用会在后台自动完成 `WRITE_SECURE_SETTINGS` 的授予，无需任何额外操作

#### 方式二：连接电脑执行一条命令

不想装 Shizuku 的话，用 USB 连接电脑，在终端执行一次：

```bash
adb shell pm grant com.tuoyu.clean android.permission.WRITE_SECURE_SETTINGS
```

执行一次即可，之后不再需要电脑。

> 首次打开应用时，在引导弹窗点「其它方式」可以看到这条命令，并支持一键复制。

### 使用

授权完成后：

- **屏幕常亮** —— 打开开关，屏幕不再自动熄灭；关闭则恢复原来的超时时间
- **自动变暗** —— 打开开关，系统不会在超时前降低亮度
- **屏幕状态刷新** —— 重新读取系统当前的真实设置并刷新界面
- **外观** —— 跟随系统 / 日间 / 夜间，选择会被记住

「当前状态」卡片会实时显示系统真实的熄屏时间，例如「永久 / 不会自动熄灭」或「1 分钟 / 到达此时间后屏幕自动熄灭」。

### 桌面快捷方式

长按桌面上的应用图标，可以直接操作三项功能，不会打开应用界面：

- 关闭屏幕常亮 / 开启屏幕常亮
- 关闭自动变暗 / 开启自动变暗
- 关闭系统广告 / 开启广告拦截

---

## 系统去广告

分三层，覆盖不同类型的广告。

### 第一层：本地 DNS 过滤（网络层）

原理是应用内启动一个本地 VPN，只接管 DNS 查询：

```
系统发出 DNS 查询
  ├─ 命中黑名单  → 直接返回 0.0.0.0，广告加载失败
  └─ 未命中      → 转发给 223.5.5.5，正常上网
```

**只路由 DNS，真实流量不经过 VPN**，所以不影响网速。

#### 使用

1. 打开「去广告」页
2. 打开 **一键关闭系统广告** 开关
3. 首次会弹出系统的 VPN 授权请求，点允许

开启后状态栏会出现钥匙图标（VPN 已连接），这是正常的。

#### 拦截清单

- **精选清单**（始终生效）：逐条在真机上验证过的小米广告域名，零误伤
- **完整拦截清单**（可开关）：约 19 万条域名，融合 [StevenBlack/hosts](https://github.com/StevenBlack/hosts) 与 [GOODBYEADS](https://github.com/8680/GOODBYEADS)，覆盖国内外广告联盟

完整清单以实验性功能提供，因为大列表难免混入过宽规则。内置白名单保护了应用商店下载 CDN、账号、云服务、推送等关键域名。

**如果某个网站或应用突然打不开**，先关掉「完整拦截清单」试试；确认是拦截导致的，把域名反馈给我加进白名单。

#### 验证效果

点 **验证拦截效果**，应用会实际去解析清单里每个域名，并列出结果：

```
已拦截 14 / 14 个域名
[x] api.ad.xiaomi.com      小米广告投放接口
[x] tracking.miui.com      行为追踪上报
...
正常网站解析正常，上网不受影响。
```

### 第二层：MIUI 推荐开关（设置层）

关闭这些设置项，属于 MIUI / HyperOS 专属：

| 设置项 | 效果 |
|---|---|
| 负一屏视频推荐流 | 负一屏不再推送视频 |
| 负一屏视频标签页 | 隐藏视频 tab |
| 负一屏推荐入口 | 隐藏推荐入口 |
| 个性化广告追踪 | 关闭精准投放 |

在「去广告」页打开主开关时会一并执行，开关下方会显示「MIUI 推荐开关 5/5」表示全部已关闭。

> 非小米设备上这些设置项不存在，界面会显示「当前系统不适用」。

### 第三层：GKD（界面层）

见下一节。

### 网络层拦不住的广告

有一些广告是**应用自有广告位**，内容由应用自己的接口下发（例如日历、天气里的推荐卡片）。域名拦截对它们无效，因为拦掉接口会连功能一起废掉。

这类广告需要在对应应用的设置里关闭推荐，或用 GKD 在界面上隐藏。应用内也已标注这一点。

---

## GKD 应用内广告

[GKD](https://github.com/gkd-kit/gkd) 是一个开源的广告跳过工具，通过无障碍服务识别并点击/隐藏广告控件，处理开屏广告、弹窗广告、信息流广告。

### 安装

「去广告」页底部有 **GKD 去广告** 区块，应用内已内嵌 GKD 安装包：

1. 点 **一键安装 GKD**
2. 系统会弹出安装器，点继续完成安装
3. 安装后按钮会变成「GKD 已安装（版本号）」

> 如果安装器提示「网络异常」，点「继续」即可 —— 广告拦截同时挡掉了 MIUI 安装器的联网检测。

### 配置

1. 点 **复制订阅链接**，应用会复制官方推荐订阅：
   ```
   https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5
   ```
2. 打开 GKD，进入「订阅」页面，粘贴链接并导入
3. 按 GKD 的引导授予无障碍权限
4. 在订阅里启用需要的规则组

详细用法见 [GKD 官方文档](https://gkd.li/guide/)。

---

## 权限说明

| 权限 | 用途 | 如何授予 |
|---|---|---|
| `WRITE_SECURE_SETTINGS` | 修改屏幕超时、亮度设置 | Shizuku 自动授予，或 adb 命令 |
| `INTERNET` | VPN 转发 DNS 查询 | 安装时自动授予 |
| `BIND_VPN_SERVICE` | 建立本地 DNS 过滤器 | 首次开启拦截时系统弹窗确认 |
| `REQUEST_INSTALL_PACKAGES` | 拉起安装器安装 Shizuku / GKD | 首次安装时系统弹窗确认 |
| `POST_NOTIFICATIONS` | 显示前台服务通知 | 系统弹窗确认 |

本应用**不收集任何数据**，不联网上传任何信息。DNS 查询只转发给你所在网络的默认解析器。

---

## 常见问题

**Q：为什么点了开关没反应？**

看提示文字。现在会明确告诉你卡在哪一步：

| 提示 | 含义 | 怎么办 |
|---|---|---|
| 还没安装 Shizuku | 未检测到 Shizuku | 点「安装 Shizuku」 |
| Shizuku 服务未运行 | 装了但服务没启动 | 打开 Shizuku 点「启动」 |
| 请点上方「授权 Shizuku」 | 服务在跑但没授权本应用 | 点「授权 Shizuku」并允许 |

**Q：开启拦截后网速变慢了吗？**

不会。VPN 只路由 DNS 查询（发往 `10.111.222.2`），网页、视频等真实流量完全不走 VPN。

**Q：和 Clash 之类的代理冲突吗？**

冲突。Android 同时只允许一个 VPN 应用，两者只能二选一。

**Q：为什么日历/天气里还有推荐？**

见上文「网络层拦不住的广告」，这类内容由应用自有接口下发，需要 GKD 或应用内设置关闭。

**Q：完整拦截清单会不会误伤正常网站？**

有可能，这也是它做成可开关的原因。清单里已内置白名单保护常见服务，并从清单中剔除了 `baidu.com`、`taobao.com`、`youtube.com` 这类过宽规则。遇到问题先关开关验证，再反馈。

**Q：卸载后设置会恢复吗？**

屏幕超时会恢复成你开启常亮前的值（应用第一次修改时会记录原值）。DNS 拦截在关闭开关或卸载后立即失效，不需要额外操作。

---

## 相关项目

本项目依赖或集成了以下开源项目，感谢它们的作者：

| 项目 | 作用 | 链接 |
|---|---|---|
| Shizuku | 提供 shell 权限，用于修改系统设置 | [GitHub](https://github.com/RikkaApps/Shizuku) · [官网](https://shizuku.rikka.app/) |
| GKD | 通过无障碍服务跳过应用内广告 | [GitHub](https://github.com/gkd-kit/gkd) · [官网](https://gkd.li/) |
| StevenBlack/hosts | 通用广告域名清单 | [GitHub](https://github.com/StevenBlack/hosts) |
| GOODBYEADS | 国内广告域名清单 | [GitHub](https://github.com/8680/GOODBYEADS) |
| @gkd-kit/subscription | GKD 官方订阅规则 | [GitHub](https://github.com/gkd-kit/subscription) |

---

## 自行构建

项目不使用 Gradle，直接调用 Android SDK 工具链构建。

### 依赖

- JDK 17
- Android SDK：build-tools 36.1.0、platform android-36
- Python 3.8+
- 依赖 jar：运行 `python tools/fetch_libs.py` 拉取 Shizuku API

### 构建

```bash
# 首次需要生成签名密钥
python tools/make_keystore.py

# 构建（产物在 dist/托予净化-<version>.apk）
python Makefile.py

# 构建并安装到已连接的设备
python Makefile.py install
```

构建流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zipalign` → `apksigner`

### 版本号规则

版本号保存在 `version.txt`，格式 `MAJOR.MINOR.PATCH`，每段上限 9：

```
1.0.0 → 1.0.1 → ... → 1.0.9 → 1.1.0 → ... → 1.9.9 → 2.0.0
```

每次构建成功后自动递增，`versionCode` 由版本号推导（`MAJOR*10000 + MINOR*100 + PATCH`），保证单调递增。

手动设置版本：

```bash
python tools/version.py set 1.2.0
python tools/version.py read
```

### 发布

```bash
python tools/make_keystore.py     # 首次
python Makefile.py                # 构建 dist/托予净化-x.y.z.apk
git tag -a vx.y.z -m "vx.y.z" && git push origin vx.y.z
python tools/release.py           # 创建 release 并上传 APK
```

`tools/release.py` 会读取 `version.txt`，用 git 凭证里的 GitHub token 调 API：
创建对应的 release（已存在则复用），并把 APK 以 `TuoYuClean-x.y.z.apk` 上传。

> 附件名用 ASCII 是刻意的 —— GitHub 的上传接口会丢掉 URL 里的非 ASCII 字符，
> 中文名上传后会变成 `-1.0.0.apk`。

GitHub 会自动把 `.apk` 识别为 Android 安装包并提供对应图标。

### 项目结构

```
app/
  java/com/tuoyu/clean/
    MainActivity.java        两页 UI
    TuoYuClean.java          屏幕超时读写
    AdBlock.java             MIUI 推荐开关 + 验证探针
    AdBlockVpnService.java   本地 DNS 过滤（VpnService）
    AdHosts.java             域名黑名单 + 白名单
    DnsPacket.java           DNS 报文解析与应答
    GkdInstaller.java        GKD 安装
    ShizukuShell.java        Shizuku 命令执行
    ShizukuInstaller.java    内嵌 Shizuku 安装
    InstallProvider.java     把内嵌 APK 交给系统安装器
    EnvironmentCheck.java    卓易通环境检测
    ShortcutActivity.java    无界面快捷方式
  assets/
    gkd.apk                  内嵌 GKD
    shizuku.apk              内嵌 Shizuku
    adhosts.txt              拦截域名清单
tools/                       构建与打包脚本
version.txt                  当前版本号
```

---

## 许可

本项目代码以 MIT 协议开源。

内嵌的第三方组件遵循各自协议：

- Shizuku：[Apache-2.0](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE)
- GKD：[GPL-3.0](https://github.com/gkd-kit/gkd/blob/main/LICENSE)
- 域名清单：[StevenBlack/hosts](https://github.com/StevenBlack/hosts/blob/master/LICENSE)、[GOODBYEADS](https://github.com/8680/GOODBYEADS)
