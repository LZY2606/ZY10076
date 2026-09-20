# Shopware 6 Toolbox：Twig 模板索引与 UI 消费链分析

本文只陈述仓库当前代码可证实的事实（行号基于工作区快照 `f4d2348`）。IntelliJ 平台行为仅在代码显式调用其 API 时引用，不做行为推测。

## 1. 总览：参与本链路的四个 FileBasedIndex 与两个扩展索引

`src/main/resources/META-INF/plugin.xml:41-44` 注册了四个 Twig 相关的 `fileBasedIndex`：

| 索引 ID（代码常量） | 实现 | 键 | 值 | 版本 |
| --- | --- | --- | --- | --- |
| `de.shyim.shopware6.frontend.twig_templates` (`ShopwareTemplateIndex.key`) | `src/main/kotlin/de/shyim/shopware6/index/ShopwareTemplateIndex.kt:58` | `Resources/views/` 之后的相对路径（view path） | 文件内 `sw_extends`/`extends` 的目标引用字符串，空串表示不继承 | `getVersion() = 1`（`ShopwareTemplateIndex.kt:44`） |
| `de.shyim.shopware6.frontend.twig_template_extends` (`ShopwareTemplateExtendsIndex.key`) | `src/main/kotlin/de/shyim/shopware6/index/ShopwareTemplateExtendsIndex.kt:68` | 被继承目标的 view path（剥掉 `@Bundle/` 前缀） | `Void`（纯倒排，存在即有意义） | 1（`ShopwareTemplateExtendsIndex.kt:54`） |
| `de.shyim.shopware6.frontend.twig_hash` (`TwigBlockHashIndex.key`) | `src/main/kotlin/de/shyim/shopware6/index/TwigBlockHashIndex.kt:72` | block 名称 | `TwigBlockHash`（name、relativePath、absolutePath、hash、全文 text、hasVersioningComment） | 4（`TwigBlockHashIndex.kt:58`） |
| `de.shyim.shopware6.frontend.twig_blocks` (`TwigBlockDeprecationIndex.key`) | `src/main/kotlin/de/shyim/shopware6/index/TwigBlockDeprecationIndex.kt:82` | block 名称 | `TwigDeprecation`（name、relPath、message） | 3（`TwigBlockDeprecationIndex.kt:60`） |

扩展（bundle/app）发现本身也走索引：

- `de.shyim.shopware6.backend.shopware-bundles`：`src/main/kotlin/de/shyim/shopware6/index/ShopwareBundleIndex.kt:86`，扫描 PHP 文件，凡是非 abstract 且直接继承 `\Shopware\Core\Framework\Bundle` 或 `\Shopware\Core\Framework\Plugin` 的类（`ShopwareBundleIndex.kt:94-108`）都产出一条 key 为 `"all"` 的 `ShopwareBundle`（`ShopwareBundleIndex.kt:51-58`），其中 `viewPath = <类文件目录>/Resources/views/`（`ShopwareBundleIndex.kt:48-50`），`rootFolder` 沿目录向上找到第一个含 `composer.json` 的目录（`ShopwareBundleIndex.kt:69-75`）。测试/fixture 路径与 `*Test` 文件被排除（`ShopwareBundleIndex.kt:110-126`）。
- `de.shyim.shopware6.backend.shopware-app`：`src/main/kotlin/de/shyim/shopware6/index/ShopwareAppIndex.kt:98`，只接受路径含 `custom/apps` 的 `manifest.xml`（`ShopwareAppIndex.kt:38-45`）。
- 两者通过 `ShopwareExtensionUtil.getAllExtensions` 合并（`src/main/kotlin/de/shyim/shopware6/util/ShopwareExtensionUtil.kt:10-11`）；与 storefront 视图有关的查询还会过滤掉 `Administration/DevOps/Checkout/Profiling/Elasticsearch/Content/System/Framework` 这些无视图 bundle（`src/main/kotlin/de/shyim/shopware6/util/ShopwareBundleUtil.kt:18-29`）。

注意：四个 Twig 索引的输入过滤只按文件类型 `TwigFileType.INSTANCE`（如 `ShopwareTemplateIndex.kt:48-51`），路径门槛在 indexer 内部用字符串 `path.contains("Resources/views/")` 二次判断（`ShopwareTemplateIndex.kt:25`、`ShopwareTemplateExtendsIndex.kt:27`、`TwigBlockHashIndex.kt:25`；deprecation 用的是 `"Resources/views"`，无尾斜杠，`TwigBlockDeprecationIndex.kt:22`）。它们**不依赖** bundle 索引；bundle 索引只服务"当前文件属于哪个 bundle/viewPath"等映射（`TwigUtil.getTemplatePathByFilePath` / `getBundleByFilePath`，`src/main/kotlin/de/shyim/shopware6/util/TwigUtil.kt:27-47`）以及"Extend block"intention（`ExtendTwigBlockIntention.kt:65-80`）。

