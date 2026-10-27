# ai 模块

## 提供商

miniWhale 只用两个供应商：

- **DeepSeek**：走本模块的 OpenAI 兼容协议实现（`provider/providers/openai/`，Chat Completions；Responses API 的实现也还在，
  `useResponseApi` 打开时使用）。
- **MiniMax**：走 app 自己的 `WhaleMiniMaxClient`，不经过本模块。

B2（2026-10-27）删掉了 Claude、Google（Gemini / Vertex / Interactions）、ChatGPT、Grok 四套协议实现，以及只给账号类用的
`AccountCredentialProvider`、`AccountAuth`（401 重试）、Vertex 服务账号换 token。`ProviderManager` 只注册 `openai` 一种。

### 旧设置的兼容

`ProviderSetting` 是按 `type` 多态序列化的 sealed class，存在设置 DataStore 的 `providers` JSON 里。
`Google`、`Claude`、`ChatGPT`、`Grok` 四个类型**连同全部字段保留**，只是没有协议实现：

- 旧设置里有这类供应商时照常读入、原样写回（app 的 `LegacyProviderSettingCompatTest` 用一份含这四类的旧 JSON 验证）；
- 拿它们发请求时 `ProviderManager.getProviderByType` 抛 `UnsupportedProviderException`（带供应商名称的中文说明），不会崩；
- 删掉其中任何一个类型或字段，旧 JSON 就会解析失败或写回时丢数据，所以不要删。

内置默认供应商列表（app 的 `DEFAULT_PROVIDERS`）里仍有 Gemini、Claude 两项，同样只是数据；高级设置只显示 DeepSeek 和 MiniMax。

### 概念

| 概念                         | 位置                                     | 说明                                                                                  |
|----------------------------|----------------------------------------|-------------------------------------------------------------------------------------|
| `ProviderSetting`          | `provider/ProviderSetting.kt`          | 一个提供商的完整配置，随设置持久化。按**协议**区分类型，同一协议的不同厂商（官方、各家中转）是同一类型的不同实例                       |
| `ProviderSetting.ApiKey`   | 同上                                     | API Key 类提供商的公共父类，持有 `apiKey`、`baseUrl` 和 `quotaQuery`                                     |
| `ProviderSetting.Account`  | 同上                                     | 账号类（ChatGPT / Grok）的公共父类，只为兼容旧数据保留 `credential: OAuthCredential?`                    |
| `KeyRoulette`              | `util/KeyRoulette.kt`                  | API Key 类在多个 key 之间轮换                                                              |
| `customHeaders`            | `ProviderSetting` / `TextGenerationParams` | 用户自定义请求头。请求级的来自助手和模型，同名时覆盖提供商级的                                                   |
| `Model.providerOverwrite`  | `provider/Model.kt`                    | 单个模型改用另一份提供商配置。它是**独立副本**，自带凭据，不随所属提供商变化                                         |
| `ProviderQuota`            | `provider/ProviderQuota.kt`            | `Provider.getQuota` 的返回值                                                            |
| `QuotaQuery`               | 同上                                     | API Key 类的自定义额度查询：URL、请求头（可引用 `{{baseUrl}}`、`{{apiKey}}`）和取值表达式                         |
| `AiLog`                    | `util/AiLog.kt`                        | 本模块唯一的日志出口。默认关闭（release 不打任何日志）；app 启动时按 `BuildConfig.DEBUG` 打开，打开时也只打条数、字符数、模型、耗时、状态码、异常类型（N-001）。不要在本模块里直接用 `android.util.Log` / `println` / `printStackTrace` |

### 鉴权

OpenAI 兼容：`Authorization: Bearer <key>`，先放自定义请求头，再追加鉴权头。

- **多 key 轮换**：`apiKey` 可以填多个，以空白、换行或逗号分隔。`KeyRoulette.lru` 优先选从没用过的 key，否则选最久没用的；
  使用记录按提供商 id 区分，存在 `cacheDir/lru_key_roulette.json`，一天后过期。
- **额度查询**：由用户配置 `QuotaQuery`，鉴权头也写在它的 `headers` 里，`{{apiKey}}` 和对话请求一样从多个 key 里取一个。

miniWhale 里 DeepSeek 的 Key 不在设置里（W-028），发请求前由 app 的 `WhaleProviderKeys.withRuntimeKey` 从 SecretStore 现取。
