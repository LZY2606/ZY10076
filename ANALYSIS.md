# Shopware 6 Toolbox — Twig 模板索引管线分析

本文基于仓库当前快照（单一提交 `f4d2348`，插件版本 0.1.1）中可读到的代码与测试，追踪 Twig
模板数据如何从磁盘上的虚拟文件进入索引、再被各类 UI 功能消费。文中所有行为结论都给出
仓库文件与行号；凡涉及 IntelliJ 平台机制处均标注为“平台行为”，与仓库事实分开。

## 0. 一个必须先纠正的前提

任务描述用了 “StubIndex” 一词，但**本仓库没有使用任何 StubIndex**：

```
$ rg -n "StubIndex" src
src/test/.../TwigUpstreamPriorityTest.kt:17: *(StubIndex/FileBasedIndex storage
```

Twig 相关的四个索引全部是 `FileBasedIndexExtension`（`com.intellij.util.indexing`），在
`src/main/resources/META-INF/plugin.xml:41-45` 注册：

| 索引 ID | 类 | key | value |
| --- | --- | --- | --- |
| `de.shyim.shopware6.frontend.twig_templates` | `ShopwareTemplateIndex` | view path（相对 `Resources/views/`） | 文件内 `sw_extends` 目标引用，无则空串 |
| `de.shyim.shopware6.frontend.twig_template_extends` | `ShopwareTemplateExtendsIndex` | 被引用模板的 view path（反向索引） | `Void` |
| `de.shyim.shopware6.frontend.twig_hash` | `TwigBlockHashIndex` | block 名 | `TwigBlockHash`（见下） |
| `de.shyim.shopware6.frontend.twig_blocks` | `TwigBlockDeprecationIndex` | block 名 | `TwigDeprecation`（relPath + 消息） |

“进入索引”因此指的是 `FileBasedIndex` 的磁盘持久化 forward index，而不是基于 stub 的
`StubIndex`。下文沿这一真实架构展开。

## 1. 总体数据流

```
磁盘上的 .twig 虚拟文件 (VirtualFile)
   │  DefaultFileTypeSpecificInputFilter(TwigFileType.INSTANCE)
   ▼
FileBasedIndex 索引期: DataIndexer.map(FileContent)
   ├─ ShopwareTemplateIndex        正则扫文本 -> view path -> extends 目标
   ├─ ShopwareTemplateExtendsIndex 正则扫文本 -> 反向 key（被继承的 view path）
   ├─ TwigBlockHashIndex           遍历 PSI TwigBlockTag -> block 名 -> TwigBlockHash
   └─ TwigBlockDeprecationIndex    遍历 PSI TwigBlockStatement + 前导 TwigComment
   │  EnumeratorStringDescriptor / ObjectStreamDataExternalizer 持久化
   ▼
运行期查询（全部 GlobalSearchScope.allScope(project)）
   ├─ TwigUtil / ShopwareTemplateUtil（getUpstreamBlocks、链追踪、fallback 排序、缓存）
   ▼
UI 消费方
   ├─ navigation: TwigTemplateGoToDeclareHandler / TwigBlockGoToDeclareHandler
   ├─ marker:     TwigBlockMarker（gutter 双向箭头）
   ├─ inspection: TwigBlockHashChanged / HashMissing / Removed / Deprecated
   ├─ quickfix:   AddMissingTwigVersioningCommentFix -> TwigUtil.addVersioningComment
   ├─ intention:  AddTwigVersioningIntention / ExtendTwigBlockIntention / ShowTwigBlockDifference
   └─ completion: TwigCompletionProvider -> ShopwareTemplateUtil.getTemplateLookupElements
```

四个索引的 `dependsOnFileContent()` 均为 `true`（如 `TwigBlockHashIndex.kt:67-69`），文件
内容变化会触发重新索引。

## 2. 项目识别与扩展（bundle / app）发现

模板管线本身不做“这是不是 Shopware 项目”的显式判断：任何 `TwigFileType` 文件都会经过索引
input filter，然后由索引器内的路径断言 `path.contains("Resources/views/")` 决定是否产出
（`ShopwareTemplateIndex.kt:23-31`、`TwigBlockHashIndex.kt:25-27`）。项目层面的“扩展发现”
由两个独立索引承担：

- **PHP bundle 索引** `ShopwareBundleIndex`：
  - input filter 只收 PHP 文件（`ShopwareBundleIndex.kt:89-92`）；
  - 类必须非 abstract 且 FQN 继承 `\Shopware\Core\Framework\Bundle` 或
    `\Shopware\Core\Framework\Plugin`（`ShopwareBundleIndex.kt:94-108`）；
  - 排除测试/夹具文件：文件名以 `.` 开头或以 `Test` 结尾、路径含 `/tests/`、`/test/`、
    `/fixtures/`、`_fixture(s)`、`tests/integration/php/`（`ShopwareBundleIndex.kt:110-126`）；
  - 每条记录的 `viewPath` 硬编码为 `<PHP 文件所在目录>/Resources/views/`，`rootFolder`
    向上递归找到第一个含 `composer.json` 的目录（`ShopwareBundleIndex.kt:48-75`）；
  - 所有 bundle 都以固定 key `"all"` 存放（`:51`），`getAllBundles` 用
    `getValues(key, "all", allScope)` 取回（`ShopwareBundleUtil.kt:21-24`）。
  - 非 view bundle（Administration、Checkout、Framework 等 8 个）在
    `getAllBundlesRelatedToViews` 中被过滤（`ShopwareBundleUtil.kt:18-30`）。
- **App 索引** `ShopwareAppIndex`：只接受路径含 `custom/apps` 且文件名为 `manifest.xml`
  的 XML（`ShopwareAppIndex.kt:38-45`），`<name>` 作为 app 名，view 目录同样推导为
  `<manifest 目录>/Resources/views/`（`:74-83`）。

