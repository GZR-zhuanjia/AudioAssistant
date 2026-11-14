# AudioAssistant · 音频助手

为 Android 16（API 36）及更新设备编写的本地音频工具。Kotlin、Compose Material 3、Media3、C++/JNI 和自行精简编译的 FFmpeg；优先 arm64-v8a。无需账号、服务器、网络权限、广告或统计 SDK。

## 已实现的源码

* **视频转音频**：系统相册/文件选择器单选及批量导入、逐项改名及顺序转换、自动找音轨、AAC/ALAC 兼容音轨直接封装 M4A、其他编码转 AAC、进度与取消、结果播放/分享/SAF 保存/音乐库保存。
* **音频剪辑与拼接**：多曲列表、长按拖动柄排序及边缘自动滚动、上下移动按钮、独立多区间非破坏编辑、分割/删除/仅保留/恢复、撤销重做；源时间轴显示删除区域，波形概览和缩放细节、时间输入与微调。
* **试听**：Media3 原曲、编辑后、选区、边界及项目顺序试听，seek/暂停/恢复，源时间与编辑时间映射。界面明确提示自动均衡仅在导出时生效。
* **真实 Native 导出**：解码后按采样裁剪，每首歌的多个片段先拼成统一 PCM，再整体做 loudnorm 双遍处理；缓存测量，手动 dB、可选短淡入淡出，最后只做一次 AAC 有损编码。默认 M4A / AAC / 48 kHz / 192 kbps。
* **文件与项目**：内容哈希命名的私有输入快照、JSON 自动保存最近项目、可恢复读取、多分辨率峰值缓存、临时处理文件清理及明确错误提示。

这些是已经写入仓库的真实实现，**不代表已通过 Android 编译或实机验收**。详细差异和未完成项见 [KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md)。

歌曲排序：长按卡片左侧的 `≡`，上下拖动，松手后确认并自动保存。拖到列表边缘会自动滚动；中途取消或返回不会提交临时顺序。导出按照确认后的歌曲顺序拼接，上移/下移按钮仍可用。

## 文件选择与保存

视频导入有两个入口：「相册导入」使用 Android 系统 Photo Picker，仅显示视频，支持单选/多选，适合选择手机录屏；「文件导入」使用系统文件选择器（Storage Access Framework），按 `video/*` 筛选。歌曲仍通过文件选择器按 `audio/*` 导入，因为系统相册不提供音频选择。

文件选择器可以浏览系统允许的本机存储位置、下载目录和已接入系统的文档来源；具体目录、搜索、选择数量上限和排序界面由手机系统/文件提供程序决定。相册导入只获得所选视频的读取权限，不申请整个相册或全盘存储权限，也没有独立文件管理器。

选好后，应用通过 `content://` 读取文件流并复制到私有目录，生成内容哈希命名的源快照。剪辑不修改原始文件；完成导入后可离线处理，原 URI 权限失效不会影响已有快照，但会额外占用存储空间。第三方云盘来源尚未下载的文件是否可用，由对应提供程序和网络状态决定。

导出文件可用「另存为」通过系统保存器选择位置，也可点「存音乐库」写入 `Music/AudioAssistant`，或使用系统分享面板发送到其他应用。

## 开发准备

项目固定 AGP 8.10.1、Gradle 8.11.1、Kotlin 2.1.20、NDK r28c。安装 Android Studio、SDK 36、JDK 17+ 和 CMake 3.22.1，然后先编译 FFmpeg：

```bash
export ANDROID_NDK_HOME=/本机路径/Android/sdk/ndk/28.2.13676358
bash scripts/build-ffmpeg.sh
```

脚本使用官方 FFmpeg 7.1.1，锁定源码 SHA-256，生成 arm64-v8a 共享库，不编译 FFmpeg CLI。库没有提交到仓库，缺少真实依赖时 CMake 会报错。脚本的 Native API 35 与应用的 minSdk 36 是两个概念：NDK r28c 的最高 Native API 是 35，AGP 会自动选择该工具链上限，应用仍仅安装在 Android 16+。

**本次云端没有运行任何 APK/AAB 打包、安装或实机测试。** Android Studio 和设备验证步骤统一记录在 [BUILD_AND_TEST.md](docs/BUILD_AND_TEST.md)。

## 已执行检查

* 独立 JVM 测试：47 项通过，包括 500 轮固定种子的随机区间编辑检验与 JSON 往返测试。
* 官方 FFmpeg 7.1.1 宿主机构建 + C++ 引擎冒烟测试：探测、波形、转码、无损封装、多区间多曲归一化、静音、增益/淡入淡出、时长、取消/清理及损坏输入。
* 全部 Android Kotlin 源码使用官方 API 36 `android.jar`、实际 Compose/Media3 依赖及 Compose/Serialization 编译器插件完成独立类型编译。
* C++ 引擎和 JNI 桥的宿主机语法检查，Shell 语法、XML 及源码格式检查。
* **尚未执行** Android 资源/Gradle 全工程编译、NDK 交叉编译、APK 16 KB 对齐验收和 Android 16 实机验证。

复跑纯 JVM 测试（不需要 Android SDK，不生成 APK）：

```bash
bash scripts/test-domain.sh
```

宿主机 Native 测试说明见 [BUILD_AND_TEST.md](docs/BUILD_AND_TEST.md)。

## 文档

* [架构、时间映射与文件生命周期](docs/ARCHITECTURE.md)
* [构建与本地测试清单](docs/BUILD_AND_TEST.md)
* [真实限制及后续工作](docs/KNOWN_LIMITATIONS.md)
* [FFmpeg 来源、LGPL 与再分发](docs/FFMPEG_LICENSES.md)
* [Native API 与二进制波形格式](app/src/main/cpp/README.md)

FFmpeg 版权属于其贡献者，以 LGPL 2.1+ 动态链接使用；分发应用时需按许可文档提供对应源码、配置和许可证。