## 2. 数据怎样从虚拟文件进入索引

四个索引都是 `FileBasedIndexExtension`，`dependsOnFileContent() = true`（如 `ShopwareTemplateIndex.kt:53`），由平台按 `TwigFileType` 把 `FileContent` 喂给 indexer。

### 2.1 view path 与 extends 目标（文本抽取，不走 PSI）

- view path：`TwigUtil.getRelativePath` = `path.substringAfter("Resources/views/")`（`src/main/kotlin/de/shyim/shopware6/util/TwigUtil.kt:56-58`）。
- extends 目标：对 `inputData.contentAsText` 跑正则 `EXTENDS_TARGET_PATTERN`（`TwigUtil.kt:69`），要求形如 `'@BundleName/相对路径'` 或双引号；`findExtendsTargetReference` 返回第一个匹配的捕获组（`TwigUtil.kt:75-77`）。
- `ShopwareTemplateIndex` 存 `viewPath -> 目标引用或 ""`（`ShopwareTemplateIndex.kt:29-32`）。
- `ShopwareTemplateExtendsIndex` 存反向关系，并显式剥掉 bundle：`target.substringAfter("/", "")`，只按 view path 入索引；注释说明"覆盖模板按 bundle 引用，但运行时继承该 view path 下所有模板"（`ShopwareTemplateExtendsIndex.kt:34-42`）。

### 2.2 block 名称、hash、版本注释标记（走 PSI）

`TwigBlockHashIndex` 遍历 `inputData.psiFile`（`TwigBlockHashIndex.kt:29`），对每个 `TwigBlockTag` 写一条（`TwigBlockHashIndex.kt:31-39`）：

- 键：block 名；
- 值：`TwigBlockHash(name, relativePath, file.path, sha512(element.parent.text), element.parent.text, getShopwareBlockComment(element) !== null)`（数据类 `src/main/kotlin/de/shyim/shopware6/index/dict/TwigBlockHash.kt:5-12`）。
- 两个易错的仓库事实：
  1. hash 输入是 **block 语句父节点的全文**（`{% block %}…{% endblock %}`），不是只取名字；
  2. 方法名叫 `sha512`，实现却是 **SHA-256**：`MessageDigest.getInstance("SHA-256")`（`src/main/kotlin/de/shyim/shopware6/util/StringUtil.kt:10-14`）。`doc/twig-versioning.md` 中"SHA-512"的说法与代码不符；测试里 hash 全部从索引取真值（如 `src/test/kotlin/de/shyim/shopware6/test/inspection/TwigBlockHashChangedTest.kt:18-22`），没有任何测试锁定算法。
- 版本注释判定：`getShopwareBlockComment` 要求注释节点是 block tag 父节点的 `prevSibling.prevSibling`、类型为 `TwigComment`、文本含 `{# shopware-block:`（`TwigUtil.kt:210-229`）；解析出 `hash@version` 用 `extractShopwareBlockData`（`TwigUtil.kt:231-239`）。

### 2.3 deprecation（走 PSI，依赖兄弟节点布局）

`TwigBlockDeprecationIndex` 对 `TwigBlockStatement`，检查 `element.prevSibling.prevSibling is TwigComment` 且文本含 `@deprecated`（`TwigBlockDeprecationIndex.kt:28-34`）；消息取 `" - "` 之后、`#}` 之前（`TwigBlockDeprecationIndex.kt:37-40`）；值为 `TwigDeprecation(name, relPath, message)`（`TwigBlockDeprecationIndex.kt:42-43`，数据类 `src/main/kotlin/de/shyim/shopware6/index/dict/TwigDeprecation.kt:7`）。

### 2.4 externalize（磁盘上怎么存）

- 键与字符串值用平台 `EnumeratorStringDescriptor`（如 `ShopwareTemplateIndex.kt:36-42`），倒排索引用 `VoidDataExternalizer`（`ShopwareTemplateExtendsIndex.kt:50-52`）。
- `TwigBlockHash` / `TwigDeprecation` / `ShopwareBundle` 等用自定义 `ObjectStreamDataExternalizer`：Java `ObjectOutputStream` 序列化后先写 4 字节长度再写字节（`src/main/kotlin/de/shyim/shopware6/index/externalizer/ObjectStreamDataExternalizer.kt:9-15`）；读取时按长度 `readFully` 再 `readObject`，`ClassNotFoundException`/`ClassCastException` 被吞掉返回 null（同文件 `18-31`）。因此这些值是 Java 序列化格式，类结构变化必须配合 `getVersion()` 提升，否则旧值读出为 null。

## 3. 数据怎样被查询：view path、extends 链、上下游 block

查询入口集中在 `src/main/kotlin/de/shyim/shopware6/util/ShopwareTemplateUtil.kt` 与 `TwigUtil.kt`，全部使用 `GlobalSearchScope.allScope(project)`。

