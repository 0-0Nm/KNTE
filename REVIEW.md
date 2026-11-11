# KNTE-Next 只读独立审查 · REVIEW

- 审查对象：`/root/toolbox/workspace/knte-next`（git：分支 `main`，**尚无任何 commit**，19 个文件全部处于 staged 未提交状态）
- 审查性质：只读。未运行 gradle、未联网、未安装、未触碰设备、未修改任何被审文件。
- 审查人产出：仅本 `REVIEW.md`。本文件是审查产物，不属于被审源码；如需入库请自行决定，不要在未决定时被 `git add .` 扫入首次提交。
- 结论一句话：**当前源码树不可能编译通过，`:app` 侧存在一个父级（你）负责范围的硬缺口，与 alpha/beta 在途状态无关。**

---

## 1. 审查范围与实际读取

指定目标 8 文件全部读取（绝对路径）：

| # | 文件 |
|---|---|
| 1 | `/root/toolbox/workspace/knte-next/build.gradle.kts` |
| 2 | `/root/toolbox/workspace/knte-next/settings.gradle.kts` |
| 3 | `/root/toolbox/workspace/knte-next/core/build.gradle.kts` |
| 4 | `/root/toolbox/workspace/knte-next/app/build.gradle.kts` |
| 5 | `/root/toolbox/workspace/knte-next/.github/workflows/preview.yml` |
| 6 | `/root/toolbox/workspace/knte-next/app/src/main/AndroidManifest.xml` |
| 7 | `/root/toolbox/workspace/knte-next/app/src/main/kotlin/com/knte/preview/MainActivity.kt` |
| 8 | `/root/toolbox/workspace/knte-next/core/src/main/kotlin/com/knte/core/notes/NoteRepository.kt` |

为判定「P0 是否真实存在」追加读取的必要上下文（非目标 8 文件）：
`Note.kt`、`app/.../NoteViewModel.kt`、`app/.../ui/NotesScreen.kt`、`app/src/test/.../NoteViewModelTest.kt`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`tools/audit_source.py`、`.gitignore`、`README.md`、`docs/ARCHITECTURE.md`、`docs/MIGRATION.md`。

> 读取方式说明：用了约 17 次 shell 调用而非「一次读 8 文件」。原因是给定证据路径不存在（见 §7），必须先用 find 定位；且仅凭目标 8 文件无法判定 `FileNoteRepository` 缺失是「编译阻断」还是「设计上允许」，必须扩展读取。没有为凑次数重复读同一文件。

---

## 2. 分项裁定

### 2.1 固定版本 — 通过（有 4 个未登记项）

- `build.gradle.kts:2-5`：AGP `8.9.2`、Kotlin `2.1.20`、Compose 插件 `2.1.20`，全部精确锁死，无 `+`/动态版本，与 `TOOLCHAIN.json` 一致。
- `gradle/wrapper/gradle-wrapper.properties`：`gradle-8.11.1-bin.zip` + `distributionSha256Sum=f397b287…`，与 `TOOLCHAIN.json` 一致。AGP 8.9.2 要求 Gradle ≥ 8.11.1，满足。
- Kotlin 2.1.20 与 `org.jetbrains.kotlin.plugin.compose` 2.1.20 版本配对正确（Compose 编译器插件必须与 Kotlin 同版本）。
- `core/build.gradle.kts` 的 `plugins { kotlin("jvm") }` 无版本号，由根 `plugins { … apply false }` 供给，Gradle 语义正确。
- `settings.gradle.kts:9` `FAIL_ON_PROJECT_REPOS`，且确实没有任何 project 级 `repositories {}`，规则真实生效而非摆设。

**未登记项（不是缺陷，是证据缺口）**：`TOOLCHAIN.json` 的 `dependencyPomProbes` 只覆盖 AGP/Kotlin/Gradle/Compose-BOM。`app/build.gradle.kts:27,31,32,33,34,35,36` 中的 `activity-compose:1.10.1`、`lifecycle-*:2.8.7`（三个）、`kotlinx-coroutines-android:1.9.0`、`junit:4.13.2`、`kotlinx-coroutines-test:1.9.0` 没有任何 probe 记录。离线环境无法核验这些坐标是否真实存在于 Maven，**不能宣称已核验**。首轮云端构建是唯一裁决者。

