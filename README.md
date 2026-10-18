# 影视整理（Android）

连接 WebDAV，自动识别影视文件、按播放器规则重命名整理，并刮削海报和 NFO。

## 一、编译

需要 JDK 17+ 和 Android SDK（compileSdk 36）。Android Studio 直接打开本目录即可；命令行：

```
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

release 签名从 `~/.gradle/gradle.properties` 读取（不进仓库）：

```
moSignKeyFile=E:/keys/mediaorganizer.jks
moSignKeyAlias=mediaorganizer
moSignKeyPwd=...
```

没配置时 release 包不签名，可改用 `./gradlew assembleDebug`。

### 自动发布（GitHub Actions）

推送 `v*` 标签后，Actions 会自动跑测试、编译签名包、创建 Release 并上传 `MediaOrganizer-vX.Y.apk`。

**一次性设置**：仓库 Settings → Secrets and variables → Actions → New repository secret，添加三个：

| 名称 | 内容 |
|---|---|
| `SIGN_KEY_BASE64` | 签名文件的 base64，Windows PowerShell：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("E:\keys\mediaorganizer.jks")) \| Set-Clipboard` |
| `SIGN_KEY_ALIAS` | 同 `moSignKeyAlias` |
| `SIGN_KEY_PWD` | 同 `moSignKeyPwd` |

一定要用和以前同一个签名文件，否则新包无法覆盖安装。

**每次发布**：
1. 改 `app/build.gradle.kts` 里的 `versionCode`（+1）和 `versionName`
2. 写发布说明 `.github/release-notes/v版本号.md`（不写就用自动生成的提交列表）
3. 合并到 main 后打标签推送：`git tag v2.6 && git push origin v2.6`

标签必须和 `versionName` 一致，缺 Secret 或不一致时流程会直接失败，不会发出未签名的包。

## 二、首次设置

1. **TMDB 令牌**：在 themoviedb.org 注册 → 设置 → API → 复制「API 读访问令牌」（eyJ 开头）。
   打开 App →「刮削设置」→ 填入。国内访问不畅可把 API 地址和图片地址换成自己的反代。
2. **连接**：主界面填 WebDAV 地址、用户名、密码（群晖示例 `http://192.168.1.10:5005`）。
3. **目录**（都是 WebDAV 里的路径）：
   - 扫描目录：待整理的下载目录，如 `/下载`
   - 电影库 / 剧集库：整理后存放的位置，如 `/影视库/电影`、`/影视库/剧集`
4. **命名规则**：点「命名规则」→「切换播放器」选你用的播放器（Plex / Emby / Infuse …），
   需要的话再分别微调剧集和电影的规则，顶部示例卡片会实时显示效果。

所有设置自动保存；密码和令牌经 Android Keystore 加密存储。

## 三、日常使用

1. **扫描**：递归扫描目录，逐条显示识别结果。
   - 百分比 = 匹配可信度；≥75% 自动采用
   - 「待确认」「未匹配」：点条目，从候选列表里选正确的影片
2. **核对预览**：每条显示整理后的完整路径。改了命名规则返回主界面会自动刷新。
3. **开始整理**：移动 + 重命名，同名字幕跟着一起走；目标已存在或同批次重名的会跳过并标红。
4. **刮削**：默认整理后自动进行（可在刮削设置里关闭，改为手动点「刮削」）。
5. **撤销**：删除本次新写入的 NFO/图片 → 文件移回原处 → 删除本次新建的空文件夹。
   原有的文件和文件夹不会被删；App 重启后也能撤销（只保留最近一批）。

## 四、整理结果示例（Plex 预设）

```
/影视库/电影/盗梦空间 (2010) {tmdb-27205}/
    盗梦空间 (2010).mkv
    盗梦空间 (2010).nfo
    poster.jpg  fanart.jpg  clearlogo.png  folder.jpg
/影视库/剧集/老友记 (1994) {tmdb-1668}/
    tvshow.nfo  poster.jpg  fanart.jpg  season02-poster.jpg
    Season 02/
        老友记 (1994) S02E03.mkv
        老友记 (1994) S02E03.nfo
        老友记 (1994) S02E03-thumb.jpg
```

## 五、代码结构

| 文件 | 作用 |
|---|---|
| `parser/MediaNameParser.kt` | 从文件名解析片名、年份、季集、分辨率、发布组 |
| `storage/StorageProvider.kt` | 统一存储接口（以后接网盘只需新增一个实现） |
| `storage/WebDavStorage.kt` | WebDAV 实现 |
| `storage/OperationLog.kt` | 撤销记录持久化 |
| `metadata/TmdbClient.kt` | TMDB 搜索、详情、演职人员、季集、logo |
| `metadata/MediaMatcher.kt` | 文件 ↔ TMDB 匹配打分；目录扫描器 |
| `naming/NamingEngine.kt` | 命名模板引擎 + 各播放器预设 |
| `naming/RenamePlanner.kt` | 生成改名计划；执行整理、撤销 |
| `scraper/Scraper.kt` | NFO 生成、按模板决定图片文件名、写入存储 |
| `scraper/TmdbScrapeSource.kt` | 下载 TMDB 图片 |
| `settings/AppSettings.kt` | 设置数据结构与编解码 |
| `settings/SettingsRepository.kt` | DataStore 保存 + Keystore 加密 |
| `ui/SettingsScreens.kt` | 命名规则页、刮削设置页 |
| `MainActivity.kt` | 主界面与 ViewModel |

## 六、常见问题

- **连接失败 / 401**：检查地址是否带端口、是否 http/https，用户名密码是否正确。
- **TMDB 认证失败**：令牌要用「API 读访问令牌」，不是 v3 的 API Key（v3 Key 也兼容，但推荐令牌）。
- **搜不到 / 超时**：多半是网络访问 TMDB 不畅，换反代地址。
- **识别错**：点条目手动改选；只有集号的文件（如 `03.mp4`）请放在「剧名/Season 2/」这样的目录结构里。
