// vars/rsyncArtifacts.groovy
// ===== 分发到云：编译产物 + 部署配置 rsync 到云服务器 =====
// 跟 remoteSh 不同：这一步主体是本地 rsync（不走远端 heredoc），所以单独实现
//
// 用法：rsyncArtifacts(config: cfg, remoteDir: remoteDir)
// 依赖的 env：
//   GO_BINARY          编译产物路径（buildStage：go 单二进制 / node 的 app.tgz）
//   WORKSPACE / ENV_NAME / PROJECT / SERVICE（Jenkinsfile 头部设置）
def call(Map args = [:]) {
    def cfg       = args.config    ?: error('rsyncArtifacts: 缺少参数 config')
    def remoteDir = args.remoteDir ?: error('rsyncArtifacts: 缺少参数 remoteDir')

    // 直连模式（remote_host 留空 / direct_deploy: true）：不往跳板机暂存，产物由部署步骤直传目标机 → 跳过
    def direct = (cfg.direct_deploy == true || cfg.direct_deploy == 'true') \
        || !(cfg.remote_host && cfg.remote_host.toString().trim())
    if (direct) {
        echo 'rsyncArtifacts: 直连部署模式，跳过「分发到云」（产物稍后由部署步骤直传目标机）'
        return
    }

    withCredentials([sshUserPrivateKey(
        credentialsId  : cfg.ssh_cred,
        keyFileVariable: 'SSH_KEY'
    )]) {
        withEnv([
            "REMOTE_HOST=${cfg.remote_host}",
            "REMOTE_USER=${cfg.remote_user ?: 'root'}",
            "REMOTE_DIR=${remoteDir}",
            "DEPLOY_CONFIG_DIR=${cfg.deploy_config_dir ?: env.WORKSPACE + '/deploy-configs'}/${env.ENV_NAME}/${env.PROJECT}/${env.SERVICE}"
        ]) {
            sh '''
                set -eu
                REMOTE="${REMOTE_USER}@${REMOTE_HOST}"
                SSH_OPTS="-i ${SSH_KEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes"

                # 1. 建远端目录
                ssh ${SSH_OPTS} "${REMOTE}" "mkdir -p ${REMOTE_DIR}"

                # 2. 推送部署配置（仅 k8s 模式有；host 模式跳过了「拉部署配置」stage，目录不存在就不推）
                #    --delete：远端目录和仓库保持完全一致，历史脏 yaml（如残缺的 diff 片段）不会残留累积
                #    必须排在产物推送之前：--delete 会清掉远端目录里配置目录没有的文件（含上次的二进制），
                #    先推配置再推二进制，最终远端 = 配置 + 本次二进制，不多不少
                if [ -d "${DEPLOY_CONFIG_DIR}" ]; then
                    rsync -avzP --delete -e "ssh ${SSH_OPTS}" \
                        "${DEPLOY_CONFIG_DIR}/" \
                        "${REMOTE}:${REMOTE_DIR}/"
                fi


                # 3. 推送产物（rsync）：单个文件（go 二进制或 node 的 tgz 包）
                #    restart 模式没有编译产物（GO_BINARY 为空）→ 跳过，只同步配置
                #    注意：此处故意不加 --delete —— 否则会删掉第 2 步刚同步的配置目录（docker/host/k8s）
                if [ -n "${GO_BINARY:-}" ] && [ -f "${GO_BINARY}" ]; then
                    rsync -avzP -e "ssh ${SSH_OPTS}" \
                        "${GO_BINARY}" \
                        "${REMOTE}:${REMOTE_DIR}/"
                else
                    echo "跳过产物推送（GO_BINARY 为空或不存在：restart 模式仅同步部署配置）"
                fi

                # 4. 验证一下（用通配符避免命令替换/反斜杠转义问题）
                ssh ${SSH_OPTS} "${REMOTE}" "ls -lh ${REMOTE_DIR}/ && file ${REMOTE_DIR}/*" 2>/dev/null || \
                    ssh ${SSH_OPTS} "${REMOTE}" "ls -lh ${REMOTE_DIR}/"
            '''
        }
    }
}
