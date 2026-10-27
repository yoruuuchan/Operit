---
step: 1
scope: 麦克风前台资格的归属与生命周期
---

# 麦克风前台资格归浮窗服务所有

## 旧实现

浮窗语音会话自己没有任何麦克风前台资格。

`FloatingChatService` 在清单里只声明 `dataSync`，启动前台时也只传 `dataSync`。它的窗口是 `TYPE_APPLICATION_OVERLAY` 的 ComposeView，不是 Activity，因此拿不到 Activity 那种 while-in-use 授权。

进程里唯一能拿到 `microphone` 类型的是 `AIForegroundService.tryPromoteToMicrophoneForeground()`。这条路有三个结构性缺陷：

- 它硬性要求 `ActivityLifecycleManager.getCurrentActivity() != null`，最多等 3500ms。侧键路径的 `OperitAssistActivity` 在 `onCreate` 里就 `finish()`，浮窗稳定显示后没有任何 resumed Activity，等待超时后打印 `promote microphone foreground skipped: app not in foreground` 并返回 false
- `AIForegroundService` 在每轮 AI 回复结束时收到 `EXTRA_STATE = STATE_IDLE`，走 `stopSelfIfIdle(ignoreAppForeground = true)` 自我销毁，`microphone` 类型随服务一起消失
- `ACTION_ENSURE_MICROPHONE_FOREGROUND` 分支先 `serviceScope.launch` 异步 promote，紧接着同步调用 `stopSelfIfIdle`。自毁链路 `onDestroy → stopWakeMonitoring → serviceScope.cancel()` 会把还没执行的 promote 直接取消

`SpeechInteractionManager.startListening()` 每轮都调用 `ensureMicrophoneForeground(context, forceStart = true)` 来补资格，于是首轮可能靠 `OperitAssistActivity` 短暂的 resumed 窗口撞上 promote，后续轮次必然补不到。

Android 9 起，进程在缺少合法收音资格时采集音频不会报错，`AudioRecord` 正常构造、`startRecording` 正常返回，读到的却是无声数据。VAD 因此永不触发，界面上就是「完全没反应，和没说话一样」，日志里也没有任何异常。这与报告里「语音服务本身正常，STT 链路无误」完全吻合。

## 意图修正

把麦克风前台资格交给真正活着的那个服务。浮窗会话期间只有 `FloatingChatService` 贯穿始终，麦克风类型就该由它声明并持有，不能寄存在一个每轮都会自毁、且依赖瞬时 Activity 的服务上。

## 新实现

清单给 `FloatingChatService` 追加 `microphone` 类型：

```xml
android:foregroundServiceType="dataSync|microphone"
```

`onCreate` 组装前台类型时按录音授权状态带上 `microphone`。带类型调用要求 `RECORD_AUDIO` 已授予，否则系统抛 `SecurityException`，所以类型跟随授权状态组装，这是平台调用边界而不是回退分支。

`SpeechInteractionManager.startListening()` 删掉 `ensureMicrophoneForeground(forceStart = true)` 调用。`ACTION_PREPARE_WAKE_HANDOFF` 保留，它负责让唤醒监听让出麦克风，与采集资格是两件事。

`ensureMicrophoneForeground()` 的 `forceStart` 参数唯一传入 true 的调用点就是被删掉的那处，参数随之移除，避免留下一个没人用的开关。

## 结果

浮窗打开即持有麦克风前台类型并保持到会话结束，多轮对话不再依赖 AI 回合间隙里某个服务是否还活着。唤醒监听那条链路保持原样，仍由 `tryPromoteToMicrophoneForeground()` 在 Activity 可见时补类型。

[DONE]