### 2.2 Actions 最小权限 — 通过（1 处低风险）

- `preview.yml:10-11` 顶层 `permissions: contents: read`，job 继承；无 `packages: write`、无 `id-token: write`、无 `GITHUB_TOKEN` 外传。
- `preview.yml:23` `persist-credentials: false`，凭证不落到 runner 磁盘。
- 全 workflow 无 `secrets.` 引用；`upload-artifact` 不需要写权限。
- 三个 Action 均按 commit SHA 固定（`11bd719…` / `c5195ef…` / `ea165f8…`），与 `TOOLCHAIN.json.actionsPinned` 一致，无 `@v4` 浮动引用。

**低风险**：`preview.yml:2` `run-name: KNTE preview ${{ inputs.audit_id }}` 直接把 `workflow_dispatch` 字符串输入插入 run-name，无任何长度/字符约束。此处不进入 shell，危险面仅限 run-name 展示层；但 `audit_id` 标注为「Non-secret」只是文字描述，没有任何机制阻止分发者粘贴敏感串。建议：保留原样可接受，若要收口就在 description 之外加一条 `pattern` 或改为不回显。

### 2.3 无旧数据上传 — 通过（1 处需知悉）

- 上传路径（`preview.yml:47-52`）只有 `artifacts/`（本次 debug APK + SHA256SUMS + SOURCE_COMMIT）与四个 `build/reports|test-results` 目录。
- 没有上传 `build/`、`.gradle/`、`local.properties`、`*.jks`、任何 evidence 树、任何旧 APK。
- `artifacts/KNTE-preview.apk` 来自 `app/build/outputs/apk/debug/app-debug.apk`（AGP 8.x 正确路径），是 debug 签名产物，非生产签名。
- `retention-days: 3`，短保留，符合「内部预览」定位。
- `.gitignore` 已覆盖 `*.apk`/`*.aab`/`*.jks`/`.env`/`local.properties`，与 `tools/audit_source.py` 的排除清单一致。

**需知悉**：`preview.yml:43` `if: always()` 与 `:36` `if: success()` 叠加后，语义是「gradle 步骤失败也仍然上传当时已存在的测试报告」。这是可接受的诊断行为（失败时有报告比没有好），且 `:39` 的 `cp` 被 `if: success()` 守住，**失败时不会上传 APK**。不构成旧数据外泄，但验收方要清楚：**artifact 存在 ≠ 构建成功**。

### 2.4 独立包名 — 通过

- `applicationId`/`namespace` = `com.knte.preview`；旧基线为 `com.knte.shelllab`（`knte-shell-v0.18.0/app/src/main/AndroidManifest.xml`）。包名不同，可共存。
- `versionCode = 1` vs 旧 `180000`：不同 applicationId 下不构成降级冲突；同时意味着这个包**永远没有升级路径**，与「preview 实验」定位自洽。
- `AndroidManifest.xml` 中 `.MainActivity` 在 `namespace = com.knte.preview` 下正确解析为 `com.knte.preview.MainActivity`。
- Manifest 无 `<provider>`、无自定义 permission、无 `<queries>`、无 authority，无跨应用可见面。
- `versionName = "0.1.0-preview1"` 未伪装成 `0.18.x`，无版本误导。

### 2.5 IO 不在 main thread — 结构正确（1 项 P2 残留）

判定依据：

- `NoteViewModel.kt:64` 默认 `ioDispatcher = Dispatchers.IO`；`open()`(`:126`)、`save()`(`:181`)、`reloadInternal()`(`:~285`) 全部经 `viewModelScope.launch(ioDispatcher)` 执行，仓库调用确实离开主线程。
- `MainActivity.kt:24` `private val storage by lazy { FileNoteRepository(File(filesDir, "preview-notes-v1")) }`：`lazy` 保证 `filesDir`（其 getter 会 `mkdir`）只在**首次仓库调用**时求值，而首次调用来自 IO 协程。`onCreate` 只构造匿名对象与 `Lazy` 委托，**不触碰文件系统**。注释 `MainActivity.kt:22` 的描述与实现相符。
- `NoteViewModel.kt:77` `init { restoreDraft() }` 在主线程执行，但只读写 `SavedStateHandle`（纯内存）与 `MutableStateFlow`，无 IO。
- `NoteRepository.kt:3` 契约注释明确「Synchronous IO contract; Android callers must dispatch it off the main thread」，与 `NoteViewModel` 的实际用法一致。
- UI 层（`NotesScreen.kt`）不出现任何 `File`/`Dispatchers` 引用，UI 与 IO 的所有权划分干净。

