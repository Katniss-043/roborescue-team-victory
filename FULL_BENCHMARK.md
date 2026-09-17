# RoboRescue 全量地图测试报告

测试日期：2026-09-17  
测试程序：当前 Victory ADF 代码  
运行环境：WSL Ubuntu 24.04、Java 17

## 测试范围

覆盖 `rcrs-server-master/maps` 下的 11 张真实地图：Berlin、Eindhoven、Istanbul、Joao、Kobe、Montreal、NY、Paris、Sakae、SF、VC。

每张地图使用独立的 `benchmark-full-*` 副本，保留完整 `map.gml` 和原始实体规模，并生成包含初始火灾的灾害场景。火灾、点火、坍塌、清障和交通模拟器均已启用。为控制批量测试时间，统一运行 30 个仿真时步；各地图原始比赛时长更长。

## 测试结果

| 地图 | 平民 | 消防 | 警察 | 救护 | 初始火灾 | 时步 | 初始 Score | 最终 Score | 保留率 | 状态 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Berlin | 111 | 5 | 25 | 15 | 5 | 30/30 | 111.000000 | 90.907338 | 81.90% | 成功 |
| Eindhoven | 200 | 40 | 35 | 35 | 10 | 30/30 | 200.000000 | 148.993795 | 74.50% | 成功 |
| Istanbul | 234 | 34 | 36 | 33 | 10 | 30/30 | 234.000000 | 191.776607 | 81.96% | 成功 |
| Joao | 162 | 40 | 40 | 30 | 10 | 30/30 | 162.000000 | 113.124975 | 69.83% | 成功 |
| Kobe | 200 | 30 | 30 | 30 | 10 | 30/30 | 200.000000 | 145.502324 | 72.75% | 成功 |
| Montreal | 100 | 12 | 12 | 12 | 10 | 30/30 | 100.000000 | 64.530048 | 64.53% | 成功 |
| NY | 230 | 50 | 48 | 49 | 10 | 30/30 | 230.000000 | 163.452453 | 71.07% | 成功（4 GB） |
| Paris | 247 | 46 | 10 | 28 | 10 | 30/30 | 247.000000 | 185.329814 | 75.03% | 成功 |
| Sakae | 300 | 48 | 42 | 45 | 10 | 30/30 | 300.000000 | 252.909126 | 84.30% | 成功 |
| SF | 130 | 30 | 37 | 17 | 10 | 30/30 | 130.000000 | 112.491831 | 86.53% | 成功 |
| VC | 413 | 50 | 26 | 50 | 10 | 30/30 | 413.000000 | 311.233429 | 75.36% | 成功 |

初始 Score 合计 `2327.000000`，最终 Score 合计 `1780.251740`，按总量计算的整体保留率为 `76.50%`；11 张地图保留率简单平均为 `76.16%`。

## Score 计算

```text
保留率 = 最终 Score / 初始 Score × 100%
```

Score 取自服务器 `kernel-out.log` 中每个时步的 `Score:` 输出。初始 Score 通常等于场景平民数；火灾、伤亡和救援结果会使 Score 变化。这里的保留率是归一化对比值，不等同于官方比赛排名分数。

## 稳定性和特殊情况

- 11 张地图均完成 30/30 时步，并出现 `Kernel has shut down`。
- 完成测试未检出 `ClassCastException`、`StackOverflowError` 或客户端 `OutOfMemoryError`。
- NY 首次使用默认 2 GB 客户端堆加载完整地图时发生 `OutOfMemoryError`；提高到 4 GB 后重跑成功。
- `build.gradle` 的 `launch` 任务现支持 `-PagentHeap=4g`，默认仍为 `2048m`。
- 测试结束后已清理服务器和智能体进程。

## 日志

日志目录：

```text
E:\roborescue\rcrs-server-master\logs\benchmark-full-berlin-run5
E:\roborescue\rcrs-server-master\logs\benchmark-full-eindhoven-run1
E:\roborescue\rcrs-server-master\logs\benchmark-full-istanbul-run1
E:\roborescue\rcrs-server-master\logs\benchmark-full-joao-run1
E:\roborescue\rcrs-server-master\logs\benchmark-full-kobe-run1
E:\roborescue\rcrs-server-master\logs\benchmark-full-montreal-run1
E:\roborescue\rcrs-server-master\logs\benchmark-full-ny-run2
E:\roborescue\rcrs-server-master\logs\benchmark-full-paris-run1
E:\roborescue\rcrs-server-master\logs\benchmark-full-sakae-run1
E:\roborescue\rcrs-server-master\logs\benchmark-full-sf-run1
E:\roborescue\rcrs-server-master\logs\benchmark-full-vc-run1
```

测试地图副本和服务器日志被 `.gitignore` 忽略；本报告未被忽略，应正常显示为未跟踪文件。
