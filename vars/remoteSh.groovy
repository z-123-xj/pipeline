// vars/remoteSh.groovy
// ===== 通用「SSH 到云服务器执行远端脚本」步骤 =====
//
// 用法：
//   remoteSh(
//       config: cfg,                                // 服务配置（取 ssh_cred / remote_host / remote_user）
//       envs  : [REMOTE_DIR: remoteDir, K8S_NS: x], // 注入远端的环境变量（远端脚本里 ${K8S_NS} 直接引用）
//       script: '''set -eu
// cd "${REMOTE_DIR}"
// ...remote commands...''')                        // 远端 bash 脚本，写在行首（heredoc 保留缩进）
//
// 本步骤固化了此前踩坑后确立的写法约定：
//   1) 外层 heredoc 一律 <<'REMOTE_EOF'（带引号）：本地 shell 完全不展开任何 $ 变量
//   2) Jenkins 侧变量以 export 行拼在远端脚本开头（值用单引号包裹，内部 ' 转义），
//      由 Groovy 拼好后经 heredoc 原样传给远端 bash → 远端脚本里 ${XXX} 放心写，不需要转义
//      （不走 ssh 命令行 VAR="..." 注入：ssh 会把参数按空格拼接后交给远端 shell 重新解析，
//        含空格的值（如 REMOTE_DIR="/path with space"）会被远端 shell 拆开）
//   3) Groovy 三引号字符串里反斜杠仍是转义符 → 全程不用反斜杠（不续行、不转义）
//   4) script 内容必须写在行首（不带缩进）：heredoc 会原样保留每行前导空格
def call(Map args = [:]) {
    def cfg  = args.config ?: error('remoteSh: 缺少参数 config')
    def envs = args.envs   ?: [:]
    def body = args.script ?: error('remoteSh: 缺少参数 script')

    // envs → 远端脚本开头的 export 行（值用单引号包裹，内部单引号转义成 '\''）
    def esc = { v -> v.toString().replace("'", "'\\''") }
    def envLines = envs.collect { k, v -> "export ${k}='${esc(v)}'" }.join('\n')

    withCredentials([sshUserPrivateKey(
        credentialsId  : cfg.ssh_cred,
        keyFileVariable: 'SSH_KEY'
    )]) {
        withEnv([
            "REMOTE_HOST=${cfg.remote_host}",
            "REMOTE_USER=${cfg.remote_user ?: 'root'}"
        ]) {
            sh '''
                set -eu
                REMOTE="${REMOTE_USER}@${REMOTE_HOST}"
                SSH_OPTS="-i ${SSH_KEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes"
                ssh ${SSH_OPTS} "${REMOTE}" bash -s <<'REMOTE_EOF'
''' + envLines + '\n' + body + '''
REMOTE_EOF
            '''
        }
    }
}
