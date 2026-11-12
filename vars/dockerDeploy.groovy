// vars/dockerDeploy.groovy
// ===== docker 类型部署 =====
//
// 两种形态（deploy-configs 目录有无 compose.yml 自动分流）：
//   compose 模式：下发 compose.yml 到目标机，docker compose up -d --force-recreate
//   run 模式    ：docker run -d --name ... --restart=always
// 镜像分发（docker_push，默认 true）：true→推 ECR、目标机 pull；false→save/load 直传目标机
// docker 与 k8s 一致：默认在流水线编译 + 构建镜像（MODE=config 除外：沿用目标机当前镜像，不重新构建）
//
// 部署通道（directDeploy）：
//   remote_host 配了 → 跳板机通道（Jenkins→跳板机→目标机），构建/暂存在跳板机
//   remote_host 留空（或 direct_deploy:true）→ 直连通道（Jenkins→目标机），构建在 agent 本地
//
// 用法：dockerDeploy(paramMap)
// 依赖 env：IMAGE_FULL（build 模式）、SERVICE（容器名默认）
// 依赖 cfg：servicesIP / gray_hosts / container_name / docker_run_args / service_dir /
//           docker_push / directDeploy / ssh_cred
def call(Map paramMap) {
    def mode = params.MODE ?: 'deploy'

    // 目标选择（与 hostDeploy 一致）：deploy 全部 / Grayscale 灰度 / custom 手动勾选
    def targets = ''
    switch (mode) {
        case 'deploy':
        case 'config':
        case 'test':
        case 'restart':
            targets = (paramMap.servicesIPList ? paramMap.servicesIPList.join(',') : '')
            break
        case 'Grayscale':
            targets = (paramMap.gray_hosts ?: (paramMap.servicesIPList ? paramMap.servicesIPList[0] : '')).toString()
            echo "dockerDeploy >>> Grayscale 灰度目标: ${targets}"
            break
        case 'custom':
            def sel = params.TARGET_HOSTS ?: []
            if (!sel) { error('custom 模式请至少选择一台目标主机（MODE → custom 后勾选 TARGET_HOSTS）') }
            targets = sel.collect { it.toString() }.findAll { it }.join(',')
            break
        default:
            error("dockerDeploy: MODE=${mode} 暂未实现（当前支持 deploy / Grayscale / custom）")
    }

    def allIps = (paramMap.servicesIP ?: '').toString().split(',').collect { it.trim() }.findAll { it }
    if (!allIps) { error('servicesIP 为空（请在服务 yaml 里配置目标主机列表）') }
    def picked = targets ? targets.split(',').collect { it.trim() }.findAll { it } : allIps
    if (!picked) { error('没有选中任何目标主机') }
    echo "dockerDeploy >>> 目标主机: ${picked.join(', ')}（共 ${picked.size()}/${allIps.size()} 台）"

    def directDeploy = paramMap.directDeploy
    echo "dockerDeploy >>> 部署通道: ${directDeploy ? '直连(agent→servicesIP)' : '跳板机(' + paramMap.remote_host + ')'}｜remote_host=[${paramMap.remote_host}] direct_deploy=[${paramMap.direct_deploy}]"
    // docker 与 k8s 一致：永远在流水线编译 + 构建镜像
    // docker_push 只控制镜像分发方式（true→推 ECR 目标机 pull；false→save/load 直传）
    def pushMode   = paramMap.docker_push != false
    // config 模式不产生新镜像，跳过清洗（避免误删正在用的当前镜像）
    def imagesClean = paramMap.images_clean && mode != 'config'
    def imagesCleanKeep = paramMap.images_clean_keep ?: 3
    // ECR 仓库与区域：docker_push=true 时目标机 pull 前要现取令牌登录（ECR 令牌 12h 过期，每次部署重登）
    def ecrRegistry = paramMap.image_registry ?: ''
    def _regParts   = ecrRegistry.split('\\.')
    def ecrRegion   = (_regParts.size() >= 4 && _regParts[2] == 'ecr') ? _regParts[3] : 'ap-northeast-1'
    def serviceDir = paramMap.service_dir                  // 全局默认 /data/<项目>/<环境>/<服务>
    def dryRun    = (params.MODE == 'test')                // test 演练：构建+分发但不发布
    def apply     = !dryRun                                // apply = 真正的发布动作（compose up / docker run）
    def targetDir = dryRun ? "${serviceDir}_test" : serviceDir   // test 分发到 _test 子目录，避免污染正式目录
    def container  = paramMap.container_name ?: env.SERVICE
    def runArgs    = (paramMap.docker_run_args ?: '').toString()
    def image      = env.IMAGE_FULL
    def prevImage  = "${image.split(':')[0]}:prev"          // 旧镜像打个 prev tag，便于手动回滚（run 模式）
    def imageRepo  = "${image.split(':')[0]}"               // 去掉 :tag 的仓库名（目标机按服务清理用）
    def keepPlus1  = imagesCleanKeep + 1                    // tail 跳过前 N 个 = 保留最近 N 个
    def removeOrphans = (paramMap.compose_remove_orphans != false && paramMap.compose_remove_orphans != 'false')
    def orphanFlag    = removeOrphans ? '--remove-orphans' : ''   // 共享项目只接管单服务时置空，避免删其它服务

    def deployConfigDir = paramMap.deploy_config_dir ?: "${env.WORKSPACE}/deploy-configs"
    def svcDir = "${deployConfigDir}/${paramMap.ENV_NAME}/${paramMap.PROJECT}/${paramMap.SERVICE}"
    // host/ 存在则作为“推到目标机”的目录；否则回退到服务根目录（旧布局）
    def hostDir = directDeploy ? "${svcDir}/host" : "${paramMap.remoteDir}/host"
    // compose 源：deploy-configs 统一布局是 host/compose.yml，跳板机分支与直连一致都用 hostDir。
    //   rsyncArtifacts 把整个服务目录同步到 remoteDir/，故跳板机上 compose 实际在
    //   remoteDir/host/compose.yml（不是 remoteDir/compose.yml）；写错会判成 run 模式，
    //   起一个裸 docker run 容器，流量仍落在旧 compose 容器上。
    //   跳板机分支不能用 new File()（那是 agent 本地 FS），由部署脚本在跳板机上用 [ -f ] 判断。
    def composeSrc = directDeploy
        ? (new File("${hostDir}/compose.yml").exists() ? "${hostDir}/compose.yml" : "${svcDir}/compose.yml")
        : "${hostDir}/compose.yml"

    // ===== restart：跳过构建/分发/发布，直接重启目标机上的容器 =====
    // 通道与 deploy 一致：directDeploy→agent 本地直连 servicesIP；否则经 remote_host 跳板机内网转发。
    // 容器名不依赖 job yaml 的 container_name，运行时从目标机 compose.yml 读出真实名（带项目前缀）。
    if (mode == 'restart') {
        def restartScript = """#!/bin/bash
set -eu

CONTAINER="${container}"
# 现网容器名带项目前缀（compose 的 container_name），job yaml 一般不配 container_name；
# 兜底：从目标机 compose.yml 读出真实容器名，否则回落到服务名后 docker restart 找不到容器。
SERVICE_DIR="${paramMap.service_dir ?: ('/data/' + paramMap.PROJECT + '/' + paramMap.ENV_NAME + '/' + paramMap.SERVICE)}"
SSH_KEY_PATH="\${SSH_KEY_PATH:-}"
REMOTE_USER="${paramMap.remote_user ?: 'root'}"

TARGETS=(
${picked.collect { "    ${it}" }.join('\n')}
)

for entry in "\${TARGETS[@]}"; do
    ip="\$(echo "\${entry}" | cut -d: -f1)"
    port="\$(echo "\${entry}" | cut -d: -f2)"
    port="\${port:-22}"
    HOST="\${REMOTE_USER}@\${ip}"
    SSH_OPTS="-p \${port} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes"
    if [ -n "\${SSH_KEY_PATH}" ]; then
        SSH_OPTS="\${SSH_OPTS} -i \${SSH_KEY_PATH}"
    fi
    # 兜底取真实容器名（compose container_name，如 demo-prod-web01-agent-web-1）
    CNAME=\$(ssh \${SSH_OPTS} "\${HOST}" "grep -m1 '^[[:space:]]*container_name:' \${SERVICE_DIR}/compose.yml 2>/dev/null | sed 's/.*container_name:[[:space:]]*//' | tr -d '\\r'" || true)
    if [ -n "\${CNAME}" ]; then CONTAINER="\${CNAME}"; fi
    echo "===== 重启容器 \${CONTAINER} @ \${ip}:\${port} ====="
    ssh \${SSH_OPTS} "\${HOST}" "docker restart \${CONTAINER}"
    # 健康验证：轮询等待 running（restart 后容器初始化可能超过 2s，避免假失败）
    HEALTH_OK=0
    STATUS=''
    for i in \$(seq 1 20); do
        STATUS="\$(ssh \${SSH_OPTS} "\${HOST}" "docker inspect --format='{{.State.Status}}' \${CONTAINER}" 2>/dev/null || echo missing)"
        if [ "\${STATUS}" = "running" ]; then
            HEALTH_OK=1
            echo "容器状态: \${STATUS}（第 \${i} 次检查）"
            break
        fi
        echo "等待容器 running（\${i}/20，当前状态='\${STATUS}'）"
        sleep 3
    done
    if [ "\${HEALTH_OK}" != "1" ]; then
        echo "FAIL: \${ip} 上容器 \${CONTAINER} 60s 内未处于 running（最后状态='\${STATUS}'）" >&2
        exit 1
    fi
done
echo "===== 全部目标重启完成（\${#TARGETS[@]} 台）====="
"""
        if (directDeploy) {
            // 直连通道：agent 本地执行，ssh_cred 私钥直连 servicesIP
            withCredentials([sshUserPrivateKey(credentialsId: paramMap.ssh_cred, keyFileVariable: 'SSH_KEY')]) {
                withEnv(["SSH_KEY_PATH=${SSH_KEY}"]) {
                    sh """
                        set -eu
                        cat > restart_docker.sh <<'RESTART_EOF'
${restartScript}
RESTART_EOF
                        chmod +x restart_docker.sh && ./restart_docker.sh
                        rm -f restart_docker.sh
                    """
                }
            }
        } else {
            // 跳板机通道：与 deploy 主流程一致，脚本上传到 remote_host 执行，
            // 由跳板机内网免密 SSH 到 servicesIP（Jenkins agent 连不到内网目标）。
            // 不注入 SSH_KEY_PATH：跳板机→目标机不需要 -i，restartScript 里 SSH_OPTS 也就不会带 -i。
            withCredentials([sshUserPrivateKey(credentialsId: paramMap.ssh_cred, keyFileVariable: 'CLOUD_KEY')]) {
                sh """
                    set -eu
                    CLOUD="${paramMap.remote_user ?: 'root'}@${paramMap.remote_host}"
                    CLOUD_SSH_BASE="-i \${CLOUD_KEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes"
                    cat > restart_docker.sh <<'RESTART_EOF'
${restartScript}
RESTART_EOF
                    scp \${CLOUD_SSH_BASE} restart_docker.sh "\${CLOUD}:/tmp/restart_docker.sh"
                    ssh \${CLOUD_SSH_BASE} "\${CLOUD}" "chmod +x /tmp/restart_docker.sh && /tmp/restart_docker.sh"
                    ssh \${CLOUD_SSH_BASE} "\${CLOUD}" "rm -f /tmp/restart_docker.sh"
                    rm -f restart_docker.sh
                    echo "===== docker restart 完成（经跳板机 ${paramMap.remote_host}）====="
                """
            }
        }
        return
    }

    // ===== 构建（docker 默认永远构建；config 模式除外：沿用目标机当前镜像，不重新构建/推送）=====
    if (mode == 'config') {
        echo "dockerDeploy >>> config 模式：跳过编译/构建/推送，沿用目标机当前镜像"
    } else if (directDeploy) {
        // 直连：在 agent 本地构建（没有跳板机）。构建上下文 = 本地 deploy-configs 服务目录 + 编译产物
        def localCtx = "${env.WORKSPACE}/.docker-build/${paramMap.SERVICE}"
        // 构建上下文：优先 docker/ 子目录（仅构建用），否则整个服务目录（旧布局）
        def buildSrc = new File("${svcDir}/docker").exists() ? "${svcDir}/docker" : "${svcDir}"
        sh """
            set -eu
            rm -rf "${localCtx}"; mkdir -p "${localCtx}"
            cp -r "${buildSrc}/." "${localCtx}/"
            if [ -n "\${GO_BINARY:-}" ] && [ -f "\${GO_BINARY}" ]; then
                cp "\${GO_BINARY}" "${localCtx}/"
            fi
            docker build -t "${image}" "${localCtx}"
        """
        if (pushMode) {
            remoteDockerPush(config: paramMap, remoteDir: '', local: true)
        }
    } else {
        remoteDockerBuild(config: paramMap, remoteDir: paramMap.remoteDir)
        if (pushMode) {
            remoteDockerPush(config: paramMap, remoteDir: paramMap.remoteDir)
        }
    }

    // ===== 部署脚本（两种通道共用，差异在 SSH 通道与 compose 源）=====
    def deployScript = """#!/bin/bash
set -eu

IMAGE="${image}"
PREV_IMAGE="${prevImage}"
CONTAINER="${container}"
SERVICE="${env.SERVICE}"
SERVICE_DIR="${targetDir}"
RUN_ARGS="${runArgs}"
COMPOSE_SRC="${composeSrc}"
HOST_DIR="${hostDir}"
DOCKER_PUSH="${pushMode}"
APPLY="${apply ? '1' : '0'}"
CONFIG_MODE="${mode == 'config' ? '1' : '0'}"
IMAGES_CLEAN="${imagesClean}"
REMOTE_USER="${paramMap.remote_user ?: 'root'}"

TARGETS=(
${picked.collect { "    ${it}" }.join('\n')}
)

# 直连模式下 SSH_KEY_PATH 由 withEnv 注入（agent 上连目标机的私钥）；
# 跳板机模式下为空，目标机 SSH 走跳板机内网免密（不传 -i）
SSH_KEY_PATH="\${SSH_KEY_PATH:-}"

if [ -f "\${COMPOSE_SRC}" ]; then
    COMPOSE_MODE=1
    if grep -q '<image>' "\${COMPOSE_SRC}"; then
        SRC_COMPOSE="\$(mktemp /tmp/demo-compose.XXXXXX)"
        trap 'rm -f "\${SRC_COMPOSE}"' EXIT
        sed "s|<image>|\${IMAGE}|g" "\${COMPOSE_SRC}" > "\${SRC_COMPOSE}"
    else
        SRC_COMPOSE="\${COMPOSE_SRC}"
    fi
else
    COMPOSE_MODE=0
fi

for entry in "\${TARGETS[@]}"; do
    ip="\$(echo "\${entry}" | cut -d: -f1)"
    port="\$(echo "\${entry}" | cut -d: -f2)"
    port="\${port:-22}"
    echo "=========================================="
    echo "===== 目标主机: \${ip}:\${port} ====="
    echo "=========================================="
    HOST="\${REMOTE_USER}@\${ip}"
    SSH_OPTS="-p \${port} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes"
    SCP_OPTS="-P \${port} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes"
    if [ -n "\${SSH_KEY_PATH}" ]; then
        SSH_OPTS="\${SSH_OPTS} -i \${SSH_KEY_PATH}"
        SCP_OPTS="\${SCP_OPTS} -i \${SSH_KEY_PATH}"
    fi

    # ===== config 模式：取目标机当前镜像复用（不重新构建、不 pull/load）=====
    if [ "\${CONFIG_MODE}" = "1" ]; then
        if [ "\${COMPOSE_MODE}" = "1" ]; then
            # compose 容器名：显式 container_name 优先，否则 <项目名>-<服务名>-1
            CREF="\$(grep -m1 'container_name:' "\${SRC_COMPOSE}" | sed 's/.*container_name:[[:space:]]*//' | tr -d '\r' || true)"
            if [ -z "\${CREF}" ]; then
                CREF="\$(grep -m1 '^name:' "\${SRC_COMPOSE}" | sed 's/^name:[[:space:]]*//' | tr -d '\r')-\${SERVICE}-1"
            fi
        else
            CREF="\${CONTAINER}"
        fi
        echo "----- 取 \${ip} 当前镜像（容器 \${CREF}）-----"
        IMAGE="\$(ssh \${SSH_OPTS} "\${HOST}" "docker inspect --format='{{.Config.Image}}' \${CREF}" 2>/dev/null || true)"
        [ -n "\${IMAGE}" ] || { echo "FAIL: \${ip} 上取不到容器 \${CREF} 的当前镜像（config 模式要求服务已在运行）" >&2; exit 1; }
        echo "----- 沿用当前镜像 \${IMAGE}（不重新构建）-----"
    fi

    if [ "\${COMPOSE_MODE}" = "1" ]; then
        # 先把镜像送到目标机：docker_push=true → 目标机 pull；false → save/load 直传
        # config 模式跳过：镜像已在目标机（上面已取当前镜像）
        if [ "\${CONFIG_MODE}" != "1" ]; then
            if [ "\${DOCKER_PUSH}" = "true" ]; then
                echo "----- 目标机登录 ECR 并 pull 镜像 \${IMAGE} -----"
                ssh \${SSH_OPTS} "\${HOST}" "aws ecr get-login-password --region ${ecrRegion} | docker login --username AWS --password-stdin ${ecrRegistry} && docker pull \${IMAGE}"
            else
                echo "----- 分发镜像 \${IMAGE}（save/load）-----"
                docker save "\${IMAGE}" | ssh \${SSH_OPTS} "\${HOST}" "docker load"
            fi
        fi
        echo "----- 下发 compose 并 up -----"
        ssh \${SSH_OPTS} "\${HOST}" "mkdir -p \${SERVICE_DIR}"
        # host/ 同步到目标：rsync --delete 清掉目标机多余文件/目录，保持与源完全一致
        # --exclude compose：config/restart 沿用目标机已有 compose 不覆盖；deploy/test 模式下方单独下发
        if [ -d "\${HOST_DIR}" ]; then
            echo "----- 同步 host/（排除 compose.yml）到目标 \${SERVICE_DIR} -----"
            rsync -avzP --delete --exclude='compose.yml' --exclude='compose.yaml' -e "ssh \${SSH_OPTS}" "\${HOST_DIR}/" "\${HOST}:\${SERVICE_DIR}/"
        fi
        # compose.yml：deploy/test 模式随清单一并下发；config/restart 沿用目标机已有 compose，不再下发避免覆盖
        if [ "\${CONFIG_MODE}" != "1" ] && [ -f "\${SRC_COMPOSE}" ]; then
            echo "----- 下发 compose.yml（deploy/test 模式）-----"
            scp \${SCP_OPTS} "\${SRC_COMPOSE}" "\${HOST}:\${SERVICE_DIR}/compose.yml"
        fi
        if [ "\${APPLY}" = "1" ]; then
            # 同名容器可能来自其他 compose 项目（容器名冲突），先 rm 再 up 接管；rm 幂等无副作用
            CNAME=\$(ssh \${SSH_OPTS} "\${HOST}" "grep -m1 'container_name:' \${SERVICE_DIR}/compose.yml | sed 's/.*container_name:[[:space:]]*//'")
            ssh \${SSH_OPTS} "\${HOST}" "docker rm -f \${CNAME} 2>/dev/null || true"
            ssh \${SSH_OPTS} "\${HOST}" "cd \${SERVICE_DIR} && docker compose up -d --force-recreate ${orphanFlag}"
            # 健康验证：轮询等待 compose 服务 running。--force-recreate 后容器创建/启动常超过
            # 3s，固定 sleep 会拍到空快照（ps 无输出 → 状态= 为空）误报 FAIL；最多等 60s，成功即退出。
            HEALTH_OK=0
            STATE=''
            for i in \$(seq 1 20); do
                STATE="\$(ssh \${SSH_OPTS} "\${HOST}" "cd \${SERVICE_DIR} && docker compose ps --format '{{.State}}' | head -1" 2>/dev/null || echo missing)"
                if [ "\${STATE}" = "running" ]; then
                    HEALTH_OK=1
                    echo "容器状态: \${STATE}（第 \${i} 次检查）"
                    break
                fi
                echo "等待容器 running（\${i}/20，当前状态='\${STATE}'）"
                sleep 3
            done
            if [ "\${HEALTH_OK}" != "1" ]; then
                echo "FAIL: \${ip} compose 服务 60s 内未处于 running（最后状态='\${STATE}'）" >&2
                exit 1
            fi
        fi
    else
        # ---- run 模式 ----
        # config 模式跳过：镜像已在目标机（上面已取当前镜像）
        if [ "\${CONFIG_MODE}" != "1" ]; then
            if [ "\${DOCKER_PUSH}" = "true" ]; then
                echo "----- 目标机登录 ECR 并 pull 镜像 \${IMAGE} -----"
                ssh \${SSH_OPTS} "\${HOST}" "aws ecr get-login-password --region ${ecrRegion} | docker login --username AWS --password-stdin ${ecrRegistry} && docker pull \${IMAGE}"
            else
                echo "----- 分发镜像 \${IMAGE}（save/load）-----"
                docker save "\${IMAGE}" | ssh \${SSH_OPTS} "\${HOST}" "docker load"
            fi
        fi

        if [ "\${APPLY}" = "1" ]; then
            echo "----- 切换容器 \${CONTAINER} -----"
            ssh \${SSH_OPTS} "\${HOST}" "set -eu
                if docker inspect "\${CONTAINER}" >/dev/null 2>&1; then
                    OLD_IMAGE=\\\$(docker inspect --format='{{.Image}}' "\${CONTAINER}")
                    docker tag "\\\${OLD_IMAGE}" "\${PREV_IMAGE}" 2>/dev/null || true
                    docker stop "\${CONTAINER}" >/dev/null
                    docker rm "\${CONTAINER}" >/dev/null
                    echo "旧容器已停止并移除（镜像已留 \${PREV_IMAGE} 便于回滚）"
                else
                    echo "无旧容器，首次部署"
                fi
            "

            echo "----- 起新容器 -----"
            ssh \${SSH_OPTS} "\${HOST}" "set -eu
                mkdir -p "\${SERVICE_DIR}"
                docker run -d --name "\${CONTAINER}" --restart=always \${RUN_ARGS} "\${IMAGE}"
            "

            # 健康验证：轮询等待容器 running（同 compose 模式，避免固定 sleep 的假失败）
            HEALTH_OK=0
            STATUS=''
            for i in \$(seq 1 20); do
                STATUS="\$(ssh \${SSH_OPTS} "\${HOST}" "docker inspect --format='{{.State.Status}}' "\${CONTAINER}" 2>/dev/null || echo missing")"
                if [ "\${STATUS}" = "running" ]; then
                    HEALTH_OK=1
                    echo "容器状态: \${STATUS}（第 \${i} 次检查）"
                    break
                fi
                echo "等待容器 running（\${i}/20，当前状态='\${STATUS}'）"
                sleep 3
            done
            if [ "\${HEALTH_OK}" != "1" ]; then
                echo "FAIL: \${ip} 上容器 \${CONTAINER} 60s 内未处于 running（最后状态='\${STATUS}'）" >&2
                exit 1
            fi
        fi
    fi
    if [ "\${IMAGES_CLEAN}" = "true" ]; then
        echo "----- 清理 \${ip} 上 ${imageRepo} 旧镜像（保留最近 ${imagesCleanKeep} 个）-----"
        OLD_TAGS=\$(ssh \${SSH_OPTS} "\${HOST}" "docker images '${imageRepo}' --format '{{.Tag}}'" | grep -v '^<none>\$' | sort -r | tail -n +${keepPlus1})
        for t in \${OLD_TAGS}; do
            echo "  移除 \${ip} ${imageRepo}:\$t"
            ssh \${SSH_OPTS} "\${HOST}" "docker rmi '${imageRepo}:\$t'" 2>/dev/null || true
        done
    fi
    echo "===== \${ip}:\${port} 部署完成 ====="
done

echo "===== 全部目标部署完成（\${#TARGETS[@]} 台）====="
"""

    if (directDeploy) {
        // 直连通道：在 agent 本地执行部署脚本，直连各目标机（需 ssh_cred 能连目标机）
        withCredentials([sshUserPrivateKey(credentialsId: paramMap.ssh_cred, keyFileVariable: 'SSH_KEY')]) {
            withEnv(["SSH_KEY_PATH=${SSH_KEY}"]) {
                sh """
                    set -eu
                    cat > deploy_docker.sh <<'DEPLOY_EOF'
${deployScript}
DEPLOY_EOF
                    chmod +x deploy_docker.sh && ./deploy_docker.sh
                    rm -f deploy_docker.sh
                """
            }
        }
    } else {
        // 跳板机通道：上传脚本到跳板机执行（内网免密分发到目标机）
        withCredentials([sshUserPrivateKey(credentialsId: paramMap.ssh_cred, keyFileVariable: 'CLOUD_KEY')]) {
            sh """
                set -eu
                CLOUD="${paramMap.remote_user ?: 'root'}@${paramMap.remote_host}"
                CLOUD_SSH_BASE="-i \${CLOUD_KEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes"
                # 确认跳板机上产物/compose 就位（rsyncArtifacts 已推送）
                ssh \${CLOUD_SSH_BASE} "\${CLOUD}" "ls -lh ${paramMap.remoteDir}/"
                cat > deploy_docker.sh <<'DEPLOY_EOF'
${deployScript}
DEPLOY_EOF
                scp \${CLOUD_SSH_BASE} deploy_docker.sh "\${CLOUD}:/tmp/deploy_docker.sh"
                ssh \${CLOUD_SSH_BASE} "\${CLOUD}" "chmod +x /tmp/deploy_docker.sh && /tmp/deploy_docker.sh"
                ssh \${CLOUD_SSH_BASE} "\${CLOUD}" "rm -f /tmp/deploy_docker.sh"
                rm -f deploy_docker.sh
                echo "===== docker 部署完成（${picked.size()}/${allIps.size()} 台）====="
            """
        }
    }

    // ===== 镜像清理（images_clean: true）：清理「构建机」上的历史镜像 =====
    // 目标机清理已在部署脚本内逐台执行；这里清理构建机（直连=agent 本地；跳板机=remote_host）
    if (imagesClean) {
        if (directDeploy) {
            echo 'dockerDeploy >>> 清理构建机（agent）镜像：docker image prune -a -f'
            sh 'docker image prune -a -f'
        } else {
            echo 'dockerDeploy >>> 清理构建机（跳板机）镜像：docker image prune -a -f'
            withCredentials([sshUserPrivateKey(credentialsId: paramMap.ssh_cred, keyFileVariable: 'CLEAN_KEY')]) {
                sh """
                    set -eu
                    ssh -i \${CLEAN_KEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes \\
                        "${paramMap.remote_user ?: 'root'}@${paramMap.remote_host}" "docker image prune -a -f"
                """
            }
        }
    }
}
