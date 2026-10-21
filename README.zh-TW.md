# 托予淨化

[简体中文](README.md) | [繁體中文](README.zh-TW.md) | [English](README.en.md)

一個面向小米 / 紅米裝置的輕量工具，解決兩件日常小事：**讓螢幕不熄滅**，以及**把系統裡的廣告清乾淨**。

不用 root，不用刷機，裝好即用。

---

## 目錄

- [功能一覽](#功能一覽)
- [安裝](#安裝)
- [螢幕控制](#螢幕控制)
- [系統去廣告](#系統去廣告)
- [GKD 應用內廣告](#gkd-應用內廣告)
- [權限說明](#權限說明)
- [常見問題](#常見問題)
- [相關專案](#相關專案)
- [自行建置](#自行建置)
- [授權](#授權)

---

## 功能一覽

| 功能 | 作用 | 前置條件 |
|---|---|---|
| 螢幕常亮 | 取消自動熄屏，看文件/導航時螢幕不會黑 | Shizuku（或一次 adb 授權） |
| 自動變暗 | 關閉系統的自動降低亮度 | Shizuku（或一次 adb 授權） |
| 本機廣告攔截 | 透過本機 VPN 把廣告網域解析到 `0.0.0.0` | 授予 VPN 權限 |
| MIUI 推薦關閉 | 關掉負一屏影片流、推薦入口、個人化廣告 | Shizuku |
| 完整攔截清單 | 19 萬條網域的通用黑名單，可開關 | 無需額外權限 |
| GKD 整合 | 一鍵安裝 GKD，複製訂閱連結 | 無 |

---

## 安裝

1. 從 [Releases](../../releases) 下載最新版本的 `托予淨化-x.y.z.apk`
2. 在手機上點擊安裝
3. 首次開啟會彈出引導，按提示完成 Shizuku 授權

> 如果提示「未知來源應用程式」，在系統設定裡允許本次安裝即可。

---

## 螢幕控制

### 前置條件：修改系統設定的權限

「螢幕常亮」和「自動變暗」需要寫入系統設定，這會用到 `WRITE_SECURE_SETTINGS` 權限。它是系統級權限，**應用程式無法自行申請**，只有兩種方式授予：

#### 方式一：Shizuku（推薦，不需要電腦）

1. 開啟應用程式，在「螢幕控制」頁頂端找到 **Shizuku** 卡片
2. 如果顯示「還沒安裝 Shizuku」，點 **安裝 Shizuku**（應用程式內已內嵌安裝檔）
3. 裝好後開啟 Shizuku，按它自己的引導 **啟動服務**
   - Android 11 以上可以用「無線偵錯」啟動，全程不需要電腦
   - 詳細步驟見 [Shizuku 官方文件](https://shizuku.rikka.app/guide/setup/)
4. 回到本應用程式，點 **授權 Shizuku**，在彈窗中允許
5. 授權完成後，應用程式會在背景自動完成 `WRITE_SECURE_SETTINGS` 的授予，無需任何額外操作

#### 方式二：連接電腦執行一行指令

不想裝 Shizuku 的話，用 USB 連接電腦，在終端機執行一次：

```bash
adb shell pm grant com.tuoyu.clean android.permission.WRITE_SECURE_SETTINGS
```

執行一次即可，之後不再需要電腦。

> 首次開啟應用程式時，在引導彈窗點「其它方式」可以看到這行指令，並支援一鍵複製。

### 使用

授權完成後：

- **螢幕常亮** —— 開啟開關，螢幕不再自動熄滅；關閉則恢復原本的逾時時間
- **自動變暗** —— 開啟開關，系統不會在逾時前降低亮度
- **螢幕狀態重新整理** —— 重新讀取系統目前真實的設定並更新介面
- **外觀** —— 跟隨系統 / 日間 / 夜間，選擇會被記住

「目前狀態」卡片會即時顯示系統真實的熄屏時間，例如「永久 / 不會自動熄滅」或「1 分鐘 / 到達此時間後螢幕自動熄滅」。

### 桌面捷徑

長按桌面上的應用程式圖示，可以直接操作三項功能，不會開啟應用程式介面：

- 關閉螢幕常亮 / 開啟螢幕常亮
- 關閉自動變暗 / 開啟自動變暗
- 關閉系統廣告 / 開啟廣告攔截

---

## 系統去廣告

分三層，涵蓋不同類型的廣告。

### 第一層：本機 DNS 過濾（網路層）

原理是應用程式內啟動一個本機 VPN，只接管 DNS 查詢：

```
系統發出 DNS 查詢
  ├─ 命中黑名單  → 直接回傳 0.0.0.0，廣告載入失敗
  └─ 未命中      → 轉發給 223.5.5.5，正常上網
```

**只路由 DNS，真實流量不經過 VPN**，所以不影響網速。

#### 使用

1. 開啟「去廣告」頁
2. 開啟 **一鍵關閉系統廣告** 開關
3. 首次會彈出系統的 VPN 授權請求，點允許

開啟後狀態列會出現鑰匙圖示（VPN 已連線），這是正常的。

#### 攔截清單

- **精選清單**（始終生效）：逐條在實機上驗證過的小米廣告網域，零誤傷
- **完整攔截清單**（可開關）：約 19 萬條網域，融合 [StevenBlack/hosts](https://github.com/StevenBlack/hosts) 與 [GOODBYEADS](https://github.com/8680/GOODBYEADS)，涵蓋國內外廣告聯盟

完整清單以實驗性功能提供，因為大列表難免混入過寬規則。內建白名單保護了應用程式商店下載 CDN、帳號、雲端服務、推送等關鍵網域。

**如果某個網站或應用程式突然打不開**，先關掉「完整攔截清單」試試；確認是攔截導致的，把網域回報給我加進白名單。

#### 驗證效果

點 **驗證攔截效果**，應用程式會實際去解析清單裡每個網域，並列出結果：

```
已攔截 14 / 14 個網域
[x] api.ad.xiaomi.com      小米廣告投放介面
[x] tracking.miui.com      行為追蹤上報
...
正常網站解析正常，上網不受影響。
```

### 第二層：MIUI 推薦開關（設定層）

關閉這些設定項，屬於 MIUI / HyperOS 專屬：

| 設定項 | 效果 |
|---|---|
| 負一屏影片推薦流 | 負一屏不再推送影片 |
| 負一屏影片標籤頁 | 隱藏影片 tab |
| 負一屏推薦入口 | 隱藏推薦入口 |
| 個人化廣告追蹤 | 關閉精準投放 |

在「去廣告」頁開啟主開關時會一併執行，開關下方會顯示「MIUI 推薦開關 5/5」表示全部已關閉。

> 非小米裝置上這些設定項不存在，介面會顯示「目前系統不適用」。

### 第三層：GKD（介面層）

見下一節。

### 網路層攔不住的廣告

有一些廣告是**應用程式自有廣告版位**，內容由應用程式自己的介面下發（例如日曆、天氣裡的推薦卡片）。網域攔截對它們無效，因為攔掉介面會連功能一起廢掉。

這類廣告需要在對應應用程式的設定裡關閉推薦，或用 GKD 在介面上隱藏。應用程式內也已標註這一點。

---

## GKD 應用內廣告

[GKD](https://github.com/gkd-kit/gkd) 是一個開源的廣告跳過工具，透過無障礙服務辨識並點擊/隱藏廣告控制項，處理開屏廣告、彈窗廣告、資訊流廣告。

### 安裝

「去廣告」頁底部有 **GKD 去廣告** 區塊，應用程式內已內嵌 GKD 安裝檔：

1. 點 **一鍵安裝 GKD**
2. 系統會彈出安裝程式，點繼續完成安裝
3. 安裝後按鈕會變成「GKD 已安裝（版本號）」

> 如果安裝程式提示「網路異常」，點「繼續」即可 —— 廣告攔截同時擋掉了 MIUI 安裝程式的聯網偵測。

### 設定

1. 點 **複製訂閱連結**，應用程式會複製官方推薦訂閱：
   ```
   https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5
   ```
2. 開啟 GKD，進入「訂閱」頁面，貼上連結並匯入
3. 按 GKD 的引導授予無障礙權限
4. 在訂閱裡啟用需要的規則群組

詳細用法見 [GKD 官方文件](https://gkd.li/guide/)。

---

## 權限說明

| 權限 | 用途 | 如何授予 |
|---|---|---|
| `WRITE_SECURE_SETTINGS` | 修改螢幕逾時、亮度設定 | Shizuku 自動授予，或 adb 指令 |
| `INTERNET` | VPN 轉發 DNS 查詢 | 安裝時自動授予 |
| `BIND_VPN_SERVICE` | 建立本機 DNS 過濾器 | 首次開啟攔截時系統彈窗確認 |
| `REQUEST_INSTALL_PACKAGES` | 拉起安裝程式安裝 Shizuku / GKD | 首次安裝時系統彈窗確認 |
| `POST_NOTIFICATIONS` | 顯示前景服務通知 | 系統彈窗確認 |

本應用程式**不收集任何資料**，不聯網上傳任何資訊。DNS 查詢只轉發給你所在網路的預設解析器。

---

## 常見問題

**Q：為什麼點了開關沒反應？**

看提示文字。現在會明確告訴你卡在哪一步：

| 提示 | 含義 | 怎麼辦 |
|---|---|---|
| 還沒安裝 Shizuku | 未偵測到 Shizuku | 點「安裝 Shizuku」 |
| Shizuku 服務未執行 | 裝了但服務沒啟動 | 開啟 Shizuku 點「啟動」 |
| 請點上方「授權 Shizuku」 | 服務在跑但沒授權本應用程式 | 點「授權 Shizuku」並允許 |

**Q：開啟攔截後網速變慢了嗎？**

不會。VPN 只路由 DNS 查詢（發往 `10.111.222.2`），網頁、影片等真實流量完全不走 VPN。

**Q：和 Clash 之類的代理衝突嗎？**

衝突。Android 同時只允許一個 VPN 應用程式，兩者只能二選一。

**Q：為什麼日曆/天氣裡還有推薦？**

見上文「網路層攔不住的廣告」，這類內容由應用程式自有介面下發，需要 GKD 或應用程式內設定關閉。

**Q：完整攔截清單會不會誤傷正常網站？**

有可能，這也是它做成可開關的原因。清單裡已內建白名單保護常見服務，並從清單中剔除了 `baidu.com`、`taobao.com`、`youtube.com` 這類過寬規則。遇到問題先關開關驗證，再回報。

**Q：解除安裝後設定會恢復嗎？**

螢幕逾時會恢復成你開啟常亮前的值（應用程式第一次修改時會記錄原值）。DNS 攔截在關閉開關或解除安裝後立即失效，不需要額外操作。

---

## 相關專案

本專案依賴或整合了以下開源專案，感謝它們的作者：

| 專案 | 作用 | 連結 |
|---|---|---|
| Shizuku | 提供 shell 權限，用於修改系統設定 | [GitHub](https://github.com/RikkaApps/Shizuku) · [官網](https://shizuku.rikka.app/) |
| GKD | 透過無障礙服務跳過應用程式內廣告 | [GitHub](https://github.com/gkd-kit/gkd) · [官網](https://gkd.li/) |
| StevenBlack/hosts | 通用廣告網域清單 | [GitHub](https://github.com/StevenBlack/hosts) |
| GOODBYEADS | 國內廣告網域清單 | [GitHub](https://github.com/8680/GOODBYEADS) |
| @gkd-kit/subscription | GKD 官方訂閱規則 | [GitHub](https://github.com/gkd-kit/subscription) |

---

## 自行建置

專案不使用 Gradle，直接呼叫 Android SDK 工具鏈建置。

### 相依

- JDK 17
- Android SDK：build-tools 36.1.0、platform android-36
- Python 3.8+
- 相依 jar：執行 `python tools/fetch_libs.py` 取得 Shizuku API

### 建置

```bash
# 首次需要產生簽章金鑰
python tools/make_keystore.py

# 建置（產物在 dist/托予淨化-<version>.apk）
python Makefile.py

# 建置並安裝到已連接的裝置
python Makefile.py install
```

建置流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zipalign` → `apksigner`

### 版本號規則

版本號儲存在 `version.txt`，格式 `MAJOR.MINOR.PATCH`，每段上限 9：

```
1.0.0 → 1.0.1 → ... → 1.0.9 → 1.1.0 → ... → 1.9.9 → 2.0.0
```

每次建置成功後自動遞增，`versionCode` 由版本號推導（`MAJOR*10000 + MINOR*100 + PATCH`），保證單調遞增。

手動設定版本：

```bash
python tools/version.py set 1.2.0
python tools/version.py read
```

### 發佈

```bash
python tools/make_keystore.py     # 首次
python Makefile.py                # 建置 dist/托予淨化-x.y.z.apk
git tag -a vx.y.z -m "vx.y.z" && git push origin vx.y.z
python tools/release.py           # 建立 release 並上傳 APK
```

`tools/release.py` 會讀取 `version.txt`，用 git 憑證裡的 GitHub token 呼叫 API：
建立對應的 release（已存在則沿用），並把 APK 以 `TuoYuClean-x.y.z.apk` 上傳。

> 附件名稱用 ASCII 是刻意的 —— GitHub 的上傳介面會丟掉 URL 裡的非 ASCII 字元，
> 中文名稱上傳後會變成 `-1.0.0.apk`。

GitHub 會自動把 `.apk` 辨識為 Android 安裝檔並提供對應圖示。

### 專案結構

```
app/
  java/com/tuoyu/clean/
    MainActivity.java        兩頁 UI
    TuoYuClean.java          螢幕逾時讀寫
    AdBlock.java             MIUI 推薦開關 + 驗證探針
    AdBlockVpnService.java   本機 DNS 過濾（VpnService）
    AdHosts.java             網域黑名單 + 白名單
    DnsPacket.java           DNS 封包解析與回應
    GkdInstaller.java        GKD 安裝
    ShizukuShell.java        Shizuku 指令執行
    ShizukuInstaller.java    內嵌 Shizuku 安裝
    InstallProvider.java     把內嵌 APK 交給系統安裝程式
    EnvironmentCheck.java    卓易通環境偵測
    ShortcutActivity.java    無介面捷徑
  assets/
    gkd.apk                  內嵌 GKD
    shizuku.apk              內嵌 Shizuku
    adhosts.txt              攔截網域清單
tools/                       建置與打包腳本
version.txt                  目前版本號
```

---

## 授權

本專案程式碼以 MIT 授權條款開源。

內嵌的第三方元件遵循各自授權條款：

- Shizuku：[Apache-2.0](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE)
- GKD：[GPL-3.0](https://github.com/gkd-kit/gkd/blob/main/LICENSE)
- 網域清單：[StevenBlack/hosts](https://github.com/StevenBlack/hosts/blob/master/LICENSE)、[GOODBYEADS](https://github.com/8680/GOODBYEADS)