两者合并为“扩展”集合：`ShopwareExtensionUtil.getAllExtensions` =
bundle(related-to-views) + apps（`ShopwareExtensionUtil.kt:7-14`），供
`ExtendTwigBlockIntention` 选择目标扩展（`ExtendTwigBlockIntention.kt:73-80`），并通过
`isInProject` 过滤掉不在项目内容根内的扩展（`:76-79`）。

注意：这两个“扩展索引”只服务于生成（Extend block）等功能；**模板链与 block 解析并不依赖
bundle 索引**，而是直接用绝对路径字符串做 bundle 名推导（见 §4.2）。这意味着即使 PHP 源码
不在项目内（独立插件仓库），模板索引仍会建立，只是链解析在找不到父模板时中止（见 §5.5、
§8.4）。

## 3. 抽取阶段：从虚拟文件到索引键值

### 3.1 模板路径

view path = 绝对路径中 `Resources/views/` 之后的部分：
`TwigUtil.getRelativePath`（`TwigUtil.kt:56-58`，`substringAfter` 取最后一次匹配）。
`ShopwareTemplateIndex.getIndexer` 对每个 `Resources/views/` 下的 twig 文件产出
`mapOf(relativePath to extends目标或"")`（`ShopwareTemplateIndex.kt:21-33`）。

同一 view path 对应多个物理文件（Shopware 同名覆盖）时，`FileBasedIndex` 的
`getContainingFiles(key=viewPath)` 会返回**全部**文件；查询侧再做过滤与排序
（`ShopwareTemplateUtil.kt:223-229`，后缀必须是 `Resources/views/$templatePath`）。

### 3.2 extends 关系（正则，而非 PSI）

`TwigUtil.kt:68-69` 定义两个正则：

```kotlin
private val EXTENDS_PATTERN = Regex("\\{%-?\\s*(sw_)?extends\\s")
private val EXTENDS_TARGET_PATTERN =
    Regex("\\{%-?\\s*(?:sw_)?extends\\s+['\"](@[A-Za-z0-9_]+/[^'\"]+)['\"]")
```

- `isExtendingTemplate` 只用前者判断“文件是否含 extends 标记”（`TwigUtil.kt:71-73`）；
- `findExtendsTargetReference` 用后者抓 `@Bundle/view-path` 捕获组（`:75-77`）。

两个索引共用这一抽取结果：
- 正向：`ShopwareTemplateIndex` 把目标引用存成 value（`ShopwareTemplateIndex.kt:28-31`）；
- 反向：`ShopwareTemplateExtendsIndex` 把目标中的 bundle 前缀剥掉
  （`target.substringAfter("/", "")`，即 `@Storefront/a/b` -> `a/b`），以 view path 为
  key（`ShopwareTemplateExtendsIndex.kt:29-40`）。注释（:31-33）明确说明：所有 override 都
  引用 `@Storefront/...`，运行期它们覆盖同 view path 的所有模板，因此反向索引只存 view path。

**正则的边界（已用 JDK 21 jshell 按同一 pattern 验证，属于代码事实）**：
- 同时接受 `sw_extends` 与原生 `extends`，接受 `{%-`、`{%extends`、单双引号；
- **不要求引号内以 `@` 开头以外的 bundle 名形式合法**，但要求至少一个 `/`；
- 正则不识别 Twig 注释语法：`{# {% sw_extends '@Storefront/x.html.twig' %} #}` 仍会被
  两个正则命中（实测 `find()` 返回该引用）。即“注释掉的 extends”当前会被当作真实继承抽取
  进两个索引，并让 `isExtendingTemplate` 返回 true。仓库现有 fixture 中没有这种用例，也没有
  测试覆盖（见风险 R4）。

### 3.3 block 名称与 hash

`TwigBlockHashIndex.getIndexer`（`TwigBlockHashIndex.kt:21-48`）对文件 PSI 做
`PsiRecursiveElementWalkingVisitor`，对每个 `TwigBlockTag`：

- key = `element.name`（`:32`）；
- value = `TwigBlockHash(name, relativePath, absolutePath, hash, text, hasVersioningComment)`
  （`dict/TwigBlockHash.kt:5-12`）；
- hash 输入是 **`element.parent.text`**（整个 block statement，从 `{% block %}` 到
  `{% endblock %}`，`:36`），算法在 `StringUtil.sha512`
  （`StringUtil.kt:10-14`）——函数名叫 sha512，但代码实际 `MessageDigest.getInstance("SHA-256")`，
  输出 64 位 hex，与 `doc/twig-versioning.md` 示例中 64 位 hash 一致；
- `hasVersioningComment` = block 上方是否存在 `{# shopware-block: ... #}` 注释
  （`:38` 调 `TwigUtil.getShopwareBlockComment`）。

版本注释的定位规则：`blockTag.parent.prevSibling?.prevSibling` 必须是 `TwigComment` 且文本含
`{# shopware-block:`（`TwigUtil.kt:210-229`）。注释内容按 `@` 切成 `hash` 与 `version`
两段（`:231-239`，恰好一个 `@` 才接受）。

注意 HashMap 语义（`:23`）：**同一文件内同名 block 只保留最后一个**（后者覆盖前者）。

### 3.4 deprecated 信息

`TwigBlockDeprecationIndex`（`TwigBlockDeprecationIndex.kt:18-54`）匹配
`TwigBlockStatement` 且其 `firstChild` 是 `TwigBlockTag`（`:28-29`），并检查
`element.prevSibling.prevSibling` 是否为含 `@deprecated` 的 `TwigComment`（`:31-34`）；
消息取 ` - ` 之后、`#}` 之前的文本（`:37-40`）。value 只有 `name/relPath/message`
（`dict/TwigDeprecation.kt:7`），**不含绝对路径**，因此 deprecated 匹配只能按 block 名 +
相对路径，无法区分同 view path 的多个文件（见 R5）。

### 3.5 externalize / 持久化

