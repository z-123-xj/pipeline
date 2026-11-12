// vars/paramBuild.groovy
// ===== 参数聚合：jobName 解析 + 配置校验 + 派生字段 → paramMap =====
//
// 纯参数处理，不包含任何 Jenkins 流程步骤（stage / 参数定义都在 pipelineInlet）
//
// 用法：paramBuild(jobName, cfg)
//   jobName  <项目>_<服务名>_<语言>_<部署>_<环境>
//   cfg      全局 config/config.yaml + 项目 yaml 合并后的配置（项目优先）
//
// 返回 paramMap = cfg + 以下字段：
//   PROJECT / SERVICE / LANGUAGE / DEPLOY / ENV_NAME   job 名五段（DEPLOY: k8s / host / docker）
//   remoteDir       产物在云上的目录（yaml 的 remote_dir 可覆盖，默认 /opt/cicd/<env>/<项目>/<服务>）
//   IMAGE_TAG       发布时间戳
//   IMAGE_FULL      k8s / docker 统一 = ECR 完整地址 <registry>/<项目>/<环境>/<服务>:<时间戳>（docker 不推时用同地址 save/load）
//   K8S_NS          k8s 命名空间（yaml 的 namespace 可覆盖）
//   service_dir     目标机工作目录（默认 /data/<项目>/<环境>/<服务>）
//   directDeploy    true = 直连 servicesIP 部署（不经跳板机）；remote_host 留空即触发
//   servicesIPList  servicesIP 解析后的列表（host / docker）
def call(String jobName, Map cfg) {
    def parts = jobName.split('_')
    if (parts.size() != 5) {
        error("paramBuild: Job 名格式错误，应为 <项目>_<服务名>_<语言>_<部署>_<环境>，实际: ${jobName}")
    }

    def paramMap = [:]
    paramMap.putAll(cfg)
    paramMap.putAll([
        PROJECT : parts[0],
        SERVICE : parts[1],
        LANGUAGE: parts[2],
        DEPLOY  : parts[3],
        ENV_NAME: parts[4],
    ])

    // 语言段校验：go / nodejs 两类（nodejs 默认 static 站；node_type: runtime 走 node 常驻；编译统一由 buildStage 处理）
    if (!(paramMap.LANGUAGE in ['go', 'nodejs'])) {
        error("paramBuild: 不支持的语言段 ${paramMap.LANGUAGE}（job 名第 3 段只允许 go / nodejs）")
    }

    // ===== 校验 =====
    // directDeploy：直连 servicesIP 部署（不经跳板机）。remote_host 留空即直连；
    //   也可显式 direct_deploy: true 强制直连（即便配了 remote_host 也直连）。
    //   直连模式下「分发到云」整段跳过，产物由部署步骤直传目标机。
    def hasRemote = paramMap.remote_host && paramMap.remote_host.toString().trim() != ''
    paramMap.directDeploy = (cfg.direct_deploy == true || cfg.direct_deploy == 'true') || !hasRemote

    // host / docker 在指定主机上跑（systemctl / docker），都必须能 SSH 到目标机
    //   → 需要 ssh_cred（跳板机或直连都用它）；需要 servicesIP 目标列表
    if (paramMap.DEPLOY in ['host', 'docker']) {
        if (!paramMap.ssh_cred) {
            error("paramBuild: ${paramMap.DEPLOY} 部署需要 ssh_cred（请在 config/config.yaml 或项目 yaml 里配置）")
        }
        if (!paramMap.servicesIP) {
            error("paramBuild: ${paramMap.DEPLOY} 部署缺少 servicesIP（请在服务 yaml 里配置目标主机列表）")
        }
    }
    // k8s / docker 镜像统一走 ECR 完整地址，都需要 image_registry
    if (paramMap.DEPLOY in ['k8s', 'docker']) {
        if (!paramMap.image_registry) {
            error("paramBuild: 缺少 image_registry（请在 config/config.yaml 里配置）")
        }
    }
    if (!(paramMap.DEPLOY in ['k8s', 'host', 'docker'])) {
        error("paramBuild: 不支持的部署段 ${paramMap.DEPLOY}（job 名第 4 段只允许 k8s / host / docker）")
    }

    // ===== 派生字段 =====
    // service_dir：服务在「目标机」的工作目录（compose 下发 / 容器数据 / host 二进制）。
    //   demo 是项目名不是固定值，用 PROJECT 占位；默认 /data/<项目>/<环境>/<服务>。
    // remoteDir：跳板机上的暂存/构建目录，默认与 service_dir 同路径（同一服务在跳板机和目标机用同一目录布局，docker 构建即部署目录）。
    //   仅当要把跳板机暂存单独放时才用 remote_dir 覆盖，常规无需配置。
    paramMap.service_dir = paramMap.service_dir ?: "/data/${paramMap.PROJECT}/${paramMap.ENV_NAME}/${paramMap.SERVICE}"
    paramMap.remoteDir   = paramMap.remote_dir ?: paramMap.service_dir
    // docker 与 k8s 一致：永远在流水线编译 + 构建镜像
    // docker_push：build 之后是否推送到镜像仓库（目标机直接 pull）。默认 true（与 k8s 一致）。
    // 设 false = 不推仓库，改为 docker save/load 直接分发到目标机（目标机无法 pull 仓库时用）。
    paramMap.docker_push  = (cfg.docker_push != false && cfg.docker_push != 'false')
    // images_clean：部署结束后清理历史镜像。默认 false
    //   构建机（agent/跳板机）→ docker image prune -a -f 全清
    //   目标机 → 仅清理当前服务仓库旧 tag，保留最近 images_clean_keep 个
    paramMap.images_clean = (cfg.images_clean == true || cfg.images_clean == 'true')
    def _keepRaw = cfg.images_clean_keep
    paramMap.images_clean_keep = (_keepRaw != null && _keepRaw.toString().trim()) ? _keepRaw.toString().trim().toInteger() : 3
    // compose_remove_orphans：compose up 是否加 --remove-orphans（删本项目里当前 compose 未定义的容器）。默认 true
    // 多服务共享项目里只接管单个服务时设 false，避免误删同项目其它服务（如 prod web01 的 nginx/admin/docs/site）
    paramMap.compose_remove_orphans = (cfg.compose_remove_orphans != false && cfg.compose_remove_orphans != 'false')
    paramMap.IMAGE_TAG  = new Date().format('yyyyMMddHHmmss')
    // 镜像地址：k8s / docker 统一用完整 ECR 地址；k8s 和 docker 仅部署阶段不同（apply / pull 或 save-load）
    paramMap.IMAGE_FULL = (paramMap.image ?: "${paramMap.image_registry}/${paramMap.PROJECT}/${paramMap.ENV_NAME}/${paramMap.SERVICE}") + ":${paramMap.IMAGE_TAG}"
    if (paramMap.DEPLOY == 'k8s') {
        paramMap.K8S_NS = paramMap.namespace ?: "${paramMap.PROJECT}-${paramMap.ENV_NAME}"
    }
    paramMap.servicesIPList = (paramMap.servicesIP ?: '')
        .toString()
        .split(',')
        .collect { it.trim() }
        .findAll { it }
    return paramMap
}