**按 view path 找模板**：`getTemplatesByViewPath` 用 `ShopwareTemplateIndex.key` 取 `getContainingFiles`，并额外用 `path.endsWith("Resources/views/$templatePath")` 过滤（`ShopwareTemplateUtil.kt:223-229`）。同名 view path 天然可能返回多个文件（核心、vendor、custom/plugins 各一份）。

**按 bundle 过滤与排序（即"插件优先级"的静态近似）**：

- `filterByBundle`：先找路径段与 bundle 名规范化后**精确相等**的候选；找不到才退化为子串包含匹配，注释明确说是为了覆盖 vendor 包（`@AcmeFoo -> vendor/acme/foo`）（`ShopwareTemplateUtil.kt:231-248`）。规范化是去掉 `-`、`_`、`/` 再小写（`ShopwareTemplateUtil.kt:254-256`）。
- `templateOrder`：核心模板（`TwigUtil.isShopwareCoreTemplate`）排在最前，其余按绝对路径字典序（`ShopwareTemplateUtil.kt:250-252`）。
- `isShopwareCoreTemplate`：路径含 `src/Storefront/Resources/views/storefront` 或 `vendor/shopware/storefront/Resources/views/storefront`（`TwigUtil.kt:60-62`）；`isUpstreamTemplate` = 核心 或 路径含 `vendor/` 且含 `Resources/views/`（`TwigUtil.kt:64-66`）。
- bundle 名从路径反推：核心固定为 `Storefront`；`custom/plugins/<Name>`、`custom/apps/<Name>`；vendor 下 `shopware/<x>` 映射为 `<X>`，其它 `acme/foo-bar` 映射为 `AcmeFooBar`；最后回退到 `Resources/views/` 之前的目录名（`ShopwareTemplateUtil.getBundleNameForPath`，`ShopwareTemplateUtil.kt:167-199`，`camelize` 在 `258-262`）。

仓库中**不存在运行时插件优先级的读取**（无 plugin.xml/bootstrap/加载顺序解析）。静态近似规则只有两条：核心优先、同 view path 下路径字典序；`doc/twig-versioning.md` 的 Limitations 也声明"运行时链由插件加载顺序决定，静态不可知，sw_extends 引用是最佳近似"。

**向上的 extends 链**：`followExtendsChain` 从给定目标引用开始，每跳一次 `findTemplateInBundle` 解析父模板，再用 `getExtendsTarget` 从索引读父模板自己的目标，最近父在前，最多 10 跳，靠 `visited` 破环（`ShopwareTemplateUtil.kt:121-147`）。`getExtendsTarget` 用 `processValues` 读 `ShopwareTemplateIndex` 的值（`ShopwareTemplateUtil.kt:27-42`）。`TwigUtil.getExtendsChainPaths(PsiFile)` 第一跳直接对当前 PSI 文本跑正则（未保存编辑也生效），之后走索引，并把结果按 `PsiModificationTracker.MODIFICATION_COUNT` 缓存在文件上（`TwigUtil.kt:79-92`）。

**向下的子模板图**：`getTemplatesExtendingTemplate` 用 `ShopwareTemplateExtendsIndex` 的 `getDirectChildren`（`ShopwareTemplateUtil.kt:149-155`）做 BFS；初始 `visited` 包含自身与**向上链**，避免"同 view path 的兄弟覆盖互相看起来像父子"（`ShopwareTemplateUtil.kt:78-108`），深度上限 8（`ShopwareTemplateUtil.kt:92`）。

**上游 block 解析（消费方共用的核心不变量）**：`TwigUtil.getUpstreamBlocks(project, filePath, chainPaths, blockName)`（`TwigUtil.kt:168-194`）：

1. 先在 `TwigBlockHashIndex` 按 block 名取全量值（`TwigUtil.kt:174-175`）；
2. 主路径：extends 链上每个路径 `firstOrNull { it.absolutePath == path }`，命中即返回，最近父在前（`TwigUtil.kt:177-182`）——**注意链上只取每个路径的第一个值**；
3. 回退路径：`relativePath == 当前文件相对路径 && absolutePath != 当前文件 && !hasVersioningComment`，再按"核心 → vendor → 路径"降序（`TwigUtil.kt:184-193`）。

`!hasVersioningComment` 是本仓库最关键的优先级不变量：**带版本注释的同路径 block 永远不算上游**，防止兄弟插件用自己的 override hash 掩盖上游变更/删除。`getVersioningComment` 无 source file 时也用同样的过滤与排序取 `.firstOrNull()`（`TwigUtil.kt:278-286`）。

**下游 block**：`getDownstreamBlockPaths` = 子模板路径 ∩ 索引中含同名 block 的 `absolutePath` 集合（`TwigUtil.kt:139-151`）；两者都按文件缓存（`TwigUtil.kt:118-132`，依赖 `MODIFICATION_COUNT`）。

