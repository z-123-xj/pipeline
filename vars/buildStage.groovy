// vars/buildStage.groovy
// ===== 统一编译步骤：按 LANGUAGE 准备工具链 + 执行 build 命令（可覆盖）+ 产物打包 =====
//
// 服务 yaml 字段：
//   build       多行 shell 编译命令（覆盖语言默认）。不配 → 走语言默认（见 defaultCmd）
//   build_env   构建期环境变量 map（vite/next 烤进 bundle，或传给 go 程序）
//   （兼容旧字段 go_build / node_build，优先级低于 build）
//
// pipeline 仍负责：node/go 工具链与镜像、依赖缓存、输出目录变量、产物打包与 env.GO_BINARY 写入。
// 服务 yaml 只写"编译动作"，不重复环境；最终执行的命令会在日志里 echo 出来，方便审计。
//
// 用法：buildStage(paramMap)

def call(Map paramMap) {
    def LANGUAGE = paramMap.LANGUAGE?.toString()?.trim()
    def outDir   = "${env.WORKSPACE}/out"

    // ===== 解析编译命令（优先级：build > 旧 go_build/node_build > 语言默认）=====
    def defaultCmd
    switch (LANGUAGE) {
        case 'go':
            defaultCmd = '''set -eu
go test ./...
go vet ./...
go build -trimpath -tags netgo,osusergo -o ${GO_OUT_DIR}/${SERVER_NAME} .'''
            break
        case 'nodejs':
            defaultCmd = '''set -eu
pnpm install --frozen-lockfile
pnpm build'''
            break
        default:
            error("buildStage: 未知 LANGUAGE=${LANGUAGE}（支持 go / nodejs）")
    }
    def buildCmd = paramMap.build
        ?: (LANGUAGE == 'go' ? paramMap.go_build : paramMap.node_build)
        ?: defaultCmd

    // ===== build_env：构建期环境变量（统一注入，go / node 都生效）=====
    def buildEnvMap = [:]
    if (paramMap.build_env) {
        paramMap.build_env.each { k, v -> buildEnvMap["${k}"] = "${v}" }
    }

    echo "buildStage >>> LANGUAGE=${LANGUAGE}"
    echo "buildStage >>> 最终编译命令:\n${buildCmd}"
    if (buildEnvMap) { echo "buildStage >>> build_env: ${buildEnvMap}" }

    if (LANGUAGE == 'go') {
        runGo(paramMap, outDir, buildCmd, buildEnvMap)
    } else {
        runNode(paramMap, outDir, buildCmd, buildEnvMap)
    }
}

// ===== go 编译 + 产物（单二进制）=====
def runGo(Map paramMap, String outDir, String buildCmd, Map buildEnvMap) {
    def serverName = paramMap.server_name ?: 'main'

    // 私有依赖：GOPRIVATE 路径 + https→SSH 重写规则（go_private 前缀推导，git_url_rewrite 可显式覆盖）
    def privatePatterns = (paramMap.go_private ?: '')
        .toString().split(',').collect { it.trim() }.findAll { it }
    def rewriteMap = [:]
    if (paramMap.git_url_rewrite instanceof Map) {
        rewriteMap.putAll(paramMap.git_url_rewrite)
    } else {
        privatePatterns.each { pattern ->
            def clean = pattern.replaceAll('/[\\*]+$', '')
            def segs  = clean.split('/').findAll { it }
            if (segs.size() >= 2) {
                rewriteMap["https://${segs[0]}/${segs[1]}/"] = "git@${segs[0]}:${segs[1]}/"
            } else if (segs.size() == 1) {
                rewriteMap["https://${segs[0]}/"] = "git@${segs[0]}:"
            }
        }
    }

    def envList = [
        "BUILD_CMD=${buildCmd}",
        "GO_OUT_DIR=${outDir}",
        "SERVER_NAME=${serverName}",
        "GOPRIVATE=${privatePatterns.join(',')}",
        "GIT_CONFIG_COUNT=${rewriteMap.size()}"
    ]
    buildEnvMap.each { k, v -> envList << "${k}=${v}" }
    rewriteMap.eachWithIndex { entry, idx ->
        envList << "GIT_CONFIG_KEY_${idx}=url.${entry.value}.insteadOf"
        envList << "GIT_CONFIG_VALUE_${idx}=${entry.key}"
    }
    echo "buildStage(go) >>> GOPRIVATE=${privatePatterns.join(',')}"
    rewriteMap.each { https, ssh -> echo "buildStage(go) >>> 私有依赖重写: ${https} ⇒ ${ssh}" }

    def buildScript = '''
        set -eu
        export CGO_ENABLED=0
        export GOOS=linux
        export GOARCH=amd64
        export GOPROXY=${GOPROXY:-https://goproxy.cn,direct}
        export GOMODCACHE=${GOMODCACHE:-/data/build-cache/go/mod}
        export GOCACHE=${GOCACHE:-/data/build-cache/go/build}
        export PATH=$PATH:/usr/local/go/bin

        if [ -n "${GO_SSH_KEY:-}" ]; then
            export GIT_SSH_COMMAND="ssh -i ${GO_SSH_KEY} -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
        fi

        cd "${WORKSPACE}/src"
        mkdir -p "${GO_OUT_DIR}"
        go version
        sh -c "${BUILD_CMD}"
        ls -lh "${GO_OUT_DIR}"
    '''

    if (paramMap.ssh_credentials_id) {
        withCredentials([sshUserPrivateKey(
            credentialsId  : paramMap.ssh_credentials_id,
            keyFileVariable: 'GO_SSH_KEY'
        )]) {
            withEnv(envList) { sh buildScript }
        }
    } else {
        withEnv(envList) { sh buildScript }
    }

    def binary = "${outDir}/${serverName}"
    if (!fileExists(binary)) { error("buildStage(go): 编译产物不存在: ${binary}") }
    env.GO_OUT_DIR = outDir
    env.GO_BINARY  = binary
    echo "buildStage <<< go 编译完成: ${binary}"
}