- 字符串 key/value 用 `EnumeratorStringDescriptor`（如 `ShopwareTemplateIndex.kt:35-40`）；
- 所有字典对象统一走 `ObjectStreamDataExternalizer`
  （`externalizer/ObjectStreamDataExternalizer.kt:7-31`）：Java 原生序列化 + 4 字节长度前缀；
  读取时 `ClassNotFoundException`/`ClassCastException` 被吞掉并返回 null（`:27-29`）。
- 每个索引有版本号（`getVersion()`）：bundle=7、app=4、hash=4、deprecation=3、两个模板索引=1。
  平台行为：`FileBasedIndex` 在索引版本或实现变化后会触发全量重建——这是升级插件后
  externalizer 读到旧 class 时的主要防线；但读取期返回 null 被吞的设计意味着反序列化失败
  会以“查不到数据”的形式出现（见 R6）。

## 4. 查询阶段：view path、bundle 名与优先级

### 4.1 按 view path 取文件

`ShopwareTemplateUtil.getTemplatesByViewPath`（`ShopwareTemplateUtil.kt:223-229`）：
`getContainingFiles(ShopwareTemplateIndex.key, viewPath, allScope)` 后再用
`endsWith("Resources/views/$templatePath")` 兜底（防止 key 碰撞）。

### 4.2 路径 -> bundle 名（不经过 bundle 索引）

`getBundleNameForPath`（`ShopwareTemplateUtil.kt:167-199`）用纯字符串规则推导 `@Xxx` 名：
- core `src/Storefront/...` -> `Storefront`（`:168-170`）；
- `custom/plugins/Foo/...` -> `Foo`，`custom/apps/Foo/...` 同理（`:172-178`）；
- `vendor/shopware/storefront/...` -> `Storefront`（camelize 第二段，`:188-189`）；
- `vendor/acme/example-plugin/...` -> `AcmeExamplePlugin`（两段拼接 camelize，`:190-191`）；
- 否则取 `Resources/views/` 前一段；末段是 `src` 时取再前一段（`:195-198`）。

### 4.3 同名模板的 bundle 过滤与“插件优先级”

`findTemplateInBundle`（`:17-21`）在同一 view path 的候选中先 `filterByBundle`
再 `templateOrder()` 取 `firstOrNull`：

- `filterByBundle`（`:231-248`）：先尝试路径段精确匹配（去 `- _ /`、小写后相等），找不到再
  退化为子串包含匹配（注释 :239-240 说明这是为覆盖 `vendor/acme/foo` 与 `@AcmeFoo` 的命名差异）；
- `templateOrder`（`:250-252`）：**core 模板降序优先，其余按绝对路径字典序**；
- `resolveTemplateReference`（`:44-72`）：导航时把“引用 bundle 的命中”排在前面，其后拼接同
  view path 的其它模板（runtime 继承链的静态近似）。

因此代码里的“优先级”是**静态路径启发式**，不是 Shopware runtime 的插件加载顺序。
`doc/twig-versioning.md` 的 Limitations 明确承认了这一点：runtime 链由插件加载顺序决定，
静态不可知，`sw_extends` 只是最佳近似。

### 4.4 vendor 与 custom/plugins 的区分

- `isShopwareCoreTemplate`（`TwigUtil.kt:60-62`）：含 `src/Storefront/Resources/views/storefront`
  或 `vendor/shopware/storefront/Resources/views/storefront`；
- `isUpstreamTemplate`（`:64-66`）：core，或任意含 `vendor/` 且含 `Resources/views/` 的路径。
  注意 `custom/plugins/...` **不算** upstream；
- composer 包名：`getComposerPackageByPath` 从 `vendor/` 后取前两段（`:196-208`）。

### 4.5 版本号来源

`getUpstreamPackageVersion`（`TwigUtil.kt:299-326`）：
- vendor 路径：`ComposerInstalledPackagesService.getCurrentPackageVersion(acme/pkg)`（`:300-304`）；
- 否则（custom/plugins 等）：从模板文件逐级向上找第一个 `composer.json`，读其中的
  `version` 字段，空则视为无版本（`:307-323`）。
`getVersioningComment`（`:265-297`）产出 `{# shopware-block: <hash>[@<version>] #}\n`。

## 5. 继承链、上游 block 与三个特殊情形

### 5.1 向上：sw_extends 链

`ShopwareTemplateUtil.followExtendsChain`（`ShopwareTemplateUtil.kt:121-147`）：
从初始 target 开始，循环解析 `@Bundle/view` -> `findTemplateInBundle` ->
`getExtendsTarget`（读 `ShopwareTemplateIndex` value，`:27-42`）；
- 起点之后最多 10 跳（`:128`），用 `visited` 集合断环（`:123,138`）；
- 任一跳在项目内找不到模板即 `break`，链可能为空。
文件自身的第一跳 target 来自 **PSI 文本正则**（`TwigUtil.getExtendsChainPaths`，
`TwigUtil.kt:79-92`），以尊重未保存编辑；后续跳全部来自索引，无需再加载文件
（见 `ShopwareTemplateUtil.kt:118-120` 注释）。结果按文件缓存，依赖
`PsiModificationTracker.MODIFICATION_COUNT`（`TwigUtil.kt:81-91`）。

### 5.2 向下：哪些模板继承我（反向 BFS）

`getTemplatesExtendingTemplate`（`ShopwareTemplateUtil.kt:78-108`）：
- 用 `ShopwareTemplateExtendsIndex.getContainingFiles(viewPath)` 取直接孩子
  （`getDirectChildren`，`:149-155`，排除自身并按 `templateOrder` 排序）；
- 多跳 BFS，深度上限 8（`:92`）；
- `visited` 初始化为“本文件的全部上游 + 自身”（`:84-85`），解决多个同级 override 互相指向
  同一 view path 导致“看起来互为孩子”的问题（注释 :81-83）。

### 5.3 上游 block 解析：链优先、同名路径兜底（核心不变量）

`TwigUtil.getUpstreamBlocks`（`TwigUtil.kt:162-194`）：
1. 从 `TwigBlockHashIndex` 取该 block 名的全部 value（allScope，`:174-176`）；
2. **主路径**：按继承链路径顺序匹配 `absolutePath`，链上第一个含此 block 的模板集合胜出
   （`:179-182`，nearest parent first）；
