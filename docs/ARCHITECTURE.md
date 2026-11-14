# 架构与数据流

## 职责

一个 Android application module，按职责分包，不引入账号、服务端、Room 或依赖注入框架。

* `ui/`：Compose Material 3 中文界面、ViewModel、编辑临时状态及任务状态。主界面的视频转换和音频编辑相互独立。
* `domain/`：可序列化项目模型、半开区间算法、历史记录、源时间/编辑时间映射、缓存键。使用 `Long` 微秒；浮点数只用于绘制、缩放和音量。
* `data/AppRepository.kt`：SAF 导入、不可变源文件快照、AtomicFile 项目 JSON、波形缓存、输出文件、分享、SAF/MediaStore 保存。UI 不依赖 JNI。
* `playback/`：共享 Media3 播放器，按保留片段构建 clipped playlist，以当前 media item 和片段起点还原源位置。
* `engine/`：协程 Native 适配器，将结构化数据传入 JNI，传递进度、错误和取消。
* `cpp/`：libavformat/libavcodec/libavfilter/libswresample 的处理引擎；不运行或嵌入 CLI 的 `main`。

## 文件与持久化

系统选择器提供的是 `content://` 访问能力，不能作为文件路径传给 FFmpeg。导入时以流复制到 `files/sources/<SHA-256>.media`，复制过程检查协程取消，先写临时文件再重命名。相同字节复用同一个快照。原始 URI 仅用于来源记录，后续处理不再依赖其权限，外部原文件被删除或改变不影响已导入的快照。重新导入改变后的内容会得到新的指纹。

视频提供相册和文件两个入口：`PickMultipleVisualMedia` 以 `VideoOnly` 打开系统 Photo Picker，`OpenMultipleDocuments` 以 `video/*` 打开 SAF；两者结果都调用同一个 ViewModel 导入及私有暂存流程。相册取消返回空列表，不改变任务列表。无需申请 `READ_MEDIA_VIDEO`、`READ_MEDIA_IMAGES` 或全盘访问权限；可持久化 URI 授权不可用时仍依靠已经完成的快照处理。歌曲使用 SAF `audio/*`，不通过相册选择。Photo Picker 参考：https://developer.android.com/training/data-storage/shared/photopicker 。

`files/project.json` 保存最近一个项目，通过 AtomicFile 写入。读取失败必须向用户报错，保留原文件，不自动以空项目覆盖。播放位置、选区、缩放、滚动和历史栈不写入项目。撤销重做在本次进程内有效。

导出保存在 `files/outputs`，再由用户保存到 SAF 选择位置或 Music/AudioAssistant 媒体库。MediaStore 先写 pending 行，完成后发布；失败删除行。分享使用 FileProvider 临时只读授权。应用不声明互联网或广泛存储访问权限。

## 编辑和预览

保留区间在原始时间线上，删除只做区间差集，恢复做并集，保留选区做交集。规范化合并重叠区间但保留首尾相接的显式分割点。原始波形始终可见，删除区域加遮罩。时间映射按区间长度前缀和计算，区分源歌曲位置、编辑后歌曲位置和项目顺序。

`ui/SongReorderState` 维护拖动时的临时歌曲顺序，以稳定 ID 找到可见卡片；越过另一张卡片中心时交换显示位置，靠近边缘按帧滚动。交换后等待 LazyColumn 的新 key 顺序完成布局，避免旧坐标反复触发反向移动；显式保持可见位置，防止首个可见 key 移动导致视口跳动。松手时 ViewModel 校验歌曲 ID 集合，再一次性更新项目、停止旧试听列表并触发自动保存；手势取消不改变项目数据。

Media3 按区间剪切媒体项，编辑/排序后重建播放列表。试听沿用源文件，无需每次选区变化后重新导出。Media3 的剪切时间和硬件解码、播放列表衔接不能保证采样级准确，也不能保证与 Native 输出的边界听感完全相同。试听中的手动音量及响度补偿只是近似，界面必须明确提示；最终文件由 FFmpeg 双遍流程产生。

## 波形

首次需要波形时 Native 解码并计算峰值。AWF1 为小端二进制：4 字节 magic、uint32 version、uint32 sampleRate、uint32 channels、uint64 durationUs、uint32 framesPerPeak、uint64 peakCount，接着为每个窗口的各声道 float32 峰值。Kotlin 读入后按两两取最大值建立多级缓存，绘图选择相应层级。缩放/滚动仅查询内存峰值，不重新解码。缓存位于 `cache/waveforms`，与 JSON 项目分离，按源指纹和缓存版本失效；缺失或损坏会重新分析。

## Native 任务与导出

Native API 管理任务句柄与取消信号，Kotlin 在 IO 调度器中等待，进度回调只更新状态。单实例串行执行耗时任务，取消可从其他线程置位，在解码、过滤、编码和读写循环检查；资源由 RAII 释放。释放不能与正在使用的对象竞争。FFmpeg 的日志回调是进程全局状态，分析时必须限制到当前任务/线程并妥善处理，不使用 CLI 遗留的退出函数或静态命令状态。

视频抽取优先将兼容的 AAC/ALAC 流直接封装进 M4A，其他输入解码后转 AAC。音频编辑先按原始时间精确选择解码采样，将一首歌的所有保留片段转成统一的 48 kHz 双声道 PCM；每首歌整体 loudnorm 测量一次，再传入 measured 参数做第二遍。测量缓存由拼接 PCM 的实际内容和有效 loudnorm 参数决定。手动增益、峰值保护及可选短淡入淡出在 Native 处理链完成，最后顺序送入同一个 AAC 编码器，避免片段级中间有损编码。临时 PCM 增加磁盘需求，但降低复杂多输入 filter graph 的维护成本。具体峰值和手动增益策略见 Native 源码与已知限制。

## 技术参考

* [Media3 clipping API](https://developer.android.com/reference/androidx/media3/common/MediaItem.ClippingConfiguration.Builder)
* [Media3 playlists](https://developer.android.com/media/media3/exoplayer/playlists)
* [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
* [MediaStore](https://developer.android.com/training/data-storage/shared/media)
* [FFmpeg 音频解码示例](https://ffmpeg.org/doxygen/7.1/decode_audio_8c-example.html)
* [FFmpeg loudnorm](https://ffmpeg.org/ffmpeg-filters.html#loudnorm)
* [Android 16 KB 内存页](https://developer.android.com/guide/practices/page-sizes)
* [FFmpeg LGPL 合规](https://ffmpeg.org/legal.html)
