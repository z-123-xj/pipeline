// vars/remoteDockerBuild.groovy
// ===== 构建镜像 =====
// 默认 SSH 到跳板机执行 docker build（remote 模式）；
// local: true 时改在 Jenkins agent 本地构建（直连模式：没有跳板机）。
//
// 用法：remoteDockerBuild(config: cfg, remoteDir: remoteDir [, local: true])
// 依赖 env.IMAGE_FULL（完整镜像地址）；local 模式 remoteDir = 本地构建上下文目录
def call(Map args = [:]) {
    def cfg       = args.config    ?: error('remoteDockerBuild: 缺少参数 config')
    def remoteDir = args.remoteDir ?: error('remoteDockerBuild: 缺少参数 remoteDir')
    def local     = args.local     ?: false

    // 构建上下文约定（见 deploy 目录分工）：
    //   docker/ = 构建相关目录，由人工在仓库事先准备好（Dockerfile + 构建期配置文件，如 nginx 的 default.conf）；
    //   host/  = 运行时配置目录，同样人工事先准备好，不参与镜像构建。
    // 流水线只负责把「构建产物」GO_BINARY（web 的 app.tgz / go 的二进制，rsyncArtifacts 已按原名
    // 放到 REMOTE_DIR 根）拷进 docker/ 构建上下文，不替 docker/ 从 host/ 拷贝任何配置文件。
    def script = '''SRC_DIR="${REMOTE_DIR}"
if [ -d "${REMOTE_DIR}/docker" ]; then SRC_DIR="${REMOTE_DIR}/docker"; fi
# 编译产物：本地直连模式下 GO_BINARY 是真实文件路径；跳板机模式下它在 REMOTE_DIR 根（同名）
if [ -n "${GO_BINARY:-}" ]; then
    if [ -f "${GO_BINARY}" ]; then
        cp -f "${GO_BINARY}" "${SRC_DIR}/"
    else
        BIN_NAME="$(basename "${GO_BINARY}")"
        if [ -f "${REMOTE_DIR}/${BIN_NAME}" ]; then
            cp -f "${REMOTE_DIR}/${BIN_NAME}" "${SRC_DIR}/${BIN_NAME}"
        fi
    fi
fi
cd "${SRC_DIR}"
docker build -t "${IMAGE_FULL}" .'''

    def envs = [REMOTE_DIR: remoteDir, IMAGE_FULL: env.IMAGE_FULL, GO_BINARY: env.GO_BINARY ?: '']

    if (local) {
        withEnv(["REMOTE_DIR=${remoteDir}", "IMAGE_FULL=${env.IMAGE_FULL}", "GO_BINARY=${env.GO_BINARY ?: ''}"]) {
            sh script
        }
    } else {
        remoteSh(config: cfg, envs: envs, script: script)
    }
}