3. **fallback**：链上没有时，取 `relativePath` 相同、`absolutePath` 不同、且
   `!hasVersioningComment` 的候选（`:187-188`），按
   core 降序 -> vendor(upstream) 降序 -> 绝对路径升序 排序（`:189-193`）。

“带版本注释的 block 永远不是别人的上游”是贯穿全仓库的关键不变量，同样出现在
`getVersioningComment` 无 sourceFile 分支（`:278-285`）、`TwigBlockRemoved`
（`TwigBlockRemoved.kt:49`）和 hash-changed 检查。

下游 block 则由 `getDownstreamBlockPaths`（`TwigUtil.kt:139-151`）做交集：
继承我的模板路径 ∩ hash 索引中含该 block 名的文件绝对路径。

### 5.4 dumb mode（索引未就绪）

仓库事实：Twig 管线里**没有任何 `DumbService` 守卫、`runWhenSmart` 或 dumb-aware 标记**
（`rg "DumbService" src/main/kotlin` 仅命中与 Twig 无关的
`xml/AdminComponentProvider.kt:41`；四个 twig 索引、两个 goto handler、marker、四个
inspection、TwigUtil/ShopwareTemplateUtil 均无命中）。所有查询都直接调用
`FileBasedIndex.getValues/getContainingFiles/processValues`，且作用域一律是
`GlobalSearchScope.allScope(project)`。

平台行为（非仓库代码）：dumb mode 下访问 `FileBasedIndex` 会抛
`IndexNotReadyException`；平台一般在高亮/补全等框架入口处推迟 dumb-aware 的扩展，但
`GotoDeclarationHandler`/`RelatedItemLineMarkerProvider`/`LocalInspectionTool` 本身是否被
安全推迟取决于平台调用点。仓库代码没有自行防护，因此“大型项目重建索引期间”这条链上的
行为只能依赖平台默认调度（见 R3）。

### 5.5 vendor / custom/plugins / 独立仓库

- vendor 模板在 `isUpstreamTemplate` 中是上游，自身不会被要求加版本注释
  （`TwigBlockHashMissing.kt:15-18`）；
- custom/plugins 中的模板被视为普通扩展：若不含 extends 就不是 override
  （`:21-23` + `TwigUtil.isExtendingTemplate`），fixture 中有直接测试
  （`TwigBlockHashMissingTest.kt:82-90`）；
- 独立插件仓库（没有 Shopware 源码）：链解析为空，`TwigBlockRemoved` 直接整体关闭
  （`TwigBlockRemoved.kt:28-31`，测试 `:55-74`），避免把所有 block 误报为 removed。

### 5.6 同名模板覆盖

同一 view path 的多个文件全部进索引；“谁是父”由 §4.3 的 `templateOrder`
（core > 路径字典序）在链解析时选一，其余通过 §5.3 fallback 参与 block 匹配。
导航（`resolveTemplateReference`）则反过来把全部同名文件都提供给用户，引用 bundle 排最前。

### 5.7 注释掉的 extends

如 §3.2 实测：`{# ... #}` 内的 `sw_extends` 仍被正则抽取。后果：
- 该文件进入 `ShopwareTemplateExtendsIndex`（成为目标 view path 的“孩子”）；
- `isExtendingTemplate` 返回 true，`TwigBlockHashMissing` 会把它当 override 扫描；
- `followExtendsChain` 会尝试解析这个其实不存在的继承关系，找不到目标才中止。
代码没有基于 PSI `TwigComment` 的过滤（对比：block/hash/deprecation 抽取全部走 PSI visitor，
只有 extends 走文本正则）。仓库无对应 fixture 与测试。

## 6. 完整调用链（真实文件:行号）

### 链 A：block hash 变更 inspection（索引 -> 链/兜底 -> 告警）

1. 索引期：`TwigBlockHashIndex.getIndexer` 对每个 `TwigBlockTag` 存
   key=block 名，value 含 `sha256(blockStatement.text)` 与 `hasVersioningComment`
   （`src/main/kotlin/de/shyim/shopware6/index/TwigBlockHashIndex.kt:29-44`）。
2. 高亮期：`TwigBlockHashChanged.buildVisitor` 预计算 `chainPaths`
   （`src/main/kotlin/de/shyim/shopware6/inspection/twig/TwigBlockHashChanged.kt:20-21`）。
3. 每个带版本注释的 block（`:25`）调
   `TwigUtil.getUpstreamBlocks(project, filePath, chainPaths, name)`（`:28-29`）。
4. `TwigUtil.getUpstreamBlocks` 读 `FileBasedIndex.getValues(TwigBlockHashIndex.key, …)`
   （`src/main/kotlin/de/shyim/shopware6/util/TwigUtil.kt:174-176`），先按链匹配
   （`:179-182`），否则走“同相对路径 + 排除带注释 override + core/vendor/path 排序”
   fallback（`:187-193`）。
5. `extractShopwareBlockData` 解析注释中记录的 hash（`TwigUtil.kt:231-239`）。
6. 若所有上游候选 hash 都不等于记录值，`registerProblem` WARNING
   （`TwigBlockHashChanged.kt:38-44`）。

测试：`src/test/kotlin/de/shyim/shopware6/test/inspection/TwigBlockHashChangedTest.kt:14-41`
（变更）、`:43-77`（带注释的同级 override 不得用自身 hash 掩盖变更）、
`:79-106`（vendor 第三方候选任一匹配即可接受）。

### 链 B：block removed inspection + “仍在别处存在”消息

1. 仅 `TwigFile`（`TwigBlockRemoved.kt:19`）；先算 `chainPaths`，**为空则整个 inspection
     不运行**（`src/main/kotlin/de/shyim/shopware6/inspection/twig/TwigBlockRemoved.kt:28-31`）。