**版本号来源**：vendor 路径取 composer 包名（`vendor/<a>/<b>`，`TwigUtil.kt:196-208`）并查 `ComposerInstalledPackagesService`（`TwigUtil.kt:299-304`）；否则从模板向上逐级找 `composer.json` 读其 `version` 字段（`TwigUtil.kt:306-323`）。注释形态为 `{# shopware-block: <hash>[@<version>] #}\n`（`TwigUtil.kt:288-296`）。

## 4. 三条完整调用链（文件:行号）

### 链路 A：Ctrl/Cmd-Click `sw_extends` / `sw_include` 字符串 → 所有同名模板

1. 注册：`plugin.xml:55`（`TwigTemplateGoToDeclareHandler`）。
2. `TwigTemplateGoToDeclareHandler.getGotoDeclarationTargets` 限定 `STRING_TEXT` + `TAG`，标签名必须是 `sw_extends`/`sw_include`，且只接受标签内第一个字符串（避免 `with` 里的字符串）：`src/main/kotlin/de/shyim/shopware6/navigation/TwigTemplateGoToDeclareHandler.kt:18-36`。
3. `ShopwareTemplateUtil.resolveTemplateReference`：拆 `@Bundle/path`（`ShopwareTemplateUtil.kt:44-58`）→ `getTemplatesByViewPath` 读 `ShopwareTemplateIndex`（`ShopwareTemplateUtil.kt:60`、`223-229`）→ 无 bundle 时按 `templateOrder()` 全量返回；有 bundle 时引用 bundle 的候选排前，其余同名模板接后（`ShopwareTemplateUtil.kt:62-71`、`231-252`）。
4. 过滤掉当前文件自身、映射为 PSI 返回：`TwigTemplateGoToDeclareHandler.kt:39-45`。
测试：`src/test/kotlin/de/shyim/shopware6/test/navigation/TwigNavigationTest.kt:21-38`（核心在前、`MyPluginOther` 在后，共 2 个目标）、`40-55`（排除自身）、`57-72`（custom/plugins 的 `@TcinnTheme`）。

### 链路 B：缺版本注释 inspection → quick fix 写注释 → hash 索引取值

1. 注册：inspection `Shopware6TwigBlockCommentMissing`（默认关闭）`plugin.xml:216-223`。
2. `TwigBlockHashMissing.buildVisitor`：上游文件（vendor / `src/Storefront`）直接跳过（`src/main/kotlin/de/shyim/shopware6/inspection/twig/TwigBlockHashMissing.kt:15-18`，判定在 `TwigUtil.kt:64-66`）；文件必须能正则匹配到 extends（`TwigBlockHashMissing.kt:20-23`，`TwigUtil.isExtendingTemplate` 在 `TwigUtil.kt:71-73`）；visitor 对无 `shopware-block` 注释的 block 调 `getUpstreamBlocks(..., chainPaths, ...)`，非空才报 WARNING 并挂 `AddMissingTwigVersioningCommentFix`（`TwigBlockHashMissing.kt:28-43`）。
3. 数据来源：`getUpstreamBlocks` 读 `TwigBlockHashIndex`（`TwigUtil.kt:174-175`），链优先、同路径回退并排除带注释者（`TwigUtil.kt:177-193`）；链本身来自 `ShopwareTemplateIndex`（`ShopwareTemplateUtil.kt:27-42`、`121-147`）。
4. quick fix：`AddMissingTwigVersioningCommentFix.applyFix` 取相对路径调 `TwigUtil.addVersioningComment`（`src/main/kotlin/de/shyim/shopware6/inspection/quickfix/twig/AddMissingTwigVersioningCommentFix.kt:12-19`）。
5. `addVersioningComment` 在写命令中插入/替换注释（`TwigUtil.kt:241-263`）；注释内容由 `getVersioningComment` 产出：取上游 block hash + composer 版本（`TwigUtil.kt:265-297`、`299-326`）。

### 链路 C：line marker / go-to block / removed inspection 共用上下游解析

1. marker 注册 `plugin.xml:267`，实现 `src/main/kotlin/de/shyim/shopware6/marker/twig/TwigBlockMarker.kt:18-59`：
   - "Overrides block"：文件被正则判定为 extending 且 `getUpstreamBlocks` 非空才显示（`TwigBlockMarker.kt:40-47`），注释解释了为何必须先判 extending——否则同路径回退会把 override 误报成上游（`TwigBlockMarker.kt:37-40`）；
   - "Overridden in extending templates"：`getDownstreamBlockPaths` 非空即显示，路径全部来自索引，点击才加载 PSI（`TwigBlockMarker.kt:49-58`、`TwigUtil.kt:139-151`）。
