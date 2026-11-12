// vars/gitCheckout.groovy
// ===== 通用 git 检出（支持 workspace 内相对目录 / 外部绝对目录）=====
//
// 用法：
//   gitCheckout(url: 'http://gitlab.../xxx.git', branch: 'main', dir: 'src')     // 相对路径 → workspace 下
//   gitCheckout(url: ..., dir: '/data/xx/deploy-configs')                        // 绝对路径 → 公共目录，多 job 共享
//
// 凭据按地址前缀自动选（Jenkins 凭据 ID 由 config/config.yaml 的 http_credentials_id / ssh_credentials_id 提供）：
//   http(s):// 开头 → httpCredentialsId（username/password 或 token 类型）
//   git@ / ssh:// 开头 → sshCredentialsId（SSH Username with Private Key 类型），
//                        未传时回落 httpCredentialsId（要求它本身是 SSH 私钥凭据）
//
// 行为（checkout + GitSCM 全量形式，这个 Jenkins 版本的 git 简写 step 不支持 extensions）：
//   首次 clone，之后只 fetch + checkout 目标 commit（增量，不重复 clone）
def call(Map args = [:]) {
    // === 诊断打印：确认 Jenkins 实际加载的是最新版 gitCheckout.groovy ===
    // 判读：
    //  - 本行没出现 + fetch 仍 # timeout=10 → site job 没走本 var（用了自己的 checkout），groovy 改动无关，需改 job 自身；
    //  - 本行出现 + fetch 仍 # timeout=10 → 共享库未重新加载（被钉版本/缓存），需把 pipeline 库改为跟踪 main 或点 Replay；
    //  - 本行出现 + fetch 显示 # timeout=60 → 已生效，原失败是网络层断开（见下步处理）。
    try {
        def mt = sh(script: 'ls -l --time-style=long-iso $(find . -name gitCheckout.groovy 2>/dev/null | head -1) 2>/dev/null || echo "file-not-found"', returnStdout: true).trim()
        echo ">>> [gitCheckout] 加载检查 | mtime: ${mt} | 期望 timeout=60 shallow=1"
    } catch (e) {
        echo ">>> [gitCheckout] 加载检查 marker v2026-10-29c（诊断打印异常，但本行出现即代表新版已加载）"
    }

    // git@ / ssh:// 开头 = SSH 地址
    def isSshUrl = (args.url ?: '') ==~ '^(git@|ssh://).*'

    // 目标引用：分支或 tag，默认 main。
    // 关键修复：GitSCM 默认 refspec 是 +refs/heads/*（拉所有分支头），即使只 checkout main
    // 也会把全部分支 + 全 tag 都 fetch 一遍（你日志里那行 +refs/heads/*:refs/remotes/origin/*
    // 就是证据），大仓库会拖到 25min。这里用显式 refspec 只拉目标引用。
    def branch = args.branch ?: 'main'
    def isTag = branch ==~ '^refs/tags/.*' || (args.tag != null && args.tag != '')
    def refspec
    if (isTag) {
        def tagRef = branch ==~ '^refs/tags/.*' ? branch : "refs/tags/${args.tag ?: branch}"
        refspec = "+${tagRef}:${tagRef}"
    } else {
        refspec = "+refs/heads/${branch}:refs/remotes/origin/${branch}"
    }

    // 按地址选凭据：SSH 地址优先 sshCredentialsId；http 地址用 httpCredentialsId
    // refspec 限制只拉目标引用；noTags 在拉分支时禁用自动 tag 跟随（tag 场景仍由显式 refspec 取到需要的那一个）
    def remoteConfig = [url: args.url, refspec: refspec]
    def credId = isSshUrl ? (args.sshCredentialsId ?: args.httpCredentialsId) : args.httpCredentialsId
    if (credId) {
        remoteConfig.credentialsId = credId
    }
    // 拉取超时 10→60 分钟（大仓库在慢链路上常被默认 10 分钟砍断：early EOF / invalid index-pack）；
    // 浅克隆 depth 1 只取目标分支头一层历史，大幅减小下载量。
    // 注意：本环境 Git 插件版本较旧，GitSCM 顶层 cloneOptions 不被识别（会 WARNING 并忽略），
    // 必须用 CloneOption 扩展来设超时/浅克隆（见下方 extensions）。
    def scm = [
        $class: 'GitSCM',
        branches: [[name: branch]],
        userRemoteConfigs: [remoteConfig]
    ]

    def dirPath = (args.dir ?: '').toString()

    // 绝对路径（/xxx 或 C:\xxx）：公共目录，用 dir() 步骤包住（可跳出 workspace）
    // CleanBeforeCheckout：检出前 git reset --hard + git clean -fd，
    // 清掉共享目录里残留的本地改动和未跟踪文件（防止脏 yaml 混进 rsync / apply 链路）
    if (dirPath ==~ '(/|[A-Za-z]:[\\\\/]).*') {
        dir(dirPath) {
            scm << [extensions: [
                [$class: 'CleanBeforeCheckout'],
                [$class: 'hudson.plugins.git.extensions.impl.CloneOption', timeout: 60, shallow: true, depth: 1, noTags: !isTag]
            ]]
            checkout(scm)
        }
    } else {
        // 相对路径：workspace 子目录
        scm << [extensions: [
            [$class: 'RelativeTargetDirectory', relativeTargetDir: dirPath],
            [$class: 'hudson.plugins.git.extensions.impl.CloneOption', timeout: 60, shallow: true, depth: 1, noTags: !isTag]
        ]]
        checkout(scm)
    }
}