2. 对带版本注释的 block，链/同名路径上游为空才继续（`:35-40`，同样走
   `TwigUtil.getUpstreamBlocks`）。
3. 再全 scope 取该 block 名的所有 `TwigBlockHash`，排除自身文件与带版本注释者，
   `map(relativePath).distinct()`（`TwigBlockRemoved.kt:44-51`）。
4. 空 -> “has been removed”；非空 -> “removed from this template, but still exists in:
   <relPaths>”（`:53-67`）。

测试：`TwigBlockRemovedTest.kt:11-18`（removed + moved 两种文案）、
`:20-30`（MyPluginB 仍带注释地 override 不能掩盖移除）、
`:32-53`（链上的带注释主题仍是合法上游）、`:55-74`（无 Shopware 源码时不报）。

### 链 C：gutter line marker 双向导航（索引 -> BFS/交集 -> PSI）

向上箭头（Overrides block）：
1. `TwigBlockMarker.collectNavigationMarkers` 只在 block 标识符叶子上触发
   （`src/main/kotlin/de/shyim/shopware6/marker/twig/TwigBlockMarker.kt:23-33`）。
2. 门控：文件必须 `isExtendingTemplate` 且上游非空（`:40`）——注释 :37-39 说明没有此门控
   时 fallback 会把同 view path 的 override 误报为自己的上游。
3. 点击时 `upstreamTargets` -> `TwigUtil.getUpstreamBlocks` ->
   `findBlockTagsInFile` 打开上游 PSI 并定位 `TwigBlockTag`
   （`:61-66`；`TwigUtil.kt:94-112` 用 `ShopwareTemplateUtil.findTemplateByPath` +
   `PsiManager.findFile` + `PsiRecursiveElementWalkingVisitor`）。

向下箭头（Overridden in extending templates）：
1. `getDownstreamBlockPaths`（`TwigUtil.kt:139-151`）=
   `getExtendingTemplatePaths`（:118-132，带 per-file `CachedValue`）与
   hash 索引中含该 block 的绝对路径集合做交集；
2. `getExtendingTemplatePaths` 底层是
   `ShopwareTemplateUtil.getTemplatesExtendingTemplate`
   （`src/main/kotlin/de/shyim/shopware6/util/ShopwareTemplateUtil.kt:78-108`），
   直接孩子来自反向索引 `getDirectChildren`（`:149-155`）；
3. marker 目标惰性求值 `getDownstreamBlocks`（`TwigBlockMarker.kt:51-58`，
   `TwigUtil.kt:156-160`）。

测试：`src/test/kotlin/de/shyim/shopware6/test/marker/TwigBlockMarkerTest.kt:26-72`
（链顶/链中/链尾三个方向、无关联 block 无 marker、点击目标顺序 nearest first）。

### 链 D：missing-comment inspection -> quick fix -> 版本注释落盘

1. `TwigBlockHashMissing`：上游模板自身直接退出
   （`src/main/kotlin/de/shyim/shopware6/inspection/twig/TwigBlockHashMissing.kt:15-18`），
   非 extends 文件退出（`:21-23`）；无注释且上游存在的 block 注册 WARNING 并附带
   `AddMissingTwigVersioningCommentFix`（`:30-40`）。
2. quick fix：`AddMissingTwigVersioningCommentFix.applyFix` 取文件 view path 调
   `TwigUtil.addVersioningComment(blockTag, relativePath)`
   （`src/main/kotlin/de/shyim/shopware6/inspection/quickfix/twig/AddMissingTwigVersioningCommentFix.kt:12-18`）。
3. `addVersioningComment` 在写命令内替换/插入注释 PSI
   （`src/main/kotlin/de/shyim/shopware6/util/TwigUtil.kt:241-263`，
   `createVirtualTwigFile` 见 `:49-54`）。
4. 注释内容：`getVersioningComment` 经 `getUpstreamBlocks(sourceFile, …)`（链优先，`:271-286`）
   选 hash，再 `getUpstreamPackageVersion` 决定 `@version` 后缀（`:288-296, 299-326`）。

测试：`TwigBlockHashMissingTest.kt:11-115`（core/插件/vendor/custom theme/不同相对路径链/
只剩带注释 override/upstream 自身不报，共 8 例）；
版本选择测试 `src/test/kotlin/de/shyim/shopware6/test/util/TwigUtilTest.kt:14-76`。

### 链 E：模板引用 go-to-declaration 与补全

1. `TwigTemplateGoToDeclareHandler` 限定 `sw_extends`/`sw_include` 标签内第一个
   `STRING_TEXT`（`src/main/kotlin/de/shyim/shopware6/navigation/TwigTemplateGoToDeclareHandler.kt:18-36`）。
2. `ShopwareTemplateUtil.resolveTemplateReference` 拆 `@Bundle/path`
   （`ShopwareTemplateUtil.kt:44-58`），按 view path 取全部文件，bundle 命中优先
   （`:60-71`，`filterByBundle` 见 `:231-248`）。
3. handler 过滤当前文件自身并映射为 PSI（`TwigTemplateGoToDeclareHandler.kt:39-45`）。
4. block 名上的 Ctrl/Cmd+Click 走 `TwigBlockGoToDeclareHandler` ->
   `TwigUtil.getUpstreamBlocks` + `findBlockTagsInFile(...).firstOrNull()`
   （`src/main/kotlin/de/shyim/shopware6/navigation/TwigBlockGoToDeclareHandler.kt:19-30`）。
5. 补全：`TwigCompletionProvider` 在 extends/include 字符串处调
   `getTemplateLookupElements`（`src/main/kotlin/de/shyim/shopware6/completion/TwigCompletionProvider.kt:80-96`），
   后者遍历 `ShopwareTemplateIndex` 全部 key/文件，用 §4.2 规则拼 `@Bundle/view`，结果做
   project 级缓存，依赖 `VFS_STRUCTURE_MODIFICATIONS`
   （`ShopwareTemplateUtil.kt:157-221`）。

