# 第三方书源规则声明

内置文件 `app/src/main/assets/sources/main.json` 的 12 条站点规则，移植自用户提供的 SoNovel `rules/main.json`。该原始文件 SHA-256 为 `EA291C2A93BB2CD2F8D085ACCD3FA46FCBF53C406DB369F3704EF693E8330499`，可据此核对具体输入。上游项目为 [freeok/so-novel](https://github.com/freeok/so-novel)，其 [规则集说明](https://github.com/freeok/so-novel/blob/main/BOOK_SOURCES.md) 和 [当前规则文件](https://github.com/freeok/so-novel/blob/main/bundle/rules/main.json) 可供追溯；`main` 分支持续变化，尚未确认用户文件对应的发行版本或提交，不应把当前上游文件视为完全相同的版本。上游仓库标注 [AGPL-3.0 许可](https://github.com/freeok/so-novel/blob/main/LICENSE)；移植时保留原规则的名称与备注。

本次移植只对规则数据做了以下变更：

1. 按站点主机名与规则名计算稳定 `id`（SHA-256 前 16 位小写十六进制）。
2. 将 7 条以 HTTP 为根 URL 的旧源标记为 `disabled: true`，避免首次运行时发起明文请求。
3. 将“书海阁小说网”`chapter.filterTxt` 中依赖 `\1` 反向引用的广告过滤分支，改为 RE2J 可执行的有界近似：`喜欢.{0,120}?请大家收藏：\([^)]+\).{0,120}?书海阁小说网更新速度全网最快。`。此近似不再验证两处文字完全相同，可能比原表达式多删除少量匹配片段；若实际章节出现误删，应停用该过滤分支并修正规则。
4. 仅调整 JSON 排版和字段顺序；未内置站点正文或其他小说内容。

本声明仅记录所移植规则的来源和上游许可。本项目整体按 AGPL-3.0 发布，完整许可文本见根目录 [LICENSE](../LICENSE)；公开源码与 APK 时，应同时保留本声明及上游许可信息。规则网址及可用性由第三方站点决定，移植和校验不代表对站点内容或访问权限的背书。
