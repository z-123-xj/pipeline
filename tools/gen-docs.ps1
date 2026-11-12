# gen-docs.ps1
# =============================================================================
# 同步生成项目根目录 README.md
#
# 用法（在 pipeline 根目录执行）：
#   pwsh tools/gen-docs.ps1                  # 直接写 README.md
#   pwsh tools/gen-docs.ps1 -DryRun         # 只打印到 stdout，不写文件
#   pwsh tools/gen-docs.ps1 -Check          # 与磁盘上 README 对比，不一致返回非 0
#
# 工作流：
#   1) 改了 vars/*.groovy / Jenkinsfile / resources/*.yaml → 跑一次这个脚本
#   2) 把 README.md 的变更一起 commit
#   3) CI 可加 pwsh tools/gen-docs.ps1 -Check 校验 README 是否过期
# =============================================================================

param(
    [switch]$DryRun,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'

# 定位 pipeline 根目录（脚本所在目录的父目录）
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root      = Split-Path -Parent $ScriptDir

# ---- 工具函数 ----

# 读文件，UTF-8 无 BOM
function Read-File($Path) {
    if (-not (Test-Path $Path)) { return '' }
    [System.IO.File]::ReadAllText($Path, (New-Object System.Text.UTF8Encoding $false))
}

# 从 groovy 文件头部抽「一行说明」 + 「签名」
# 约定：// ===== <title> ===== 写在 def call(...) 上方
# 注意：必须用 .NET 多行正则（RegexOptions.Multiline）；
#       Select-String 管道吃整个字符串时 ^ 只匹配全文开头，会永远抓不到标题
function Parse-GroovyStep($Path) {
    $content = Read-File $Path
    $name    = [System.IO.Path]::GetFileNameWithoutExtension($Path)

    # 顶部 // ===== TITLE =====
    $titleMatch = [regex]::Match($content, '^//\s*=====\s*(.+?)\s*=====', 'Multiline')
    $title = if ($titleMatch.Success) { $titleMatch.Groups[1].Value } else { '' }

    # def call(...) 签名
    $sigMatch = [regex]::Match($content, '^[ \t]*def\s+call\s*\([^)]*\)', 'Multiline')
    $sig = if ($sigMatch.Success) { $sigMatch.Value.Trim() } else { '' }

    # 用法片段（// 用法：... 后面到 def call / 下一个 // ===== / // 依赖）
    # (?m) 多行：让 ^ 能匹配每行开头，否则截断条件不生效、会多吞后面的注释
    # 组内用 [\s\S]*?：用法是多行块，不能换成 .*?
    $usage = ''
    $usageMatch = [regex]::Match($content, '(?m)^//\s*用法：\s*(?<u>[\s\S]*?)(?=^\s*def\s+call|^//\s*=====|^//\s*依赖)')
    if ($usageMatch.Success) {
        $usage = $usageMatch.Groups['u'].Value.Trim()
    }

    # 依赖（// 依赖 env： / // 依赖 cfg：）
    $deps = @()
    $envMatches  = [regex]::Matches($content, '//\s*依赖\s+env[^：]*：([^\n]*)')
    $cfgMatches  = [regex]::Matches($content, '//\s*依赖\s+cfg[^：]*：([^\n]*)')
    foreach ($m in $envMatches) { $deps += "env: $($m.Groups[1].Value.Trim())" }
    foreach ($m in $cfgMatches) { $deps += "cfg: $($m.Groups[1].Value.Trim())" }

    [pscustomobject]@{
        Name  = $name
        Title = $title
        Sig   = $sig
        Usage = $usage
        Deps  = $deps
    }
}

# 从 vars/pipelineInlet.groovy 抽出参数构造器
# （参数定义已从 Jenkinsfile 移到入口 var；Jenkinsfile 只是 3 行入口）
# （参数列表是动态构建的：host 类型才追加主机选择参数，所以直接扫全文件；
#   误匹配如 toString() 里的 string() 没有 name: '...' 字段会被跳过）
function Parse-JenkinsfileParameters {
    $content = Read-File (Join-Path $Root 'vars\pipelineInlet.groovy')

    # 先按「参数构造调用 + 它的 body」切成块；调用名就是参数类型
    # 匹配：<fn>(\n ... )
    # 简化：依次扫常见构造器，每个非贪婪匹配到对应的右括号
    $knownFns = 'gitParameter|choice|reactiveChoice|booleanParam|string|textParam|password|fileParam|credentials|extendedChoice'
    $fnPattern = '(?<fn>' + $knownFns + ')\s*\((?<b>(?:[^()]|\((?<DEPTH>)|\)(?<-DEPTH>))*(?(DEPTH)(?!)))\)'
    $fnMatches = [regex]::Matches($content, $fnPattern)

    $params = @()
    foreach ($m in $fnMatches) {
        $fnName  = $m.Groups['fn'].Value
        $fnBody  = $m.Groups['b'].Value

        $nameM = [regex]::Match($fnBody, "name\s*:\s*'(?<n>[^']+)'")
        if (-not $nameM.Success) { continue }
        $name = $nameM.Groups['n'].Value

        # 类型优先取 type 字段，否则用构造器名（string / choice / booleanParam...）
        $typeM = [regex]::Match($fnBody, "type\s*:\s*'(?<t>[^']+)'")
        $type = if ($typeM.Success) { $typeM.Groups['t'].Value } else { $fnName }

        $descM = [regex]::Match($fnBody, "description\s*:\s*'(?<d>[^']+)'")
        $desc = if ($descM.Success) { $descM.Groups['d'].Value } else { '' }

        $params += [pscustomobject]@{
            Name        = $name
            Type        = $type
            Description = $desc
        }
    }
    return $params
}

# 列出 resources/ 下的 yaml 结构（按环境/项目/服务）
function Get-ResourcesTree {
    $resDir = Join-Path $Root 'resources'
    if (-not (Test-Path $resDir)) { return @() }
    $tree = Get-ChildItem -Path $resDir -Recurse -Filter '*.yaml' |
        Where-Object { $_.FullName -notmatch '\\config\\config\.yaml$' } |
        ForEach-Object {
            $rel = $_.FullName.Substring($resDir.Length + 1) -replace '\\', '/'
            [pscustomobject]@{ Path = $rel; Size = $_.Length }
        }
    return $tree
}

# ---- 收集数据 ----

$groovySteps = Get-ChildItem (Join-Path $Root 'vars') -Filter '*.groovy' |
    Sort-Object Name |
    ForEach-Object { Parse-GroovyStep $_.FullName }

$params      = Parse-JenkinsfileParameters
$resTree     = Get-ResourcesTree
$commonYaml  = Read-File (Join-Path $Root 'resources\config\config.yaml')

# ---- 生成 README ----

$sb = New-Object System.Text.StringBuilder

function Out($s) { [void]$sb.AppendLine($s) }

Out '# pipeline'
Out ''
Out '> Jenkins 共享库 + 多项目多服务的 CI/CD 编排。'
Out '> 本 README 由 `tools/gen-docs.ps1` 自动生成；修改 `vars/`、`Jenkinsfile`、`resources/*.yaml` 后请同步重跑脚本。'
Out ''
Out '## 目录结构'
Out ''
Out '```text'
Out 'pipeline/'
Out '├── Jenkinsfile              # 入口（3 行，流程在 vars/pipelineInlet.groovy）'
Out '├── vars/                    # 共享库步骤（每个 groovy 文件 = 一个 step）'
Out '├── resources/               # 配置：config/config.yaml（全局）+ <env>/<proj>/<service>/{<job>.yaml+清单}（单一源）'
Out '├── tools/'
Out '│   └── gen-docs.ps1         # 本文档生成器'
Out '└── README.md                # 本文件（自动生成）'
Out '```'
Out ''
Out '## Job 命名规范'
Out ''
Out '每个 Jenkins job 的 `JOB_BASE_NAME` 必须严格按 5 段下划线分隔：'
Out ''
Out '```'
Out '<项目>_<服务名>_<语言>_<部署类型>_<环境>'
Out '```'
Out ''
Out '| 字段 | 取值示例 | 说明 |'
Out '|---|---|---|'
Out '| 项目 | `demo` | 业务线 / git group |'
Out '| 服务名 | `admin` / `api-03` | 服务唯一标识 |'
Out '| 语言 | `go` / `nodejs`（`web` 为旧命名别名） | `nodejs` 由配置 `node_type` 决定：默认 `static`=静态站(webBuild→nginx 托管)；`runtime`=node 常驻(nodejsBuild) |'
Out '| 部署类型 | `k8s` / `host` | `k8s` 走容器化，`host` 走二进制 |'
Out '| 环境 | `prod` / `test` | 对应 resources/ 子目录 |'
Out ''
Out '例：`demo_admin_go_host_prod` / `demo_admin_nodejs_host_prod`（admin 配 `node_type: runtime`）/ `demo_portal_nodejs_host_prod`（默认静态）'
Out ''
Out '> `node_type`：`static`(默认) 走 webBuild 打包静态产物交 nginx；`runtime` 走 nodejsBuild 产出 node 常驻服务。静态/常驻只改这个配置，不用改 job 名。'
Out '> `node_memory`（MB）：构建时注入 `NODE_OPTIONS=--max-old-space-size`，加大 node 堆上限，防大 SPA / Next.js 构建 OOM（值需 ≤ 构建机可用内存-512MB）。'
Out ''
Out '## 依赖的 Jenkins 插件'
Out ''
Out '| 插件 | 用途 |'
Out '|---|---|'
Out '| `pipeline-utility-steps` | `readYaml` |'
Out '| `git` | `gitCheckout` |'
Out '| `ssh-credentials` / `ssh-agent`（或等价） | SSH 凭据 |'
Out '| `git-parameter` | `BRANCH` 参数（`PT_BRANCH`） |'
Out '| `active-choices` | `TARGET_HOSTS` 多选 UI（`reactiveChoice PT_MULTI_SELECT`，联动 `MODE`） |'
Out ''
Out '> 装完 `active-choices` 后改了 `vars/pipelineInlet.groovy` 的 `parameters` 块，'
Out '> Jenkins 要**跑一次构建**才会重新加载 job 参数定义，之后的构建才会显示多选 UI'
Out '> （首次构建不弹参数是 Jenkins 的已知行为）。'
Out '> 多选脚本由 pipelineInlet 拼字面量传入（沙箱里不能调 Jenkins 步骤）。'
Out '> `TARGET_HOSTS` 联动 `MODE`：切到 custom 才渲染主机选项；k8s 类型不配 servicesIP，选项恒为空，不报错。'
Out ''
Out '## 部署类型'
Out ''
Out '| 类型 | 流程 | 适用 |'
Out '|---|---|---|'
Out '| `k8s` | 编译 → 云上 docker build → push ECR → apply k8s | 服务运行在 k8s 集群 |'
Out '| `host` | 编译 → 云上中转 → ssh 到目标主机 → systemctl restart | 二进制/systemd 跑在指定主机（`servicesIP`） |'
Out '| `docker` | 编译 → 中转机 docker build →（推 ECR / save-load）→ 目标机 pull/load → 容器托管 | 容器跑在指定主机（`servicesIP`）；默认推 ECR，可切 save/load |'
Out ''
Out 'pipelineInlet 按 `DEPLOY` 字段分支：'
Out ''
Out '```'
Out 'if (env.DEPLOY == ''host'') → remoteHostDeploy(...)'
Out 'else                               → remoteDockerBuild + remoteDockerPush + remoteK8sApply'
Out '```'
Out ''
Out '## 配置文件'
Out ''
Out '### 优先级（后者覆盖前者同名 key）'
Out ''
Out '1. `resources/config/config.yaml` — 全局默认'
Out '2. `resources/<env>/<project>/<service>/<job>.yaml` — 项目配置（最高）'
Out ''
Out '### 配置字段字典（`resources/config/config.yaml` 全文）'
Out ''
Out '> 字段的完整说明与用法就在源文件里，这里原样同步，避免两份解释漂移。'
Out '> 注释掉的 key 属于**项目级字段**（写进各 job yaml），改字段请改源文件后重跑本脚本。'
Out ''
Out '```yaml'
Out ($commonYaml.Trim())
Out '```'
Out ''
Out '### 当前已配置的服务 yaml'
Out ''
Out '| 环境 | 项目 | Job |'
Out '|---|---|---|'
foreach ($f in $resTree) {
    $parts = $f.Path -split '/'
    $jobName = ($parts[2] -replace '\.yaml$', '')
    Out ('| {0} | {1} | {2} |' -f $parts[0], $parts[1], $jobName)
}
Out ''
Out '## 共享库步骤（vars/）'
Out ''
Out '每个 `vars/<name>.groovy` 文件导出一个名为 `<name>` 的 Groovy 函数，Jenkinsfile 里直接调用。'
Out ''
foreach ($s in $groovySteps) {
    Out "### ``$($s.Name)``"
    Out ''
    Out $s.Title
    Out ''
    Out '```groovy'
    Out $s.Sig
    Out '```'
    Out ''
    if ($s.Usage) {
        Out '**用法**：'
        Out ''
        Out '```groovy'
        Out $s.Usage
        Out '```'
        Out ''
    }
    if ($s.Deps.Count -gt 0) {
        Out '**依赖**：'
        foreach ($d in $s.Deps) {
            Out "- $($d)"
        }
        Out ''
    }
}
Out '## Jenkinsfile 参数'
Out ''
if ($params.Count -gt 0) {
    Out '| 参数 | 类型 | 说明 |'
    Out '|---|---|---|'
    foreach ($p in $params) {
        Out "| ``$($p.Name)`` | $($p.Type) | $($p.Description) |"
    }
} else {
    Out '_（解析失败，请查看 Jenkinsfile `properties(parameters([...]))` 块）_'
}
Out ''
Out '## 部署流程'
Out ''
Out '### k8s 类型'
Out ''
Out '```'
Out 'deploy ：拉代码 → 拉部署配置 → 编译 → 分发到云 → docker build → push → kubectl apply'
Out 'restart：拉部署配置 → 分发到云（仅同步配置）→ kubectl apply（沿用当前镜像）→ rollout restart'
Out 'test   ：拉代码 → 拉部署配置 → 编译 → 分发到云 → （不执行发布）'
Out '```'
Out ''
Out '> `restart` 直接重启：跳过拉代码/编译/分发，不碰配置。'
Out '> k8s 滚动重启 Pod；host `systemctl restart`；docker `docker restart` 容器。'
Out '> `test` 是发版前的演练模式：全流程跑通（拉代码、编译、构建镜像、分发产物到服务器都真实执行），唯独跳过发布动作。'
Out '> docker 跳过 `compose up` / `docker run`；k8s 跳过 `kubectl apply`；host 跳过 `systemctl restart`。'
Out '> 为避免污染正式环境，test 分发到 `<服务>_test` 子目录（service_dir 追加 `_test` 后缀），不动正式目录。'
Out ''
Out '### host 类型'
Out ''
Out '```'
Out '拉代码 → 编译 → 分发到云（remote_host 中转机，默认继承全局；remote_host 留空 = 直连模式，跳过本段并直连 servicesIP） → 部署到 servicesIP 目标主机'
Out '```'
Out ''
Out '> **直连模式**（`remote_host` 留空 或 `direct_deploy: true`）：跳过「分发到云」，host / docker 直接 SSH 到 `servicesIP` 部署'
Out '> （Jenkins → 目标机），产物由部署步骤直传目标机；不依赖跳板机。'
Out ''
Out '### docker 类型'
Out ''
Out '```'
Out '拉代码 → 拉部署配置 → 编译 → 分发到云 → 中转机 docker build →（推 ECR / save-load）→ servicesIP 目标机 pull/load → 停旧起新'
Out '（remote_host 留空=直连模式：跳过「分发到云」，docker 在 Jenkins agent 本地 build，再直连 servicesIP 部署）'
Out '```'
Out ''
Out '> docker 与 host 同样按 `MODE` 选目标（deploy 全部 / Grayscale / custom）。'
Out '> **镜像分发策略**（`docker_push`，默认 true，与 k8s 一致）：'
Out '>   - `docker_push: true`  → build 后推 ECR，目标机直接 `docker pull`（走完整 ECR 地址 `IMAGE_FULL`）'
Out '>   - `docker_push: false` → 不推仓库，`docker save | ssh load` 直接分发（镜像仍用完整 ECR 地址，仅本地 save/load；目标机无法 pull 仓库时用）'
Out '> **镜像清理**（`images_clean`，默认 false）：设 true 时部署结束后清理历史镜像。'
Out '>   - 构建机（agent 直连 / 跳板机）：`docker image prune -a -f` 全清。'
Out '>   - 目标机：仅清理当前服务仓库的旧 tag，保留最近 `images_clean_keep` 个（默认 3，含当前 tag；按 tag 时间戳排序，不误伤其它服务）。'
Out '> **共享项目单服务接管**（`compose_remove_orphans`，默认 true）：`compose up` 是否加 `--remove-orphans`。'
Out '> 多服务共享同一 compose 项目、只接管其中一个服务时设 `false`，避免删掉同项目其它服务。'
Out '> **形态自动分流**（由服务目录里有没有 `compose.yml` 决定）：'
Out '>   - 无 compose.yml → `docker run -d --name <容器> --restart=always`（run 模式，原 `docker_run_args` 透传）；'
Out '>     旧容器停前打 `:prev` tag 便于回滚；起新后 3 秒校验必须 `running`。'
Out '>   - 有 compose.yml → 下发 `compose.yml` 到目标机并 `docker compose up -d --force-recreate --remove-orphans`；'
Out '>     compose 里写 `<image>` 占位（部署时替换为 `IMAGE_FULL`）。'
Out '>     部署会先 `docker rm -f` 同名容器再 up（同名容器来自其他 compose 项目时避免冲突，幂等无副作用）。'
Out '>'
Out ''
Out '产物形态由编译步骤决定（`remoteHostDeploy` 按 `GO_BINARY` 后缀自动区分）：'
Out ''
Out '| 产物 | 部署方式 | 备份方式 |'
Out '|---|---|---|'
Out '| go 二进制（单文件） | scp 替换 → `systemctl restart` | 单文件 `.bak` |'
Out '| node tgz 压缩包 | scp → 解压覆盖到 `service_dir` → 装 unit → `daemon-reload` + `restart` | 目录快照 `<service_dir>.bak.tgz` |'
Out ''
Out 'host 类型特有的设计：'
Out ''
Out '- Jenkins 通过 `ssh_cred` ssh 到中转机（`remote_host`），产物先推到中转机，再由中转机分发到没开外网的目标主机'
Out '- 跳板机 → 目标主机走内网免密（跳板机本机用户直连），不需要为目标主机单独配凭据'
Out '- 部署范围由 `MODE` 参数决定：deploy 全部 / Grayscale 灰度 / custom 手动勾选（rollback 占位）'
Out '- `MODE` 取值（三类部署通用）：'
Out '  - `deploy` 正常发版：拉代码 → 编译 → 构建镜像 → 推 ECR → apply/compose up/systemctl'
Out '  - `config` 只改配置：跳过拉代码/编译/构建/推送，**沿用当前镜像**，只 apply 清单 + 重启'
Out '    （改 configmap / compose / systemd unit 用这个，不重新打包镜像）'
Out '  - `restart` 直接重启：不碰配置、不换镜像'
Out '  - `test` 演练：全流程跑一遍但**不执行发布**（不改动集群/目标机）'
Out '- nodejs 类型把 unit 写在 yaml 的 `service_unit` 里，pipeline 原样写到目标机 `/etc/systemd/system/<service_name>.service`，'
Out '  自动 `daemon-reload` + `restart`，新机器首次部署即可起；unit 要留 `[Install]` 段（pipeline 不自动 enable，'
Out '  开机自启就在目标机手动 `systemctl enable <name>` 一次即可，之后部署无需再 enable）'
Out '- web 类型不配 `service_name`，只解压产物，由 nginx 托管'
Out ''
Out '## 新增服务流程'
Out ''
Out '1. 在 `resources/<env>/<project>/<service>/` 下创建 `<job>.yaml`，字段照「配置字段字典」里的项目级示例填'
Out '2. 清单（Dockerfile / *.yaml / compose.yml / default.conf）放同目录 `resources/<env>/<project>/<service>/`，与 `<job>.yaml` 一起（单一源，随流水线版本化）；敏感配置另存 deploy-configs 仓'
Out '3. Jenkins 里建 job，名字严格按 `<项目>_<服务名>_<语言>_<部署>_<环境>`'
Out '4. 凭据：`cloud-deploy`（跳板机 SSH，所有 host 服务共用）'
Out '5. 跑一次，确认参数面板里 `MODE` 选 custom 后 `TARGET_HOSTS` 能看到 `servicesIP` 里的所有主机'
Out ''
Out '## 文档同步'
Out ''
Out '**改完代码必须重跑生成器**：'
Out ''
Out '```powershell'
Out 'pwsh tools/gen-docs.ps1            # 写 README.md'
Out 'pwsh tools/gen-docs.ps1 -DryRun   # 预览，不写文件'
Out 'pwsh tools/gen-docs.ps1 -Check    # CI 校验：README 与代码不一致则退出非 0'
Out '```'
Out ''
Out 'CI 建议加一步 `pwsh tools/gen-docs.ps1 -Check` 在合并前跑，不一致就拒绝合并。'
Out ''
Out '### 哪些改动需要重跑'
Out ''
Out '- `vars/*.groovy`：新增 / 删除 / 重命名 / 修改签名 / 修改依赖注释'
Out '- `vars/pipelineInlet.groovy`：参数块（`parameters`）或 stage 结构'
Out '- `resources/config/config.yaml`：字段增删'
Out '- `resources/<env>/<project>/<service>/`：新增 / 删除服务（`<job>.yaml` + 清单在同一目录）'
Out ''

# ---- 输出 ----

$content = $sb.ToString()

if ($Check) {
    $diskPath = Join-Path $Root 'README.md'
    $disk = Read-File $diskPath
    if ($disk.Trim() -ne $content.Trim()) {
        Write-Host "README.md 与当前代码不同步，请运行 pwsh tools/gen-docs.ps1 重生成" -ForegroundColor Red
        exit 1
    }
    Write-Host "README.md 与代码一致" -ForegroundColor Green
    exit 0
}

if ($DryRun) {
    Write-Host $content
    exit 0
}

$outPath = Join-Path $Root 'README.md'
[System.IO.File]::WriteAllText($outPath, $content, [System.Text.UTF8Encoding]::new($false))
Write-Host "已写入 $outPath" -ForegroundColor Green