2. block 名跳转注册 `plugin.xml:56`，`TwigBlockGoToDeclareHandler` 对标识符直接调 `getUpstreamBlocks` 并在每个上游文件里找同名 block（`src/main/kotlin/de/shyim/shopware6/navigation/TwigBlockGoToDeclareHandler.kt:19-30`，PSI 查找在 `TwigUtil.kt:94-112`）。
3. removed inspection：extends 链为空时整体不报（独立插件仓库没有 Shopware 源码的情形，`src/main/kotlin/de/shyim/shopware6/inspection/twig/TwigBlockRemoved.kt:25-31`）；带注释 block 的链上上游为空时，再扫全项目同 block 名、排除自身与带注释者，按相对路径去重决定"已删除"还是"仍存在于其他模板"（`TwigBlockRemoved.kt:42-67`）。
4. hash-changed inspection 与 deprecated inspection：前者要求 `upstreamBlocks.none { it.hash == 注释 hash }` 才报（`src/main/kotlin/de/shyim/shopware6/inspection/twig/TwigBlockHashChanged.kt:25-44`）；后者读 `TwigBlockDeprecationIndex`，只按 **relPath 相等** 触发（`src/main/kotlin/de/shyim/shopware6/inspection/twig/TwigBlockDeprecated.kt:26-40`）。

## 5. 边界情形：仓库代码实际如何处理

**dumb mode（索引未就绪/重建中）**：本链路的导航、inspection、marker、intention 中没有任何 `DumbService` 守卫（对 `src/main/kotlin/de/shyim/shopware6/{navigation,inspection,marker,intentions,completion}` 全目录 grep `Dumb` 无命中；全仓库唯一的 `DumbService.isDumb` 用法在不相关的 `src/main/kotlin/de/shyim/shopware6/xml/AdminComponentProvider.kt:41`）。也就是说 dumb 期间这些消费方照常发起 `FileBasedIndex` 查询，是否可用/返回什么完全交给平台默认行为，插件自身不做 `runWhenSmart` 延迟。历史上 CHANGELOG 记录过一次"索引未就绪/dumb mode 下访问索引"的修复（`CHANGELOG.md:71`，PR #232），但当前 Twig 代码里没有对应守卫。

**索引重建**：四个索引均 `dependsOnFileContent() = true`，内容变更由平台触发重索引；代码内没有手工 `requestRebuild` / `scheduleRebuild` 调用。版本号是唯一的全量重建旋钮：twig 模板/extends 为 1、hash 为 4、deprecation 为 3、bundle 为 7（见第 1 节行号）。自定义值依赖 Java 序列化（第 2.4 节），改类结构需升版本。

**vendor 与 custom/plugins 路径**：识别全是字符串包含判断，不要求文件属于某个 content root：

- 入索引门槛：路径含 `Resources/views/`（`ShopwareTemplateIndex.kt:25` 等），所以 `vendor/acme/.../Resources/views/...` 与 `custom/plugins/Foo/src/Resources/views/...` 都入索引；app 模板的扩展发现另有 `custom/apps` 白名单（`ShopwareAppIndex.kt:38-45`）。
- "谁是上游"：vendor 下任何含 `Resources/views/` 的文件都算 upstream（`TwigUtil.kt:64-66`），missing-comment inspection 因此跳过它们（`TwigBlockHashMissing.kt:15-18`）。测试 `testThirdPartyExtensionTemplatesThemselvesAreNotReported` 直接覆盖（`src/test/kotlin/de/shyim/shopware6/test/inspection/TwigBlockHashMissingTest.kt:101-107`）。
- 版本来源双轨：vendor 走 composer 已安装包版本；custom/plugins 走上溯 `composer.json` 的 `version`（`TwigUtil.kt:299-323`）。

**同名模板覆盖（同 view path 多文件）**：`getTemplatesByViewPath` 一次返回全部（`ShopwareTemplateUtil.kt:223-229`）。排序只区分核心与非核心，其余路径字典序（`ShopwareTemplateUtil.kt:250-252`），没有真实插件优先级。导航把"引用 bundle 命中"排前、其它同名文件接后（`ShopwareTemplateUtil.kt:66-71`，测试 `TwigNavigationTest.kt:21-38`）。block 层面对同路径多上游采取"hash 匹配任意一个即视为最新"（`TwigBlockHashChanged.kt:38`，测试 `TwigBlockHashChangedTest.kt:79-106`）；记录新注释时取排序后的第一个（`TwigUtil.kt:278-286`）。

**注释掉的 extends**：代码没有任何"注释内忽略"的处理——extends 的存在性与目标都由对**原始文本**的正则决定（`TwigUtil.kt:68-77`），不是 PSI 语法树。经等价正则实测：

