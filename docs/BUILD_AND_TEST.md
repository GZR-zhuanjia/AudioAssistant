# 构建与验证

## 云端边界

本次开发**没有执行** `assembleDebug`、`assembleRelease`、`bundleRelease` 或任何 APK/AAB 打包任务，没有安装应用或进行 Android 实机测试。下方 Android 构建和设备检查清单供开发者在本地执行，不代表已经通过。

本仓库不提交 FFmpeg 预编译二进制，不用空库替代 FFmpeg。先生成真实 FFmpeg 依赖，再同步 Android 工程；缺少库时 CMake 明确失败。

## 本地工具

* Android Studio，JDK 17 或兼容运行 Gradle 的 JDK。
* Android SDK Platform 36、平台工具、CMake 3.22.1。
* Android NDK `28.2.13676358`（r28c），优先 Linux x86_64 或 macOS 主机。
* Bash、curl、tar、xz、make、Python 3；完整参数参见脚本帮助。
* Gradle Wrapper 8.11.1、AGP 8.10.1、Kotlin 2.1.20。依赖版本固定在 Gradle 文件中。

在 `local.properties` 配置 `sdk.dir`，不要提交本机路径。

## FFmpeg

```bash
export ANDROID_NDK_HOME=/本机路径/Android/sdk/ndk/28.2.13676358
bash scripts/build-ffmpeg.sh
```

脚本固定官方 FFmpeg 7.1.1 和源码 SHA-256，输出 `app/src/main/ffmpeg/arm64-v8a/include`、`lib`。不要添加 GPL/nonfree 编解码器或网络协议。构建日志、configure 参数、源代码及许可证必须按 `docs/FFMPEG_LICENSES.md` 的要求随分发保留。脚本生成的 ELF 需要在本地检查 LOAD alignment 和 Android SONAME，不可仅依靠链接参数推定合规。

应用 compile/min/target SDK 均为 36。NDK r28c 的最高 Native API 为 35，FFmpeg 脚本因此使用 `aarch64-linux-android35-clang`，AGP 的 Native 配置会自动把 36 选择到工具链上限 35，无需手工覆盖 `ANDROID_PLATFORM`。这不会降低应用的安装最低版本。已核对官方 NDK `meta/platforms.json` 和 AGP 8.10.1 源码 `PlatformConfigurator.kt` 的 clamp 行为；不要将脚本改成不存在的 android36-clang。

## 无 APK 的检查

```bash
bash -n scripts/build-ffmpeg.sh
bash scripts/test-domain.sh
git diff --check
```

独立 JVM 测试脚本不启动 Android Gradle 插件，不执行资源处理、NDK 编译或打包。首次运行需下载固定版本的 JVM 工具和测试依赖。

## Android Studio 验证清单（待本地完成）

1. 先生成 FFmpeg 库，再 Gradle Sync，检查 Kotlin/Compose、Media3 和所有 JNI 签名是否匹配；编译 Debug。此操作只能在本地允许构建的环境执行。
2. 用 Android 16 arm64 真机运行，检查边到边布局、系统浅色/深色模式、竖屏、返回导航、旋转和重复进入裁剪页。
3. 录制包含 AAC 音轨的 MP4：抽取后比较流 codec、时长、听感，确认走无重编码路径。再测试非 AAC 音轨转码，以及无音轨、损坏 MP4、截断文件、空文件。
4. 单选/批量选择文件，测试 Downloads、MediaStore、第三方 DocumentsProvider、无法持久化授权的 provider、大文件及空间不足；完成导入后撤销原 URI 权限，确认应用私有快照仍可用。
5. 编辑 300 秒音频，依次删除 120–180 秒和 60–90 秒，检查三个保留区间、灰色删除区和最后时长；再测试恢复、仅保留、播放点分割、撤销重做、删除全部后恢复。
6. 波形首次分析完成后重新进页面/重启，确认复用缓存。删除/破坏波形缓存后重建，缩放、横向拖动和选区不应触发完整解码。测试长音频的内存占用。
7. 使用精确时间输入与 ±0.1/±1 秒调节，验证起止边界、当前指针、选区长度；分别试听原曲、编辑后、选区、起点/终点附近及剪口前后。
8. 对 0–60 秒和 90–120 秒片段，确认编辑后第 70 秒对应源第 100 秒；编辑、撤销、排序后不能继续使用旧播放列表。检查暂停、恢复、seek、耳机拔出、来电、音频焦点、后台/前台生命周期。
9. 多首不同采样率和单双声道歌曲按列表顺序导出；使用 FFprobe 检查 AAC、48 kHz、192 kbps 目标和时长，用耳机检查边界、可选淡入淡出；实际平均码率可能与目标略有差异。
10. 比较不同响度歌曲，保持同一首歌多个保留片段整体测量。重复导出应复用响度分析；修改区间/设置应失效。测试静音、极短片段、强瞬态和无法满足目标的内容。
11. 用独立 FFmpeg `loudnorm` 或 `ebur128` 再分析成品，观察目标 LUFS 和 dBTP。AAC 编码可能引入峰值过冲，不能把编码前限制当作解码后绝对保证。测试正负手动增益和关闭自动均衡。
12. 分别在导入、波形、抽取、响度分析和导出过程中取消，立即再启动任务；检查不会死锁、不会出现损坏的成功结果、Native 句柄不泄漏、PCM/partial 临时文件被清理。
13. SAF 保存、MediaStore 音乐库保存、系统播放和分享，在其他应用中验证 MIME、临时读取授权及音轨可读；取消系统保存器应正常返回。
14. 杀进程/重开，检查最近项目恢复、原始文件仍未改变。损坏 project.json、删除私有源快照，确认明确报错且不默默覆盖项目。
15. 检查 APK 中所有 `.so`（包括 `libc++_shared.so`）的 16 KB ELF 段及 zip 对齐，在 16 KB 页 Android 16 设备/模拟器验证。官方检查方法：https://developer.android.com/guide/practices/page-sizes 。
16. 拖动排序：导入至少 10 首歌曲，长按左侧 `≡`，测试首尾互换、一次跨过多首、快速反向拖动，以及手指停在上下边缘的自动滚动。列表已滚动时拖动首个可见歌曲，视口不应跳走；滚动/点击卡片不应误触排序。松手后校验列表编号、拼接试听/导出顺序及重开项目后的顺序。拖动中按返回、切换模块或中断手势应恢复原顺序；处理任务进行中不能开始排序。上下移动按钮应保持可用。以上触摸行为仍待实机验证。