测试：`src/test/kotlin/de/shyim/shopware6/test/navigation/TwigNavigationTest.kt:21-107`
（全路径解析、排除自身、custom/plugins bundle、block 上游跳转、补全内容）。

### 链 F：deprecated inspection

`TwigBlockDeprecated.buildVisitor` 对每个 block 按名查 `TwigBlockDeprecationIndex`，
再比较 `deprecation.relPath == 当前文件 view path` 后以 `LIKE_DEPRECATED` 上报
（`src/main/kotlin/de/shyim/shopware6/inspection/twig/TwigBlockDeprecated.kt:24-42`）。
索引抽取见 `TwigBlockDeprecationIndex.kt:26-52`。测试：
`src/test/kotlin/de/shyim/shopware6/test/index/TwigBlockDeprecationIndexTest.kt:18-45` 与
`src/test/kotlin/de/shyim/shopware6/test/inspection/TwigBlockDeprecatedTest.kt:11-18`。

另：`ShowTwigBlockDifference.invoke` 从 hash 索引找 core 当前 block
（`src/main/kotlin/de/shyim/shopware6/intentions/ShowTwigBlockDifference.kt:84-88`），
按注释里的版本号从 `raw.githubusercontent.com/shopware/shopware/<ver>/...` 拉旧内容做 diff
（`:115-138`）；`isAvailable` 限定只有 core 模板可比（`:57-67`）。

## 7. 测试 fixture 与分支的对应

测试基类都是 `BasePlatformTestCase`，用 `copyDirectoryToProject` / `addFileToProject`
把夹具放进临时项目，索引由平台在文件写入后建立。

### 7.1 ShopwarePlatform（core 上游）

- `testData/marker/TwigBlockMarkerTest/ShopwarePlatform/src/Storefront/Resources/views/storefront/page/content/index.html.twig`
  — 含 `base_content` 与无人覆盖的 `base_untouched`，验证链顶只有“被覆盖”箭头。
- `testData/inspection/TwigBlockRemovedTest/ShopwarePlatform/.../page/content/index.html.twig`
  只有 `base_content`；另一个 `.../layout/header.html.twig` 含 `page_header`，制造
  “从本模板移除但仍存在于别处”的消息分支。
- `testData/inspection/TwigBlockHashChangedTest/ShopwarePlatform/...`（`base_content` +
  `base_other`）、`TwigBlockHashMissingTest/ShopwarePlatform/...`（`base_content` +
  `base_footer`）、`TwigBlockDeprecatedTest/ShopwarePlatform/.../page/index.html.twig`
  （`{# @deprecated tag:v6.7.0 - ... #}` + `old_block`）。
- 这些路径命中 `isShopwareCoreTemplate`（`TwigUtil.kt:60-62`），在所有排序中权重最高。

### 7.2 vendor theme / 第三方扩展

- `testData/inspection/TwigBlockHashChangedTest/vendor/acme/theme/Resources/views/.../index.html.twig`
  — 同 view path 的第三方 override，验证“hash 匹配任一上游候选即视为最新”
  （`TwigBlockHashChangedTest.kt:79-106`）与 `templateOrder`/fallback 的 vendor 层。
- `testData/inspection/TwigBlockHashMissingTest/vendor/acme/example-plugin/Resources/views/storefront/component/example.html.twig`
  — 第三方块 `acme_example_content`；`MyPlugin/.../component/example.html.twig` 以
  `@AcmeExamplePlugin/...` 引用它，验证 vendor 包名推导（`ShopwareTemplateUtil.kt:180-193`）
  与上游自身不报、override 报（`TwigBlockHashMissingTest.kt:92-107`）。

### 7.3 custom/plugins 与自定义插件

- `testData/marker/.../custom/plugins/TcinnTheme/src/Resources/views/.../page/content/index.html.twig`
  `sw_extends '@MyPlugin/...'`，构成 core <- MyPlugin <- TcinnTheme 三跳链的链尾。
- `testData/marker|navigation|inspection/.../MyPlugin(Other)/Resources/views/...`
  — 普通项目根内插件：`getBundleNameForPath` 末段非 `src` 分支
  （`ShopwareTemplateUtil.kt:195-198`）=> `@MyPlugin`/`@MyPluginOther`；
  `TwigBlockRemovedTest/MyPluginB/...` 验证兄弟 override 不掩盖 removed。
- `testData/util/TwigUtilTest/custom/plugins/TcinnTheme/composer.json` 含
  `"version": "2.5.0"`，验证 custom/plugins 版本来自 composer.json 逐级上溯
  （`TwigUtilTest.kt:14-22`）；`AaaCommentedPlugin/...` 名字在字典序上本应胜出，但因带版本
  注释被排除（`:24-40`，对应 `TwigUtil.kt:187-188`）；`NoVersionPlugin/composer.json`
  无 `version`，验证无 `@` 后缀（`:69-76`）。
- `testData/inspection/TwigBlockRemovedTest/custom/plugins/CommentedTheme/...` 自身带版本
  注释，但作为别人 `sw_extends` 链上的一环仍是合法上游（`TwigBlockRemovedTest.kt:32-53`，
  对应主路径不应用 fallback 的“排除带注释者”规则，`TwigUtil.kt:179-182`）。
- `testData/navigation/.../custom/plugins/TcinnTheme/.../themeware/example.html.twig`
  与临时文件 `@TcinnTheme/.../themeware/example.html.twig` 一起验证“不同相对路径的模板
  复用”只能通过链解析找到上游 block（另见 `TwigBlockHashMissingTest.kt:20-39`、
  `TwigUtilTest.kt:42-67`）。
- `testData/index/TwigBlockDeprecationIndexTest/MyApp/Resources/views/start-page/index.html.twig`
  验证 deprecation 抽取与嵌套注释里误含 `@deprecated` 不重复计数。

### 7.4 新增测试对应的分支