**P2 残留**：仓库是阻塞式 IO + `viewModelScope` 取消。Activity 在写入中途被销毁时协程取消，但已进入 `File.writeText` 之类的阻塞调用**不会被中断**，只能靠仓库内部原子写。仓库实现（alpha 负责）尚未存在，因此「取消是否产生半写文件」**完全无证据**。这不是当前代码的缺陷，是当前代码无法回答的问题——需 alpha 的 `FileNoteRepository` + 设备级证据。

### 2.6 factorySavedState 编译风险 — 可编译（2 项 P2）

逐条核：

- `MainActivity.kt:9` `import androidx.lifecycle.createSavedStateHandle`：该扩展函数位于 `androidx.lifecycle` 包、`lifecycle-viewmodel-savedstate` artifact；`app/build.gradle.kts:33` 已直接声明。**可解析**。
- `MainActivity.kt:10` `import androidx.lifecycle.viewmodel.CreationExtras`、`:11` `import androidx.lifecycle.viewmodel.compose.viewModel`：分别来自 `lifecycle-viewmodel`（经 `lifecycle-viewmodel-compose` 传递）与 `lifecycle-viewmodel-compose`（`:32` 已声明）。**可解析**。
- `MainActivity.kt:33` `override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T`：lifecycle 2.8.x 中 `ViewModelProvider.Factory.create(Class, CreationExtras)` 是 Java default 方法，Kotlin 允许 override default 方法。**可编译**。
- `NoteViewModel.kt:62` 构造器默认参数 `savedStateHandle: SavedStateHandle = SavedStateHandle()`：`SavedStateHandle()` 无参公开构造器存在。**可解析**。
- `NoteViewModel.kt:9` `import androidx.lifecycle.viewModelScope`：lifecycle 2.8.0 起 `viewModelScope` 已从 `-ktx` 并入主 `lifecycle-viewmodel`，由 `lifecycle-viewmodel-compose` 传递提供。**可解析**（这条在 2.6.x 之前会断，2.8.7 安全）。

**P2-a（运行期前提）**：`extras.createSavedStateHandle()` 在 extras 缺少 `SAVED_STATE_REGISTRY_OWNER_KEY` 时抛 `IllegalArgumentException`。当前调用点 `MainActivity.kt:41` `viewModel(factory = factory)` 的宿主是 `ComponentActivity`，`LocalCreationExtras` 会带上该 key，**当前成立**。但这是一个隐式运行期契约：一旦 `NotesScreen` 被搬到非 `SavedStateRegistryOwner` 的宿主（裸 `ComposeView`、预览、或未来的非 Activity 容器），会在**运行时**炸而不是编译期。建议在 `docs/ARCHITECTURE.md` 里把这条前提写明，否则重构时必踩。

**P2-b（未来版本耦合）**：override 的是 `Class<T>` 版 `create`。lifecycle 在 2.9+ 引入了 `create(KClass<T>, CreationExtras)` 系列并弱化旧重载；现在无警告，但版本升级时该 override 可能不再是实际派发目标，表现为「SavedStateHandle 静默为空、草稿恢复无声失效」。当前锁定 2.8.7，不受影响；列为升级注意事项，不是当前缺陷。

---

## 3. 实际风险清单（按严重度）

### P0 — 编译阻断，`:app` 无法构建

**R1. `com.knte.core.notes.FileNoteRepository` 不存在。**

- `MainActivity.kt:14` `import com.knte.core.notes.FileNoteRepository`
- `MainActivity.kt:24` `FileNoteRepository(File(filesDir, "preview-notes-v1"))`
- 全仓检索（`--include=*.kt`，含非 .kt 源文件枚举）**无任何定义**。`core/src/main/kotlin/com/knte/core/notes/` 下只有 `Note.kt` 与 `NoteRepository.kt`。