## 实际检查记录

## 本次云端检查结果

* `bash scripts/test-domain.sh --offline`：**47 项通过**；涵盖区间差集/交集/恢复/分割、空和非法边界、重叠规范化、撤销重做、时间映射、缓存键及 JSON 往返，包含 500 轮固定种子随机检查。
* 官方 FFmpeg 7.1.1 在 Linux 宿主机上用 GCC 构建精简静态库，再链接真实 `engine.cpp`：**冒烟测试通过**。覆盖探测、AWF1 窗口数、44.1 kHz 单声道转 AAC、AAC/ALAC 直接封装、多保留区间/多曲导出、双遍响度及缓存命中/损坏恢复/内容失效、静音、正负增益、淡入淡出、成品时长、取消/清理和损坏输入。
* 独立 FFprobe 检查一个 6 秒成品：AAC，48000 Hz，双声道，6.000000 秒，平均码率 192119 bit/s。独立 FFmpeg 再测该含手动增益的合成测试成品：-15.82 LUFS、-13.06 dBTP；这只描述该测试输入，不是所有文件的响度保证。
* AAC 转封装前后，系统 FFmpeg 导出的压缩帧校验文件 `framemd5` 比较一致，确认该路径未重编码。
* 全部 Android Kotlin 源码通过**独立类型编译**：使用官方 Android 36 `android.jar`、固定版本实际 AAR/JAR classpath、Kotlin 2.1.20 和真实 Compose/Serialization 插件；没有启动 Android Gradle 资源、Native 或打包任务。这覆盖 Kotlin 类型和依赖 API，不覆盖清单合并、资源编译、DEX、Native ABI 或运行行为。
* 引擎及 JNI 用 FFmpeg 7.1.1 和官方 OpenJDK 21 JNI 头文件执行 `g++ -std=c++17 -Wall -Wextra -Wpedantic -fsyntax-only`：通过。
* Shell 脚本语法、XML 解析、锁定源码/Wrapper 校验值、FFmpeg 组件名称和 Git whitespace 检查：通过。

2026-11-14 增加歌曲拖动排序：复用相同 API 36/Compose 依赖进行全部 Kotlin 源码的独立类型编译，并审查稳定 key、视口坐标、自动滚动和取消行为；未执行 APK/AAB 构建或触摸实机测试。实际手势验收见第 16 项。

宿主机复跑（需预先构建的 FFmpeg 7.1.1 Linux 静态库源码目录，支持 WAV/PCM/AAC/ipod 和所需音频滤镜）：

```bash
FFMPEG_HOST_SOURCE=/路径/ffmpeg-7.1.1 bash scripts/test-native-host.sh
```

脚本用 Python 标准库生成合成 WAV，用系统 FFmpeg（若存在）补充 ALAC 样本，使用 g++ 链接相同引擎，再执行断言；测试产物位置会打印。需要文件夹外的临时磁盘空间。宿主机测试不替代上方 Android Studio 和实机清单。

**未执行** Android Gradle 全工程编译、Android NDK/CMake 交叉编译、APK/AAB 构建、安装、16 KB 成品检查和 Android 实机测试。未列为通过的 Android/NDK 检查均待本地完成。