`src/test/kotlin/de/shyim/shopware6/test/marker/TwigUpstreamPriorityTest.kt`
（本次新增，见 §9）同时放置 core、`vendor/acme/theme`、`custom/plugins/TcinnTheme` 三个
同 view path 文件，再加一个 `sw_extends '@UnknownBundle/...'`（链解析为空）的
`MyPlugin` override，专门压中 §5.3 fallback 的“作用域=同 view path”与
“core > vendor > custom”排序。

## 8. 五个具体风险点

### R1. 注释掉的 extends 被当作真实继承（正则不看 PSI 注释）

- 触发布局：任意 `custom/plugins/Foo/.../x.html.twig` 中保留
  `{# {% sw_extends '@Storefront/storefront/page/x.html.twig' %} #}`（常见于临时注释掉继承
  调试，或注释里给示例）。
- 代码依据：`TwigUtil.kt:68-77` 的文本正则；已用相同 pattern 在 JDK 21 下验证该字符串会
  命中并返回 `@Storefront/...`。
- 可见现象：文件被写入 `twig_template_extends` 反向索引成为该 view path 的“孩子”，
  core 模板上出现“Overridden in extending templates”箭头并指向它；`isExtendingTemplate`
  返回 true，使 `TwigBlockHashMissing`（默认关闭）开始扫描该文件；链解析还会尝试跳转一个
  实际不存在的继承关系。
- 可能失效的键/缓存：`twig_template_extends` 的 key（view path）被污染；
  `getExtendingTemplatePaths`/`getExtendsChainPaths` 的 per-file `CachedValue`
  （`TwigUtil.kt:81-91,119-131`）会缓存错误结果直到 PSI 修改计数变化。
- 现有测试：**无**。`rg` 全 fixture 没有注释内 extends 用例；marker/inspection 测试也没有
  这类断言。

### R2. 静态“优先级”与 Shopware 运行期插件加载顺序不一致

- 触发布局：同一 view path 三个文件：core、`vendor/acme/theme/...`、
  `custom/plugins/TcinnTheme/...`，而运行期实际生效的父模板顺序由插件优先级决定
  （如 TcinnTheme 先于 acme theme 加载）。
- 代码依据：`ShopwareTemplateUtil.templateOrder`（`ShopwareTemplateUtil.kt:250-252`）
  只有“core 优先 + 绝对路径字典序”；`findTemplateInBundle` 取 `firstOrNull()`（`:17-21`）；
  `filterByBundle` 在精确段匹配失败时退化到子串包含（`:243-247`）。
- 可见现象：链追踪、block go-to-declaration 的第一目标、`getVersioningComment` 记录的
  hash（`TwigUtil.kt:282-285` 的排序与之等价）都可能选到运行期并非直接父模板的文件；
  字典序上“像 bundle 名”的无关目录还可能被子串匹配选中。
- 可能失效的键/缓存：`twig_templates`（view path -> extends value）本身正确，但解析侧的
  `getExtendsChainPaths` per-file 缓存会固化错误的第一跳；补全侧 project 级缓存
  （`ShopwareTemplateUtil.kt:159-164`，依赖 `VFS_STRUCTURE_MODIFICATIONS`）只缓存展示用
  reference，不影响解析。
- 现有测试：部分覆盖。`TwigBlockMarkerTest` 覆盖显式三跳链（运行顺序与静态链一致的情形）；
  `TwigNavigationTest.testTemplateNavigationResolvesAllTemplatesOfThePath` 只断言引用
  bundle 第一、其余第二；**“多个 bundle 同时精确命中同 view path 时谁是第一跳父模板”没有
  测试**，且 `doc/twig-versioning.md` Limitations 已自认这是静态近似。

### R3. dumb mode / 重建期间无守卫，查询直接打索引

- 触发布局：大型项目首次打开、插件升级后索引版本变化（如 hash 索引版本 4，
  `TwigBlockHashIndex.kt:58-60`）或 “Invalidate Caches” 后，dumb mode 期间用户点击
  block、触发高亮或补全。
- 代码依据：Twig 四个 inspection、两个 goto handler、marker、两个 Util 中均无
  `DumbService`（`rg DumbService src/main/kotlin` 仅命中无关的
  `xml/AdminComponentProvider.kt:41`）；所有调用点直接用
  `FileBasedIndex.getValues/getContainingFiles/processValues`（如
  `TwigUtil.kt:146-147,174-175`、`ShopwareTemplateUtil.kt:30-39,150-154,206-218`）。
- 可见现象：取决于平台对各扩展点的 dumb 调度（平台行为），最坏是
  `IndexNotReadyException` 被平台记录为插件错误；确定的仓库内后果是
  `TwigBlockRemoved.kt:28-31` 这类“链为空就静默不检查”在重建中途会随机表现为“什么都不报”。
- 可能失效的键/缓存：四个 twig 索引整体不可读；PSI 侧 `CachedValue` 在索引重建后是否清掉
  依赖其 tracker——`getExtendsChainPaths`/`getExtendingTemplatePaths` 挂的是
  `PsiModificationTracker.MODIFICATION_COUNT`，外部文件变化（如 vendor `composer update`
  导致索引更新）不会推进该计数，缓存可能短暂保留旧链。
- 现有测试：**无** dumb-mode 测试（fixture 都在索引就绪后断言）。

### R4. 同文件内同名 block 互相覆盖，且 fallback 只按相对路径

- 触发布局：一个模板里写两个同名 `{% block foo %}`（复制粘贴/合并冲突残留），或两个不同
  目录的模板通过软链/重复 content root 拥有相同绝对 view 后缀。
- 代码依据：`TwigBlockHashIndex.kt:23` 使用 `HashMap<String, TwigBlockHash>`，`:32`
  以 block 名为键重复 put——只留下最后一个；`getUpstreamBlocks` fallback 仅比较
  `relativePath`（`TwigUtil.kt:187-188`），`TwigBlockDeprecated` 也只按
  `relPath` 关联（`TwigBlockDeprecated.kt:31-34`）。