后果链（这是硬阻断，不是「部分功能缺失」）：
1. `:app:compileDebugKotlin` 失败（未解析引用）。
2. `NoteViewModelTest.kt` 与 `MainActivity.kt` 同属 `:app` 模块 → `:app:testDebugUnitTest` 在**编译期**即失败，连测试都不会运行。
3. `preview.yml:34` 整条 `./gradlew … :app:testDebugUnitTest :app:assembleDebug` 以非零退出。
4. `preview.yml:35-41` 因 `if: success()` 被跳过 → `artifacts/KNTE-preview.apk` 与 `SHA256SUMS.txt` 不会生成。
5. `preview.yml:42-54` 因 `if: always()` 仍然执行，但只上传空/不全的测试报告目录，`if-no-files-found: warn` 让该步骤通过。
6. **最终 job 状态 = failed，artifact 内容不完整。**

归属：`AUTHORIZED_SCOPE.json` 明确 `alpha = core FileNoteRepository implementation and tests only`。这是 alpha 在途项，**不是本审查判定的设计错误**。但按任务要求「不因当前文件缺失认定永久阻断」——我不断言永久，只断言**当前树状态 = 构建必败**，且这是唯一能让 CI 变绿的前置条件。在 alpha 落盘前，任何「已构建/已测试」声明都不成立。

**R2. `core/src/test` 为空，alpha 的测试职责同样未落盘。**

- `find core/src/test -type f` 返回空。
- `core/build.gradle.kts:4-5` 已声明 `testImplementation("junit:junit:4.13.2")` 与 `tasks.test { useJUnit() }`。
- 影响：`:core:test` 会**通过**（Gradle 不因零测试失败），所以 CI 不会因此报错——但这恰好意味着 `:core:test` 目前是**无证据的绿色**，不代表仓库实现经过任何测试。R1 修好后若 alpha 只补实现不补测试，CI 依旧全绿而零覆盖。验收时不要把 `:core:test` 通过当作仓库质量证据。

### P1 — 真实风险，不阻断构建但会咬人

**R3. CI 15 分钟超时概率高。**

- `preview.yml:18` `timeout-minutes: 15`。
- `preview.yml:34` `./gradlew --no-daemon`：禁用 daemon，冷启动 + 每次重新 fork Kotlin 编译守护。
- `gradle.properties:2-3` `org.gradle.parallel=false`、`org.gradle.workers.max=2`：串行，Compose 编译器 + AGP 资源处理无法并行。
- workflow 未配置 `actions/cache`，runner 上是**完全冷的 Gradle 缓存**，需下载 Gradle 8.11.1 发行包（约 130MB）+ 全部依赖。
- `ubuntu-24.04` 为 2 核 / 7GB；`gradle.properties:1` `-Xmx2g` 与 `workers.max=2` 匹配，不会 OOM，但也不会快。

综合判断：首次云端构建超时是**高概率现实风险**，不是理论风险。若超时，`timeout-minutes` 会杀掉 gradle，CI 表现为失败，且失败原因与 R1 无关——会造成「修了 R1 还是红」的误判。建议（父级职责）：首次跑通前把 `timeout-minutes` 提到 30，或加 `gradle-build-action`/`actions/cache` 预热。这是 workflow 文件，属你所有，我不改。

**R4. `internal` 顶层符号跨包使用，当前成立但是隐性模块耦合。**

- `NoteViewModel.kt:58` `internal fun String.utf8ByteCount()`。
- `ui/NotesScreen.kt` 有 `import com.knte.preview.utf8ByteCount`。
- `internal` 的作用域是 **Gradle 模块**，不是包。两者同在 `:app`，**编译通过**。
- 风险在于：`MAX_RESTORABLE_BODY_BYTES`/`MAX_TITLE_CHARS`/`NoteDraft`/`NotesStatus`/`NotesUiState`/`utf8ByteCount` 全部以顶层公开/内部符号定义在 `NoteViewModel.kt` 里，使该文件同时是「ViewModel 实现」和「UI 契约头文件」。一旦有人把 `ui/` 拆到独立 Gradle 模块（`docs/ARCHITECTURE.md:9` 说「只有两个物理模块直到有正当理由」，这是被鼓励的方向），`utf8ByteCount` 会立刻变成不可解析引用。不是当前缺陷，是拆模块时的第一个雷。

