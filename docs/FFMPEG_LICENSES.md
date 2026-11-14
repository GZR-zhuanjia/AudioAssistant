# FFmpeg / LAME 来源、许可与再分发

本项目通过 JNI 动态链接 FFmpeg，不调用 `ffmpeg` 命令行，也不下载预编译的第三方 FFmpeg 套件。

视频转换默认使用真实的 LAME MP3 编码器生成 `.mp3`，目标为 192 kb/s、48 kHz、双声道；这是对解码后音频进行 MP3 编码，不是更改扩展名。舞曲工程的整曲导出继续使用 AAC/M4A。

| 项目 | 固定值 |
| --- | --- |
| 上游 | https://ffmpeg.org/ |
| 版本 | 7.1.1 |
| 原始源码 | https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz |
| SHA-256 | `733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1` |
| 许可 | LGPL 2.1 或更新版本；此次配置使用 LGPL 2.1+ |
| 目标 | Android 16+（应用 compileSdk/minSdk/targetSdk 36），arm64-v8a，16 KiB ELF 页对齐 |
| 原生工具链 | NDK 28.2.13676358（r28c），native API 35 |
| 动态库 | libavutil.so、libavcodec.so、libavformat.so、libavfilter.so、libswresample.so |

固定值见 [ffmpeg.lock](../scripts/ffmpeg.lock)。该校验值从官方 HTTPS 发布文件计算；脚本在每次解压之前验证完整归档，不声称完成了上游 PGP 签名验证。源码不做补丁修改。升级时需要同时复查组件、上游许可和锁定值。

| MP3 编码依赖 | 固定值 |
| --- | --- |
| 上游 | https://lame.sourceforge.io/ |
| 版本 | LAME 3.100 |
| 官方发布源码 | https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz |
| SHA-256 | `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e` |
| 原始许可 | GNU Library GPL 2.0 或更新版本（LGPL 2.0+） |
| 编译内容 | 只有 libmp3lame 编码库，静态 PIC；排除命令行前端和 mpg123 解码器 |
| 链接方式 | libmp3lame.a 链入 libavcodec.so；不增加独立 libmp3lame.so |

LAME 来源和校验值固定在 [lame.lock](../scripts/lame.lock)，原始归档从 LAME 项目的 SourceForge 官方发布目录下载并验证。LAME 的 `COPYING` 是 1991 年 GNU Library GPL 第 2 版；`include/lame.h` 明确允许使用第 2 版或更新版本。这里保留原始 LGPL 2.0 文本；与 FFmpeg 组合的动态库继续使用 LGPL 2.1+，不把 LAME 的原始许可文本改称 LGPL 2.1。

## 配置与功能范围

[build-ffmpeg.sh](../scripts/build-ffmpeg.sh) 使用 `--disable-everything --disable-autodetect`，显式打开所需音频组件，并固定 `--disable-gpl --disable-nonfree --disable-version3`。唯一启用的外部编码库是 LGPL 的 libmp3lame；不启用网络协议、视频解码器、视频编码器、libavdevice、libswscale 或 libpostproc，只启用本地 `file` 协议。FFmpeg / LAME 归档仍保留上游全部源码和许可文件，未启用部分不会被链接进这些库。

脚本先将 LAME 3.100 构建为 `--enable-static --disable-shared --with-pic --disable-frontend --disable-decoder` 的编码库，再以 `--enable-libmp3lame --enable-encoder=aac,libmp3lame --enable-muxer=ipod,mp4,mp3` 配置 FFmpeg。LAME 上游 `README` 说明其附带的 MPGLIB 解码器来自 GPL 代码，因此该解码器被明确排除；构建脚本也会拒绝启用了 `HAVE_MPGLIB` 或 `DECODE_ON_THE_FLY` 的配置。MP3 输入使用 FFmpeg 的原生 MP3 解码器，不需要启用 LAME 的解码器。

输入容器包括 MP4/MOV、Matroska/WebM、MPEG-TS/PS、AVI、WAV、AIFF、CAF、FLAC、Ogg、MP3、AAC。可解码 AAC、MP2/MP3、FLAC、ALAC、Vorbis、Opus、AC-3/E-AC-3 和常见 PCM/ADPCM 音频。容器中存在音轨并不保证该音轨使用受支持的编码；完整列表以 `scripts/build-ffmpeg.sh --print-config` 为准。输出编码器是 FFmpeg 原生 AAC 与 LAME MP3，分别封装为 M4A（ipod/MP4 muxer）和 MP3。

音频过滤器包括 `abuffer`、`abuffersink`、`loudnorm`、`aresample`、`atrim`、`asetpts`、`concat`、`volume`、`afade`、`aformat`。启用音频过滤组件不会启用视频编码器。

## 复现和构建记录

先安装 Android SDK API 36、CMake 3.22.1 和 NDK 28.2.13676358（r28c），并准备 Bash、curl、tar/xz、make 和 C 编译工具。然后执行：

```sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/28.2.13676358"
scripts/build-ffmpeg.sh --print-config
scripts/build-ffmpeg.sh
```

NDK r28c 和 r29 的官方 `meta/platforms.json` 最高原生 API 都是 35，不能调用不存在的 `aarch64-linux-android36-clang`。因此 FFmpeg 使用原生 API 35；AGP 8.10.1 的 `PlatformConfigurator.clamp()` 也会自动将 JNI 的 native API 从 minSdk 36 限制到 NDK 的最高 API 35。无需手动覆盖 CMake 的 `ANDROID_PLATFORM`。这不会降低应用的安装门槛，应用仍仅支持 Android 16/API 36 及以上。这里没有使用 API 36 专属的原生符号。

脚本只编译这些音频库，不生成 APK/AAB。FFmpeg 动态库安装位置是 `app/src/main/ffmpeg/arm64-v8a/{include,lib}`，由 CMake 作为共享库导入。LAME 使用 `-fPIC`，随 libavcodec.so 使用相同的 16 KiB ELF 对齐；静态归档本身没有 ELF LOAD 段，不需要单独的 LAME `.so`。NDK r28+ 与显式链接参数 `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` 提供 16 KiB ELF 对齐；脚本用 NDK 的 `llvm-readelf` 检查每个安装库的 LOAD 段，并拒绝意外的 `libmp3lame.so` 运行时依赖。JNI 库同样需要 16 KiB 对齐，Android 包的 ZIP 对齐仍需在实际生成包时独立验证。

源码归档保存在 `.ffmpeg-build/ffmpeg-7.1.1.tar.xz` 和 `.ffmpeg-build/lame-3.100.tar.gz`，每次构建使用新的源码解压目录和对象目录。可以使用 `--download-only` 仅取得并校验两个源码归档，或通过 `FFMPEG_WORK_DIR`、`FFMPEG_OUTPUT_DIR`、`FFMPEG_JOBS` 改变目录及并行数。

构建日志、完整 FFmpeg configure 命令、`config.h`、`config_components.h`、`config.mak`、两个锁定文件、NDK 版本和 ELF 信息保存到输出目录的 `build-info/`。`build-info/lame/` 另存 LAME configure 命令及其交叉编译环境、`config.log`、`config.h`、`config.status`、`Makefile`、编译/安装日志和用于重新链接的 `libmp3lame.a`。两个项目的原始许可也复制至输出目录的 `licenses/`。源码、工具链和相同参数可复现功能配置；构建路径和时间等因素仍可能影响二进制逐字节一致性。

## 分发应用时

FFmpeg 的实际 LGPL 条款见 [完整 LGPL 2.1](licenses/FFmpeg-LGPL-2.1.txt) 和 [上游 LICENSE.md](licenses/FFmpeg-LICENSE.md)。LAME 的原始 [LGPL 2.0 COPYING](licenses/LAME-LGPL-2.0.txt)、[LICENSE](licenses/LAME-LICENSE.txt) 和 [README](licenses/LAME-README.txt) 均完整保留。相同文本以及致谢文本也打包在 `app/src/main/assets/licenses/` 中。分发包含 LAME 的 FFmpeg 动态库时，请同时完成以下工作：

1. 在应用和分发页面明确说明使用 FFmpeg 和 LAME、其版权归各自贡献者所有，并给出相应许可文本与源码下载位置。保留 LAME 致谢和 https://lame.sourceforge.io/ 链接。
2. 随二进制提供对应的两个原始源码归档 `ffmpeg-7.1.1.tar.xz`、`lame-3.100.tar.gz`、`scripts/ffmpeg.lock`、`scripts/lame.lock`、`scripts/build-ffmpeg.sh`、该次完整 `build-info/` 和任何实际修改的源码。仅指向上游主页不能代替为接收者提供对应源码。
3. 保留动态链接方式以及接收者替换 FFmpeg 库、调试其修改版本的权利；应用条款不得禁止为调试库修改而进行的逆向工程。本项目提供应用源码和构建脚本，可以用修改后的 LAME 重新构建 libmp3lame.a 和 libavcodec.so，替换 FFmpeg `.so` 后重建应用，并用接收者自己的密钥签名安装。
4. 保留 FFmpeg / LAME 源文件内的版权、许可和第三方声明。若增加库、滤镜或编解码器，重新检查 GPL/nonfree/version3 状态和新增依赖的许可证。

建议发布一个与每次二进制版本对应的源码附件，其中包含上述文件及应用源码；本仓库不包含 FFmpeg / LAME 预编译库。构建脚本会确认 `CONFIG_GPL`、`CONFIG_NONFREE`、`CONFIG_VERSION3` 均为 `0`，以及 `CONFIG_LIBMP3LAME`、`CONFIG_LIBMP3LAME_ENCODER`、`CONFIG_MP3_MUXER` 均为 `1`，并保留实际配置命令。FFmpeg 官方许可说明：https://ffmpeg.org/legal.html。

音频编解码技术可能另外涉及专利；LGPL 许可文本不授予第三方专利权。
