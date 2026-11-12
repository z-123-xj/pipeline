// vars/remoteHostDeploy.groovy
// ===== host 类型部署：Jenkins →（跳板机）→ 目标主机，systemd unit 由 pipeline 写 =====
//
// 产物形态（按 GO_BINARY 后缀自动区分）：
//   *.tgz → 目录级部署：备份整个目录快照 → 解压覆盖 → install unit → restart
//   其他  → 单文件部署：备份旧二进制 → 替换 → restart
//
// 部署通道（directDeploy）：
//   remote_host 配了 → 跳板机通道（Jenkins→跳板机→目标机），二进制暂存在跳板机
//   remote_host 留空（或 direct_deploy:true）→ 直连通道（Jenkins→目标机），二进制从 agent 直传
//
// 用法：remoteHostDeploy(config: cfg, remoteDir: remoteDir, targets: '192.168.50.29:22,...')
//
// 依赖 env：
//   GO_BINARY     编译产物路径（buildStage 步骤设置）
// 依赖 cfg：
//   remote_host        跳板机地址（直连模式留空）
//   ssh_cred           跳板机 / 目标机 SSH 凭据
//   remote_user        跳板机 SSH 用户（默认 root）
//   servicesIP         全部目标主机列表（多选为空时取这个）
//   service_dir        产物在目标主机上的部署目录（默认 /data/<项目>/<环境>/<服务>）
//   service_name       systemd 服务名；不配 = 静态站点（nginx 托管），跳过 systemctl
//   service_unit       systemd unit 完整内容（pipeline 原样写到 /etc/systemd/system/<service_name>.service）；
//                      配了即视为「nodejs 启动型」，随部署 install + daemon-reload + restart
def call(Map args = [:]) {
    def cfg        = args.config    ?: error('remoteHostDeploy: 缺少参数 config')
    def remoteDir  = args.remoteDir ?: error('remoteHostDeploy: 缺少参数 remoteDir')
    def rawTargets = (args.targets ?: '').toString().trim()
    def mode       = args.mode      ?: 'deploy'
    def dryRun     = args.dryRun    ?: false

    def directDeploy = cfg.directDeploy == true || cfg.directDeploy == 'true'

    // 中转机（跳板机）：统一走 remote_*（默认来自 config/config.yaml，项目可覆盖）；直连模式不依赖它
    def cloudHost   = cfg.remote_host ?: ''
    def cloudCred   = cfg.ssh_cred   ?: error('缺少 ssh_cred（跳板机与目标机都用它）')
    def cloudUser   = cfg.remote_user ?: 'root'

    def serviceDir  = cfg.service_dir ?: "/data/${env.PROJECT}/${env.ENV_NAME}/${env.SERVICE}"
    def targetDir   = dryRun ? "${serviceDir}_test" : serviceDir   // test 演练分发到 _test 子目录，避免污染正式目录
    def serviceName = cfg.service_name ?: ''
    def apply       = !dryRun                                    // apply = 发布动作（systemctl restart / unit install）
    def doBinary    = (mode != 'restart' && mode != 'config')    // restart/config 沿用当前二进制，不重新分发

    // unit 内容原样写出（不留占位符、不做模板渲染 —— 配置文件里写啥就推啥）
    def unitContent = (cfg.service_unit ?: '').toString()

    // ===== 选目标 =====
    def allIps = (cfg.servicesIP ?: '').toString().split(',').collect { it.trim() }.findAll { it }
    if (!allIps) {
        error('servicesIP 为空（请在服务 yaml 里配置）')
    }
    def picked = rawTargets ? rawTargets.split(',').collect { it.trim() }.findAll { it } : allIps
    if (!picked) {
        error('没有选中任何目标主机')
    }
    echo "remoteHostDeploy >>> 目标主机: ${picked.join(', ')}（共 ${picked.size()}/${allIps.size()} 台）"

    def binaryName = doBinary ? new File(env.GO_BINARY).getName() : ''
    def isTarball  = doBinary ? env.GO_BINARY.endsWith('.tgz') : false
    // 二进制来源：跳板机通道在跳板机上；直连通道在 agent 本地
    def remoteBin  = directDeploy ? env.GO_BINARY : "${remoteDir}/${binaryName}"

    // 远端安装命令：tgz = 目录级部署（解压覆盖 + 目录快照备份）；二进制 = 单文件替换
    def remoteInstallCmd = isTarball ? """    ssh \${SSH_OPTS} "\${HOST}" "set -eu
        if [ -f \${BACKUP} ]; then
            rm -f \${BACKUP}
        fi
        if [ -d \${SERVICE_DIR} ]; then
            tar czf \${BACKUP} -C \${SERVICE_DIR} . || true
        fi
        tar xzf \${REMOTE_BIN} -C \${SERVICE_DIR}
        rm -f \${SERVICE_DIR}/\${BINARY_NAME}
        date > \${SERVICE_DIR}/.current
    "
""" : """    ssh \${SSH_OPTS} "\${HOST}" "set -eu
        if [ -f \${SERVICE_DIR}/\${BINARY_NAME}.bak ]; then
            rm -f \${SERVICE_DIR}/\${BINARY_NAME}.bak
        fi
        if [ -f \${SERVICE_DIR}/.current ]; then
            cp -f \${SERVICE_DIR}/.current \${SERVICE_DIR}/\${BINARY_NAME}.bak
        fi
        chmod +x \${SERVICE_DIR}/\${BINARY_NAME}
        ls -lh \${SERVICE_DIR}/\${BINARY_NAME} > \${SERVICE_DIR}/.current
    "
"""

    // 服务控制：service_unit 存在 = 写 unit + reload + restart；只有 service_name = restart 已存在的；都没有 = 静态站点
    def systemctlCmd = ''
    if (unitContent) {
        if (!serviceName) {
            error('配了 service_unit 就必须配 service_name（unit 文件名 = <service_name>.service）')
        }
        systemctlCmd = """    # 0) 安装/更新 systemd unit（首次部署也能直接起来；enable 幂等，已 enable 过无副作用）
    printf '%s\\n' "\${UNIT_CONTENT}" | ssh \${SSH_OPTS} "\${HOST}" "cat > \${UNIT_PATH}"
    ssh \${SSH_OPTS} "\${HOST}" "systemctl daemon-reload && systemctl enable \${SERVICE_NAME} && systemctl restart \${SERVICE_NAME}"
    sleep 2
    ssh \${SSH_OPTS} "\${HOST}" "systemctl is-active \${SERVICE_NAME} || true"
"""
    } else if (serviceName) {
        systemctlCmd = """    # 0) 重启已存在的服务（没配 service_unit，假设目标机 unit 已就绪）
    ssh \${SSH_OPTS} "\${HOST}" "systemctl restart \${SERVICE_NAME}"
    sleep 2
    ssh \${SSH_OPTS} "\${HOST}" "systemctl is-active \${SERVICE_NAME} || true"
"""
    }
    // test 演练不执行发布（systemctl restart / unit install）；restart 仍要执行
    if (!apply) { systemctlCmd = '' }

    // unit 内容随脚本带到目标机（quoted heredoc，内容里的 $ 不会被展开）
    def unitScriptHead = unitContent ? """
# unit 写入路径：/etc/systemd/system/（管理员自定义 unit 的标准位置；
# /usr/lib/systemd/system/ 是包管理器领域，会被 apt 升级覆盖，不要用）
UNIT_PATH="/etc/systemd/system/${serviceName}.service"
UNIT_CONTENT="\$(cat <<'UNIT_EOF'
${unitContent}
UNIT_EOF
)"
""" : ''

    def deployScript = """#!/bin/bash
set -eu

SERVICE_DIR="${targetDir}"
SERVICE_NAME="${serviceName}"
BINARY_NAME="${binaryName}"
REMOTE_BIN="${remoteBin}"
BACKUP="${isTarball ? "${serviceDir}.bak.tgz" : "${serviceDir}/${binaryName}.bak"}"
# 直连模式下 SSH_KEY_PATH 由 withEnv 注入（agent 上连目标机的私钥）；跳板机模式为空（内网免密）
SSH_KEY_PATH="\${SSH_KEY_PATH:-}"
APPLY="${apply ? '1' : '0'}"
DO_BINARY="${doBinary ? '1' : '0'}"
REMOTE_USER="${cloudUser}"
${unitScriptHead}
# 目标主机列表（每行 ip:port）
TARGETS=(
${picked.collect { "    ${it}" }.join('\n')}
)

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

${systemctlCmd}    # 1) 部署目录
    ssh \${SSH_OPTS} "\${HOST}" "mkdir -p \${SERVICE_DIR}"

    # 2) 推产物（来源 → 目标主机）
    if [ "\${DO_BINARY}" = "1" ]; then
    # 推产物用 rsync（带 -P 进度；不加 --delete：避免清掉 tgz 解压后的应用文件 / 历史备份）
    rsync -avzP -e "ssh \${SSH_OPTS}" "\${REMOTE_BIN}" "\${HOST}:\${SERVICE_DIR}/\${BINARY_NAME}"

${remoteInstallCmd}    fi
    echo "===== \${ip}:\${port} 部署完成 ====="
done

echo "===== 全部目标部署完成 ====="
"""

    if (directDeploy) {
        // 直连通道：在 agent 本地执行，二进制从 agent 直传目标机
        withCredentials([sshUserPrivateKey(credentialsId: cloudCred, keyFileVariable: 'SSH_KEY')]) {
            withEnv(["SSH_KEY_PATH=${SSH_KEY}"]) {
                sh """
                    set -eu
                    cat > deploy_host.sh <<'DEPLOY_EOF'
${deployScript}
DEPLOY_EOF
                    chmod +x deploy_host.sh && ./deploy_host.sh
                    rm -f deploy_host.sh
                """
            }
        }
    } else {
        // 跳板机通道：上传脚本到跳板机，由跳板机内网分发到目标主机
        withCredentials([
            sshUserPrivateKey(credentialsId: cloudCred, keyFileVariable: 'CLOUD_KEY')
        ]) {
            sh """
                set -eu
                CLOUD="${cloudUser}@${cloudHost}"
                CLOUD_SSH_BASE="-i \${CLOUD_KEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes"
                REMOTE_BIN="${remoteDir}/${binaryName}"
                ssh \${CLOUD_SSH_BASE} "\${CLOUD}" "ls -lh \${REMOTE_BIN} && file \${REMOTE_BIN}"
                cat > deploy_host.sh <<'DEPLOY_EOF'
${deployScript}
DEPLOY_EOF
                scp \${CLOUD_SSH_BASE} deploy_host.sh "\${CLOUD}:/tmp/deploy_host.sh"
                ssh \${CLOUD_SSH_BASE} "\${CLOUD}" "chmod +x /tmp/deploy_host.sh && /tmp/deploy_host.sh"
                ssh \${CLOUD_SSH_BASE} "\${CLOUD}" "rm -f /tmp/deploy_host.sh"
                rm -f deploy_host.sh
                echo "===== host 部署完成（${picked.size()}/${allIps.size()} 台）====="
            """
        }
    }
}