### P2 — 记录在案，不要求改

**R5. `MAX_STORABLE_BODY_BYTES`（256 KiB）在编辑器不可达。**

`NoteViewModel.kt:115` `onBodyChange` 在 `> MAX_RESTORABLE_BODY_BYTES`（32 KiB）时直接 return，`:188` 的 `save()` 才检查 256 KiB。也就是说 256 KiB 上限只能被仓库里已存在的超长正文触发，而 `open()`(`:136`) 又在 >32 KiB 时拒绝打开。三条路径叠加后，**256 KiB 上限实际是死代码**。不影响正确性，但 `MAX_STORABLE_BODY_BYTES` 与测试注释会误导后来者以为存在 32–256 KiB 区间。属 beta 界面所有权范围。

**R6. `onBodyChange` 每次按键全量编码。**

`NoteViewModel.kt:114` `value.utf8ByteCount()` 每次 `onValueChange` 都 `toByteArray(Charsets.UTF_8)` 分配最多 32 KiB 数组。32 KiB 上限下可接受，纯性能 nit。

**R7. `lifecycle-runtime-compose` 是未使用依赖。**

`app/build.gradle.kts:31` 声明了 `lifecycle-runtime-compose`，但全仓无 `collectAsStateWithLifecycle` 使用（`NotesScreen.kt:47` 用的是 `collectAsState`）。`uiState` 是普通 `StateFlow`，不涉及生命周期感知收集，删除该依赖不影响功能。留着无害，属依赖卫生。

**R8. Manifest 无 `res/`、无 app icon。**

`find` 确认无 `res/` 目录，`AndroidManifest.xml` 未设 `android:icon`。AGP 允许零资源应用；debug 构建会带默认图标。仅外观问题。

**R9. `tools/audit_source.py` 是模式门，不是安全保证。**

脚本自身 docstring 已声明「pattern gate supplements human scope review; not exhaustive secret detection」。它检查：symlink/非常规文件、排除清单、>2MiB、非 UTF-8、三类凭据正则、`<uses-permission>`。当前树能过（无 permission、无大文件、全 UTF-8、wrapper jar 被特判跳过）。**但 `git ls-files` 只覆盖已跟踪文件**——当前 19 个文件全部 staged，所以此刻有效；一旦有人在提交前新增未 `git add` 的敏感文件，该门看不见。不要把「audit_source.py 通过」当成「源码干净」的独立证据。

---

## 4. 明确不是风险的点（避免重复审查）

以下都核过，**不要再当作问题上报**：

1. `NoteViewModelTest.kt` 的 12 个用例与 `NoteViewModel` 实现**内部自洽**：`duplicateSaveAndReloadWhilePendingAreSuppressed` 的 `listCalls == 2`、`openThenSaveUsesFreshCasRevision` 的 `getCalls == 2`、`savedStateHandleRestoresDraftAsUiMetadataOnly` 的 notice 未被 `reloadInternal` 清除，逐条推演均成立。问题只在它们**编译不了**（R1），不在断言错误。
2. `save()` 的 CAS 是「重新 `get` 真实 revision 再比对」而非信任 `SavedStateHandle` 里的 revision，`NoteViewModel.kt:~205`。这是正确设计，`docs/ARCHITECTURE.md:18` 的声明与实现相符。
3. `save()` 失败路径不清空 draft、不标记已保存，`NoteViewModel.kt` catch 分支。测试 `saveFailureKeepsDraftAndDoesNotClaimSaved` 覆盖。
4. `generation` 计数（`listGeneration`/`editGeneration`/`saveGeneration`）防晚到结果覆盖新查询，逻辑正确。
5. Compose API 用法与 BOM 2025.04.01 / material3 1.3.x 匹配：`TopAppBar` 的 `@ExperimentalMaterial3Api` 已由 `NotesScreen.kt:44` 的 `@OptIn` 覆盖（含 `TopAppBarDefaults.topAppBarColors`）；`OutlinedTextField(minLines/maxLines)`、`Modifier.clickable(role=)`、`semantics { contentDescription = }`、`lazy.items(key=)` 签名均正确。
6. `gradlew` 在 git 中为 `100755`（`git ls-files -s` 确认），exec 位已保留，CI 可直接执行。
7. wrapper jar 的 SHA-256 = `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`，与 `preview.yml:32` 硬编码值**逐字符一致**，该校验步骤会通过。
8. Manifest 无 `<uses-permission>`，`tools/audit_source.py` 的 permission 门会通过。
9. 无旧数据引用：全仓 grep `vault|migrat|oldApp|shelllab` 只命中**否定性注释**（`Note.kt:3`、`NoteRepository.kt:4`、`MainActivity.kt:18`、`README.md:9`），无任何真实旧数据路径、旧包名硬编码或 evidence 路径。