| 文本 | `isExtendingTemplate` | 抽取到的目标 |
| --- | --- | --- |
| `{% sw_extends '@Storefront/a.html.twig' %}` | 是 | `@Storefront/a.html.twig` |
| `{# {% sw_extends '@Storefront/a.html.twig' %} #}`（Twig 注释包住完整标签） | **是（误判）** | `@Storefront/a.html.twig`（误抽取） |
| `<!-- {% sw_extends '…' %} -->`（HTML 注释） | **是（误判）** | 误抽取 |
| `{# sw_extends '@Storefront/a.html.twig' #}`（注释内没有 `{%`） | 否 | 无 |
| `{%- sw_extends '…' -%}`、`{% extends '…' %}`、双引号 | 是 | 正常抽取 |

即：把整段 `{% sw_extends %}` 放进注释并不能阻止它进入 `ShopwareTemplateIndex` 的值、`ShopwareTemplateExtendsIndex` 的倒排键，也不能阻止 missing-comment inspection 的"必须 extending"门槛（`TwigBlockHashMissing.kt:21`）和 marker 的 overrides 判断（`TwigBlockMarker.kt:40`）。对比之下 block 侧走 PSI（`TwigBlockHashIndex.kt:29-44`），注释里的 block 不会被索引——同一条继承信息在"模板索引（文本）"与"block 索引（PSI）"之间存在不对称。仓库现有测试只覆盖"带版本注释的 override block"（fixture `AaaCommentedPlugin`、`CommentedTheme`），没有注释 extends 的用例（grep `commented` 仅命中这两类）。

## 6. Fixture 与各分支的对应关系

| Fixture（相对 `src/test/testData/`） | 模板角色 | 对应分支 / 被哪个测试消费 |
| --- | --- | --- |
| `navigation/TwigNavigationTest/ShopwarePlatform/src/Storefront/Resources/views/...` 与 `inspection/**/ShopwarePlatform/...` | Shopware 核心（`isShopwareCoreTemplate` 双模式之一，`TwigUtil.kt:61`） | 导航排序第一候选；hash/deprecation/removed 的上游；自身不报 missing-comment（`TwigBlockHashMissingTest.kt:109-115`） |
| `inspection/TwigBlockHashChangedTest/vendor/acme/theme/...`、`inspection/TwigBlockHashMissingTest/vendor/acme/example-plugin/...` | vendor 第三方扩展（`isUpstreamTemplate` 的 vendor 分支） | 同名 view path 的并列上游（hash 匹配任一即可）；vendor 模板自身不报缺失 |
| `navigation/.../custom/plugins/TcinnTheme/...`、`util/TwigUtilTest/custom/plugins/TcinnTheme`、`marker/.../TcinnTheme`、`inspection/TwigBlockRemovedTest/custom/plugins/CommentedTheme` | custom/plugins 主题/插件 | `@TcinnTheme` bundle 解析；composer `version: 2.5.0` 作为版本后缀（`util/TwigUtilTest.kt:14-22`）；`CommentedTheme` 演示链上带注释者仍是上游（`TwigBlockRemovedTest.kt:32-53`） |
| `navigation/.../MyPluginOther/...`、`marker/.../MyPlugin/...`、各 inspection 的 `MyPlugin`/`MyPluginB` | 普通自定义插件（项目根下 bundle 目录，路径回退分支） | 同 view path 覆盖、兄弟 override、removed 时"另一个插件仍在覆盖但不能证明存在"（`TwigBlockRemovedTest.kt:20-30`） |
| `util/TwigUtilTest/custom/plugins/AaaCommentedPlugin` | 同路径、字典序更早且**带版本注释**的 override | 证明 `!hasVersioningComment` 过滤（`util/TwigUtilTest.kt:24-40`） |
| `util/TwigUtilTest/custom/plugins/NoVersionPlugin` | composer.json 无 `version` | 注释无 `@version` 后缀（`util/TwigUtilTest.kt:69-76`） |
| `index/TwigBlockDeprecationIndexTest/MyApp` | `@deprecated` 注释 + 紧邻 block | deprecation 抽取（`index/TwigBlockDeprecationIndexTest.kt:18-45`） |

## 7. 五个具体风险点

### 风险 1：注释掉的 extends 仍被当活动继承（文本正则不过滤注释）

- **触发布局**：任意 `custom/plugins/Foo/.../Resources/views/x.html.twig` 内含 `{# {% sw_extends '@Storefront/...' %} #}` 或 HTML 注释包住的完整标签；常见于临时禁用继承调试。
- **可见现象**：模板被索引为有 extends（`ShopwareTemplateIndex` 值为该目标），并出现在目标 view path 的反向倒排里（`ShopwareTemplateExtendsIndex`）；marker 可能显示 "Overrides block"（`TwigBlockMarker.kt:40`）；missing-comment inspection（默认关）可能对本不是 override 的 block 报缺失；block 导航可能跳到"父模板"。而 block 索引基于 PSI 不看注释，造成链与 block 数据不一致。
- **可能失效的键/缓存**：`twig_templates` 的值、`twig_template_extends` 的键、按文件缓存的 `getExtendsChainPaths`/`getExtendingTemplatePaths`（`TwigUtil.kt:81-91`、`119-131`，失效只靠 PSI 修改计数，注释改动后会重建但结论仍错）。
- **现有测试**：未覆盖。`AaaCommentedPlugin`/`CommentedTheme` 是"带版本注释的 block"，不是注释 extends；无任何测试把 `{% sw_extends %}` 放进 Twig/HTML 注释。