- 可见现象：前一个同名 block 的 hash 不参与 changed/removed 判断；deprecated 消息在同名
  block 上重复出现，且无法区分消息来自哪个物理文件（deprecation value 无绝对路径，
  `dict/TwigDeprecation.kt:7`）。
- 可能失效的键/缓存：`twig_hash` 单文件 value 被截断；`twig_blocks` 的 relPath 关联跨文件
  串味。
- 现有测试：**无**同文件重名 block 用例；deprecation 只测单文件单块
  （`TwigBlockDeprecationIndexTest.kt`）。

### R5. 第三方未版本化 override 会“保住”已移除的 core block

- 触发布局：core 删除了 `page_foo`；项目中还有一个 vendor 插件
  `vendor/acme/legacy/Resources/views/<同 view path>.html.twig` 仍含无注释的
  `{% block page_foo %}`。
- 代码依据：`TwigBlockRemoved.kt:44-51` 只排除带版本注释者，任何无注释的同名 block 都会
  让 `otherLocations` 非空，消息降级为“still exists in …”；`doc/twig-versioning.md`
  Limitations 第三条明确记录了这一取舍。
- 可见现象：本该提示“override 可能是死代码”，实际只提示“从该模板移除但仍存在于
  storefront/…（第三方路径）”，用户可能误以为 core 仍有该 block。反向场景同理：无注释的
  第三方块还会让 missing-comment 对所有 override 报错（`TwigBlockHashMissing.kt:30-33`）。
- 可能失效的键/缓存：`twig_hash` 中该 block 的“存在性”被第三方文件维持；无额外缓存。
- 现有测试：**只覆盖一半**。`TwigBlockRemovedTest.testCommentedOverrideOfAnotherPlugin...`
  （`:20-30`）覆盖“带注释者不能证明存在”，但**没有**“无注释第三方块掩盖 core 移除”的用例；
  hash-changed 侧的“any candidate 匹配即可”有正向覆盖
  （`TwigBlockHashChangedTest.kt:79-106`），removed 侧缺对称测试。

### 附：R6. ObjectStream 反序列化失败被静默吞掉

`ObjectStreamDataExternalizer.read` 捕获 `ClassNotFoundException`/`ClassCastException` 后
返回 null（`ObjectStreamDataExternalizer.kt:24-30`）。升级后若 `getVersion()` 忘记 bump，
查询侧会把 null/缺值当成“没有上游/没有 deprecated”，表现为 marker 与 inspection 全部静默，
而不是显式报错。无测试覆盖（现有 `TwigBlockDeprecationIndexTest.kt:19-21` 的注释只提到
测试间 stale key 现象）。

## 9. 新增的最小测试

文件：`src/test/kotlin/de/shyim/shopware6/test/marker/TwigUpstreamPriorityTest.kt`

- **选取的不变量（代码已证实）**：当 `sw_extends` 链无法解析时，`getUpstreamBlocks`
  fallback 的作用域是“同一 view path 的其它文件、排除自身、排除带版本注释者”，优先级为
  Shopware core > `vendor/` > `custom/plugins`（`TwigUtil.kt:187-193`）。这一排序同时被
  marker、go-to-declaration、missing/changed/removed inspection 与
  `getVersioningComment` 复用，是比字符串 helper 更关键的集成不变量。
- **同时经过索引与消费方**：
  - 索引侧直接查 `FileBasedIndex.getValues(TwigBlockHashIndex.key, "base_content",
    allScope)`，断言 4 个文件全部按 block 名入库；
  - 消费方打开 override 文件，读取真实 gutter marker，断言只有一个 “Overrides block”
    marker，且其 3 个导航目标按 core -> vendor -> custom 排序、不含自身。
- 该 fallback 排序此前没有测试：现有 `TwigBlockHashChangedTest` 只断言“任一候选 hash
  匹配即可”，`TwigBlockMarkerTest` 走的是显式三跳链（主路径），都不压中无链 fallback。

## 10. 索引重建与验证记录

- 平台行为：`FileBasedIndexExtension.getVersion()` 提升或插件更新后，平台会丢弃旧索引
  全量重建；本仓库的应对仅是各索引的版本号（见 §3.5）与 `dependsOnFileContent=true`，
  没有自定义的重建监听或启动后重扫动作。
- 安装验证：`./gradlew classes` 成功（JDK 21 toolchain 来自
  `~/.gradle/jdks/eclipse_adoptium-21-...`；compileKotlin/Java 命中构建缓存）。
- 测试验证：`./gradlew test` 全绿，15 个测试类共 43 个用例（含新增 1 个），
  0 failure / 0 error。报告位于 `build/test-results/test/`、HTML 报告位于
  `build/reports/tests/test/`。

## 附：关键文件速查

- 索引：`src/main/kotlin/de/shyim/shopware6/index/ShopwareTemplateIndex.kt`、
  `ShopwareTemplateExtendsIndex.kt`、`TwigBlockHashIndex.kt`、
  `TwigBlockDeprecationIndex.kt`
- 字典/externalizer：`index/dict/TwigBlockHash.kt`、`index/dict/TwigDeprecation.kt`、
  `index/externalizer/ObjectStreamDataExternalizer.kt`
- 查询与缓存：`util/TwigUtil.kt`、`util/ShopwareTemplateUtil.kt`、
  `util/StringUtil.kt`
- UI：`navigation/TwigTemplateGoToDeclareHandler.kt`、
  `navigation/TwigBlockGoToDeclareHandler.kt`、`marker/twig/TwigBlockMarker.kt`、
  `inspection/twig/{TwigBlockHashChanged,TwigBlockHashMissing,TwigBlockRemoved,TwigBlockDeprecated}.kt`、
  `inspection/quickfix/twig/AddMissingTwigVersioningCommentFix.kt`、
  `intentions/{AddTwigVersioningIntention,ExtendTwigBlockIntention,ShowTwigBlockDifference}.kt`、
  `completion/TwigCompletionProvider.kt`
- 注册：`src/main/resources/META-INF/plugin.xml:41-56,194-267`
- 设计文档：`doc/twig-versioning.md`
