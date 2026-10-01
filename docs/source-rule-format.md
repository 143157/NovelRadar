# 小说雷达书源规则格式（首版子集）

小说雷达接受严格 JSON：一个书源对象，或由书源对象组成的数组。它借鉴 SoNovel 的配置驱动方法，但不承诺兼容 SoNovel 的所有扩展。规则只描述公开网页的请求和解析；导入时先解析、校验，再决定是否启用。内置规则位于 `app/src/main/assets/sources/main.json`，来源和修改记录见 [第三方书源声明](third-party-rule-notice.md)。

## 最小完整例子

GET 搜索：

```json
{
  "url": "https://books.example.org/",
  "name": "示例 GET 书源",
  "search": {
    "url": "https://books.example.org/search?q=%s",
    "method": "GET",
    "result": ".search-result",
    "bookName": "h3 a",
    "author": ".author"
  },
  "toc": { "item": "#chapters a" },
  "chapter": { "title": "h1", "content": "#content" }
}
```

表单 POST 搜索：

```json
{
  "url": "https://library.example.org/",
  "name": "示例 POST 书源",
  "comment": "仅示范规则结构，不代表实际站点",
  "search": {
    "url": "https://library.example.org/search",
    "method": "POST",
    "data": "{searchkey: %s, searchtype: all}",
    "result": "#results > article",
    "bookName": "h2 a",
    "author": ".byline",
    "nextPage": "a.next",
    "pagination": true
  },
  "book": { "intro": ".summary", "coverUrl": "img.cover" },
  "toc": { "item": "#chapter-list a" },
  "chapter": {
    "title": "h1",
    "content": "#reader",
    "filterTag": "script, .advertisement",
    "filterTxt": "\\(本章完\\)"
  }
}
```

`%s` 是关键词占位符。GET 在 URL 中替换；POST 在 `data` 的字段值中替换，并按 UTF-8 编码为表单数据。POST `data` 是 SoNovel 式对象文本（可有未加引号的键和值），不是 JSON；冒号和逗号可出现在成对引号内。搜索方法不区分大小写，只支持 GET 和 POST。规则文件本身必须是合法 JSON，不能含 JSON5 注释。

## 字段与解析

| 字段 | 含义 |
| --- | --- |
| `url`, `name` | 必填的站点 HTTP(S) 根 URL 和显示名。`comment`, `language`, `id`, `disabled` 可选。 |
| `search` | 可选；存在且未设 `search.disabled: true` 时，必须提供 `url`, `method`, `result`, `bookName`。可选 `author`, `category`, `latestChapter`, `lastUpdateTime`, `wordCount`, `status`, `cookies`, `pagination`, `nextPage`。 |
| `book` | 可选详情字段，例如 `bookName`, `author`, `intro`, `category`, `coverUrl`, `latestChapter`, `lastUpdateTime`, `status`。`book.url` 是从详情 URL 提取书籍 ID 的正则表达式，**不是**请求 URL。 |
| `toc` | `item` 必填，选中章节链接；可选 `list`, `url`, `baseUri`, `pagination`, `nextPage`, `isDesc`。`toc.url` 非空时须包含 `%s`。 |
| `chapter` | `title`、`content` 必填；可选 `paragraphTag`, `paragraphTagClosed`, `filterTag`, `filterTxt`, `pagination`, `nextPage`。 |
| `crawl` | 可选抓取提示，包括 `concurrency`, `minInterval`, `maxInterval`, `maxAttempts`, `retryMinInterval`, `retryMaxInterval`；间隔单位为毫秒（例如 1000 表示一秒），与本项目参考的 SoNovel 程序实际休眠行为一致；原模板中的“秒”注释与该程序实现不符，核对记录见 [书源验证笔记](source-verification-notes.md)。配置不构成绕过站点限流的授权。 |

标量与列表选择器支持 jsoup CSS 或以 `/` 开始的 XPath。`search.result` 先定位结果项，再相对结果项提取书名等字段。普通元素取可见文本；章节内容取内部 HTML；`a` 取绝对 `href`，`img` 取绝对 `src`，`meta` 取 `content`。相对链接按响应页 URL 解析为绝对 URL。XPath 不加载外部实体或 DTD。选择器以 UTF-8 计最多 2 KiB。

`@js:` 可追加到选择器尾部，例如 `".author@js:r=r.replace('作者：','')"`。选择器先取字符串并放入变量 `r`，脚本在新的、无 Java/Android 对象绑定的 QuickJS 上下文中修改 `r`，返回 `String(r)`。每次调用独立；没有文件、网络或设备 API。限制：输入 2 MiB、脚本 16 KiB、输出 4 MiB、执行 300 ms、运行时内存 32 MiB、栈 512 KiB。脚本超时或出错只令当前书源任务失败。静态校验还拒绝 `import`、`require`、`fetch`、`XMLHttpRequest`、`WebSocket`、`Java`、`Packages`、`process`、`eval` 独立标识符。

`pagination: true` 时用 `nextPage` 选择后续链接。为防止错误规则无限循环，搜索最多 10 页、目录最多 100 页、单章最多 20 页；重复下一页 URL 会停止。目录结果按绝对 URL 去重并保留原顺序，`isDesc` 表示源站目录倒序。

正文净化先用 `filterTxt` 正则删除 HTML 片段中的匹配文本，再将 `paragraphTag` 匹配转换为换行标签，随后移除 `filterTag` CSS 选择器选中的元素及脚本等危险标签。`book.url`、`paragraphTag`、`filterTxt` 必须可由 RE2J 编译且各不超过 2 KiB UTF-8；RE2J 不支持反向引用与某些依赖回溯的表达式。`paragraphTagClosed` 记录源站段落标签是否闭合。连续空行最多保留两个；空白与换行会规范化。

## 安全、导入与兼容边界

- HTTPS 规则可按用户选择启用。HTTP 旧源默认停用；用户看到明文传输风险提示并明确选择后，才允许该源访问 HTTP。请求和每次跳转都按同一协议策略检查；不能用 HTTPS 跳转绕过 HTTP 开关。
- `id` 稳定标识内置源，取 `SHA-256(lowercase(host) + "|" + name)` 的前 16 位小写十六进制字符。导入自定义源若与现有 ID 相同，应作为更新候选；同名但 ID 不同必须提示冲突，不静默覆盖。内置规则不可直接改写，用户可复制为自定义规则。
- 禁用标记**不**豁免规则校验。无效 URL、缺少必需字段、非法选择器、超长或不安全脚本会给出字段路径诊断，而非静默删除。
- 不支持登录态自动获取、验证码、Cloudflare 绕过、付费墙、任意网络调用、本地文件访问、Java/Android 对象桥接、Legado 规则或 JSON5 扩展。站点可用性会变化；通过离线规则校验不等于线上站点当前可访问。
- 应用不附带小说正文。请仅使用有权访问的公开内容，并遵守来源网站的使用条款。