// ===== node 编译 + 产物（tgz）=====
// node_type: runtime = Next.js standalone 常驻；默认 static = 静态站（nginx 托管）
def runNode(Map paramMap, String outDir, String buildCmd, Map buildEnvMap) {
    def npmRegistry = paramMap.npm_registry ?: ''
    def staticDir   = paramMap.node_static_dir ?: ''
    def nodeVersion = paramMap.node_version ?: ''
    def isRuntime   = (paramMap.node_type == 'runtime')

    def envList = [
        "BUILD_CMD=${buildCmd}",
        "NODE_OUT_DIR=${outDir}",
        "NPM_REGISTRY=${npmRegistry}",
        "NODE_STATIC_DIR=${staticDir}",
        "NODE_VERSION=${nodeVersion}",
        "IS_RUNTIME=${isRuntime ? '1' : '0'}"
    ]
    if (paramMap.node_memory) {
        envList << "NODE_OPTIONS=--max-old-space-size=${paramMap.node_memory}"
    }
    buildEnvMap.each { k, v -> envList << "${k}=${v}" }

    def buildScript = '''
        set -eu
        export NEXT_TELEMETRY_DISABLED=1

        if [ -n "${NODE_VERSION:-}" ]; then
            NODE_HOME="/usr/local/nvm/versions/node/${NODE_VERSION}"
            [ -d "${NODE_HOME}/bin" ] || { echo "node ${NODE_VERSION} 未安装（nvm install ${NODE_VERSION}）" >&2; exit 1; }
            export PATH="${NODE_HOME}/bin:${PATH}"
        fi

        command -v node >/dev/null 2>&1 || { echo "node 不可用：请在 Jenkins 节点安装 node LTS" >&2; exit 1; }
        NODE_BIN_DIR="$(dirname "$(readlink -f "$(command -v node)")")"
        export PATH="${NODE_BIN_DIR}:${PATH}"

        corepack enable 2>/dev/null || true
        command -v pnpm >/dev/null 2>&1 || { echo "pnpm 不可用：node 版本过低无 corepack" >&2; exit 1; }

        if [ -n "${NPM_REGISTRY:-}" ]; then
            export COREPACK_NPM_REGISTRY="${NPM_REGISTRY}"
            pnpm config set registry "${NPM_REGISTRY}"
        fi

        export npm_config_store_dir=/data/build-cache/node/pnpm-store
        export COREPACK_HOME=/data/build-cache/node/corepack
        mkdir -p "${npm_config_store_dir}" "${COREPACK_HOME}"

        node --version
        pnpm --version

        cd "${WORKSPACE}/src"
        mkdir -p "${NODE_OUT_DIR}"

        sh -c "${BUILD_CMD}"

        # ===== 产物打包：runtime 走 .next/standalone；static 用 node_static_dir 或自动探测 =====
        if [ "${IS_RUNTIME}" = "1" ]; then
            [ -d .next/standalone ] || { echo "buildStage(node): 缺少 .next/standalone（next.config 需 output: \\"standalone\\"）" >&2; exit 1; }
            STAGE="$(mktemp -d)"
            cp -a .next/standalone/. "${STAGE}/"
            mkdir -p "${STAGE}/.next"
            cp -a .next/static "${STAGE}/.next/"
            [ -d public ]        && cp -a public        "${STAGE}/"
            [ -d src/generated ] && { mkdir -p "${STAGE}/src"; cp -a src/generated "${STAGE}/src/"; }
            [ -d docs/ops ]      && { mkdir -p "${STAGE}/docs"; cp -a docs/ops "${STAGE}/docs/"; }
            [ -d data/geoip ]    && { mkdir -p "${STAGE}/data"; cp -a data/geoip "${STAGE}/data/"; }
            tar czf "${NODE_OUT_DIR}/app.tgz" -C "${STAGE}" .
            rm -rf "${STAGE}"
        else
            SRC=""
            if [ -n "${NODE_STATIC_DIR:-}" ]; then
                [ -d "${NODE_STATIC_DIR}" ] || { echo "buildStage(node): node_static_dir 不存在: ${NODE_STATIC_DIR}" >&2; exit 1; }
                SRC="${NODE_STATIC_DIR}"
            else
                for d in dist build out; do [ -d "$d" ] && { SRC="$d"; break; }; done
            fi
            [ -n "${SRC}" ] || { echo "buildStage(node): 未找到静态产物目录（dist/ build/ out/），可用 node_static_dir 指定" >&2; exit 1; }
            echo "buildStage(node) >>> 静态产物目录: ${SRC}"
            tar czf "${NODE_OUT_DIR}/app.tgz" -C "${SRC}" .
        fi

        ls -lh "${NODE_OUT_DIR}"
    '''

    withEnv(envList) { sh buildScript }

    def artifact = "${outDir}/app.tgz"
    if (!fileExists(artifact)) { error("buildStage(node): 打包产物不存在: ${artifact}") }
    env.GO_OUT_DIR = outDir
    env.GO_BINARY  = artifact
    echo "buildStage <<< node 编译打包完成: ${artifact}（${isRuntime ? 'standalone runtime' : 'static'}）"
}