### 风险 2：bundle 匹配的子串回退 + 路径字典序误配同名/近名模板

- **触发布局**：`vendor/acme/theme/` 与 `custom/plugins/AcmeThemeWare/`（normalize 后近名但不精确），或两个插件 `Foo`、`FooBar`，代码里写 `{% sw_extends '@Foo/storefront/p.html.twig' %}`，而精确路径段匹配为空。
- **可见现象**：`filterByBundle` 精确段匹配失败后退化为整条去后缀路径的子串包含（`ShopwareTemplateUtil.kt:243-247`），可能把 `FooBar` 的模板也当成 `@Foo` 的目标；`templateOrder` 对非核心仅按绝对路径排序（`ShopwareTemplateUtil.kt:251`），多目标/记录 hash 时选中字典序靠前的插件，与真实加载优先级无关。表现为 Ctrl-Click 多出目标、自动生成的版本注释 hash 取自错误插件。
- **可能失效的键/缓存**：查询侧不直接错键，但"view path → 文件集合"的解释错了：`twig_templates` 的 `getContainingFiles` 结果被错误排序/过滤；项目级 lookup 缓存按 VFS 结构修改失效（`ShopwareTemplateUtil.kt:157-165`），新增近名插件前不会自动纠正。
- **现有测试**：部分覆盖正常路径（`TwigNavigationTest.kt:21-38`、`57-72`），未构造精确匹配失败后落入子串回退的近名布局。

### 风险 3：deprecation 检测依赖固定 PSI 兄弟距离，且只按 relPath 广播

- **触发布局**：`@deprecated` 注释与 `{% block %}` 之间存在空行被 PSI 建模为额外节点、注释位于 block 同行/内侧，或两个不同 bundle 下存在相同 view path + 同名 block（一个废弃、一个未废弃）。
- **可见现象**：要求恰好 `prevSibling.prevSibling is TwigComment`（`TwigBlockDeprecationIndex.kt:31`），多一个空白节点则废弃信息静默丢失；反之 block 内侧也出现 `@deprecated` 文本时可能误关联。消费侧只比较 `deprecation.relPath == 当前文件相对路径`（`TwigBlockDeprecated.kt:31-34`），同 view path 的所有模板（含与废弃无关的另一份同名模板）都会被划线。
- **可能失效的键/缓存**：`twig_blocks` 键为 block 名、单文件内同名 block 后者覆盖前者（`deprecations[blockName] = ...`，`TwigBlockDeprecationIndex.kt:42`）；relPath 存的是值不是键，无法在查询时按 bundle 限定。
- **现有测试**：仅一个正向用例（`TwigBlockDeprecationIndexTest.kt`）和一个 inspection 用例（`TwigBlockDeprecatedTest.kt:11-18`）；空白节点距离、同 relPath 多 bundle、文件内同名 block 均未覆盖。

### 风险 4：路径门槛与拼接硬编码 `/` 分隔符（Windows 兼容性）