---

## 5. 尚未验证 / 无法在本环境验证

- 依赖坐标是否真实存在于 Maven Central / Google（见 §2.1 未登记项）。离线，无结论。
- AGP 8.9.2 + Gradle 8.11.1 + compileSdk 35 的组合是否顺利配置。未运行 gradle。
- `:app:assembleDebug` 是否产出 APK、产物路径是否为 `app/build/outputs/apk/debug/app-debug.apk`。未构建。
- 12 个 JVM 测试是否真的通过。未运行；且 R1 修好前不可能运行。
- Android 运行期任何行为（进程杀死后草稿恢复、旋转、取消中的阻塞写是否半写、`createSavedStateHandle` 的 extras 前提）。需设备，`docs/MIGRATION.md:3` 亦如此声明。
- `TOOLCHAIN.json` 声称的「all official Maven URLs HTTP200」。这是别人的声明，本审查未复现，不作为证据采纳。

---

## 6. 给下一手的明确动作

| 风险 | 归属 | 动作 |
|---|---|---|
| R1 `FileNoteRepository` 缺失 | alpha | 落盘 `core/src/main/kotlin/com/knte/core/notes/FileNoteRepository.kt`，实现 `NoteRepository` 四个方法；必须原子写、不得静默删除、失败可见 |
| R2 core 无测试 | alpha | 补 `FileNoteRepository` 的 JVM 测试；否则 `:core:test` 是无证据绿色 |
| R3 CI 超时 | 父级（你） | 首轮前 `timeout-minutes: 15` → 30，或加缓存 Action。workflow 属你，我不改 |
| R4 拆模块雷 | 父级（你） | 若拆 `:app-ui`，先把 `utf8ByteCount`/`MAX_*`/`NoteDraft`/`NotesUiState` 移到 `:core` 或独立 `:app-model` |
| R5 256 KiB 死代码 | beta | 统一为单一上限，或让编辑器支持 32–256 KiB 区间后恢复 `SavedStateHandle` 上限说明 |
| R6/R7/R8/R9 | — | 记录在案，无需动作 |

**CI 变绿的充分必要条件只有 R1。** R2 不阻断 CI，但缺了它 CI 的绿色没有意义。

---

## 7. 证据路径状态（非阻断）

任务指定证据根目录：

```
/root/toolbox/workspace/knte-shell-v0.18.0/evidence/6f841018-de9c-4a73-bd0d-83019d1c9abf/kotlin-rebuild-d397ffe9-02ef-44f4-a217-f0a4d230fbf4/gamma
```

**不存在。** 实际该 `kotlin-rebuild-…` 目录下只有两个文件：

- `AUTHORIZED_SCOPE.json`（已读并采纳其 ownership 划分作为 §6 归属依据）
- `TOOLCHAIN.json`（已读并采纳其版本/Actions 锁定作为 §2.1/§2.2 比对基准）

`evidence/6f841018…/` 下另存在三个含 `gamma/` 子目录的目录（`source-aware-export-94f6625a…/gamma`、`content-safety-parallel-03d3142a…/gamma`、`restore-protocol-a53debfd…/gamma`），但**均非本次指定的 `kotlin-rebuild-…` 路径**，未读取、未混用。

按任务要求未将其认定为永久阻断，也未循环等待。影响仅限：无法比对 gamma 阶段的历史结论，本审查的版本/权限基准改由 `AUTHORIZED_SCOPE.json` + `TOOLCHAIN.json` 承担。若 gamma 后续落盘，建议重跑一次「gamma 结论 vs 本审查 §2/§3」的一致性比对。