- **触发布局**：在 Windows 上打开项目（盘符反斜杠路径），或模板被符号链接/路径里出现 `Resources\views\`。
- **可见现象**：indexer 门槛 `path.contains("Resources/views/")`（`ShopwareTemplateIndex.kt:25`、`TwigBlockHashIndex.kt:25`）不匹配反斜杠路径，Twig 文件整体不入三个索引；`getRelativePath` 的 `substringAfter("Resources/views/")`（`TwigUtil.kt:57`）取不到后缀，键可能变成整段路径；`getTemplatesByViewPath` 的 `endsWith("Resources/views/$path")`（`ShopwareTemplateUtil.kt:228`）恒假，导航/补全/marker/inspection 全链路空结果。
- **可能失效的键/缓存**：`twig_templates`、`twig_template_extends`、`twig_hash` 三个索引的键空间本身被污染（同一文件在不同分隔符下键不同），必须 invalidate caches 全量重建。对比：bundle 索引使用了 `FilenameUtils.separatorsToUnix`（`ShopwareBundleIndex.kt:50`、`70-71`），Twig 索引没有。
- **现有测试**：未覆盖（测试框架内全部为正斜杠虚拟路径；无 Windows CI 配置可见）。

### 风险 5：未加版本注释的第三方同路径模板可掩盖 block 删除，且链/回退每路径只取一个值

- **触发布局**：核心 `storefront/page/content/index.html.twig` 删除 block `b`；`vendor/acme/theme/.../同路径` 仍含 `b` 且无版本注释；自己的插件 override 了 `b`（带注释）。
- **可见现象**：removed inspection 的存活证明取全项目同名值、排除带注释者后按 relPath 去重（`TwigBlockRemoved.kt:44-66`），未注释的 vendor 副本让 `b` 看起来"仍存在于 storefront/page/content/index.html.twig"，于是只提示"仍存在于…"甚至完全不报；`doc/twig-versioning.md` Limitations 明确承认该场景"third-party plugin … without versioning comments can prevent the removed-block warning"。另外 `getUpstreamBlocks` 链上每路径 `firstOrNull`（`TwigUtil.kt:179`）与 hash 索引的"同名 block 同文件后者覆盖前者"（`TwigBlockHashIndex.kt:32`）叠加时，同文件多个同名 block 只有最后一个进入索引。
- **可能失效的键/缓存**：`twig_hash` 键（block 名，无 path 维度）——同名跨文件靠值列表、同文件靠覆盖；`getUpstreamBlocks` 的语义依赖"每个文件每 block 名一条值"这一未被测试锁定的隐含前提。
- **现有测试**：覆盖了反向情形（带注释的兄弟不掩盖：`TwigBlockRemovedTest.kt:20-30`、`TwigBlockHashChangedTest.kt:43-77`），但**未覆盖文档承认的未注释第三方掩盖删除**；同文件同名 block 无测试。

**补充观察（不计入五条）**：`StringUtil.sha512` 实为 SHA-256（`StringUtil.kt:10-14`），文档与命名不一致且无算法锁定测试，若有外部工具按文档算 SHA-512 会导致 hash 永不匹配；extends 链 10 跳（`ShopwareTemplateUtil.kt:128`）、下游 BFS 8 层（`ShopwareTemplateUtil.kt:92`）是静默截断；dumb mode 无守卫（见第 5 节）。

## 8. 新增测试：锁定"带版本注释者永不为上游"不变量

新增文件：`src/test/kotlin/de/shyim/shopware6/test/inspection/quickfix/TwigVersioningCommentQuickFixTest.kt`

选择理由（对应任务要求"已由代码证实的优先级/作用域不变量，同时经过索引和一个消费方"）：

- 不变量由代码直接表达：`TwigUtil.getUpstreamBlocks` 回退排序里 `!it.hasVersioningComment`（`TwigUtil.kt:188`）与 `getVersioningComment` 的同款过滤（`TwigUtil.kt:280`），是 hash 比对/删除判定/生成注释三类功能共同依赖的优先级规则。
- 同时经过两层：
  1. **索引层**：直接读 `TwigBlockHashIndex` 取核心模板 block 的真实 hash，断言 `AaaSiblingPlugin`（同 relPath、路径字典序更早、带 `{# shopware-block: siblingsownhash@1.0.0 #}` 的兄弟 override）已经入索引但不会被选中；
  2. **消费方**：启用 `TwigBlockHashMissing` inspection（`TwigBlockHashMissing.kt:12-44`），在 override 文件的 block 上调用平台 quick fix `Add missing versioning comment`（`AddMissingTwigVersioningCommentFix.kt:9-20`），`checkResult` 断言写入的是核心 hash 且没有错误版本后缀（核心路径不在 vendor 也不在 custom/plugins，版本解析返回 null，`TwigUtil.kt:299-325`）。
- 这是仓库里**第一个驱动真实 quick-fix action 的测试**（此前 `src/test/kotlin/` 下 grep `AddMissingTwigVersioningCommentFix` 无测试引用），不是字符串 helper 测试。

测试没有新增 fixture 目录，复用 `inspection/TwigBlockHashMissingTest/ShopwarePlatform`（testDataPath 指向现有目录），兄弟插件与被修复文件在测试内以 `addFileToProject` 创建；若移除 `TwigUtil.kt:188` 的 `!it.hasVersioningComment`，写入注释会变成 `siblingsownhash@1.0.0`，测试即失败。

## 9. 构建与测试演示

- 安装/编译：`./gradlew classes` —— BUILD SUCCESSFUL（`compileKotlin FROM-CACHE`，5 tasks，约 8s）。
- 全量测试：`./gradlew test` —— BUILD SUCCESSFUL，15 个测试类共 43 个用例（原有 42 + 新增 1），0 失败；结果 XML 在 `build/test-results/test/`，HTML 报告在 `build/reports/tests/test/index.html`。
- 单独验证新增用例：`./gradlew test --tests "de.shyim.shopware6.test.inspection.quickfix.TwigVersioningCommentQuickFixTest"` 通过。
- 工具链：JDK 21（`build.gradle.kts:16-18`），IntelliJ Platform IU 2026.1 / build 261-262（`gradle.properties`），JUnit 4 `BasePlatformTestCase`。
