// vars/hostDeploy.groovy
// ===== host 类型部署编排：按 MODE 计算目标 → remoteHostDeploy =====
//
// 与 k8sDeploy 完全隔离：host 流程的改动不影响 k8s，反之亦然
//
// 用法：hostDeploy(paramMap)
// 依赖 env：GO_BINARY（编译产物路径，buildStage 设置）
// 依赖 cfg：servicesIP / gray_hosts / service_dir / service_name / service_unit
//
// MODE 分发：
//   deploy     → servicesIP 全部主机（默认，targets 传空 = 全部）
//   config     → 同 deploy 的目标，但不重新分发二进制（remoteHostDeploy doBinary=false），只更新配置 + 重启
//   Grayscale  → 灰度：gray_hosts（未配时取 servicesIP 第一台）
//   custom     → params.TARGET_HOSTS 手动勾选（一个没勾则报错，防止误发全部）
//   rollback   → 占位（remoteHostDeploy 尚未支持回滚）
def call(Map paramMap) {
    def mode    = params.MODE ?: 'deploy'
    def targets = ''

    switch (mode) {
        case 'Grayscale':
            targets = (paramMap.gray_hosts ?: (paramMap.servicesIPList ? paramMap.servicesIPList[0] : '')).toString()
            echo "hostDeploy >>> Grayscale 灰度目标: ${targets}"
            break
        case 'custom':
            def sel = params.TARGET_HOSTS ?: []
            if (sel instanceof CharSequence) { sel = [sel.toString()] }
            targets = sel.collect { it.toString() }.findAll { it }.join(',')
            if (!targets) {
                error('custom 模式请至少选择一台目标主机（MODE → custom 后勾选 TARGET_HOSTS）')
            }
            break
        case 'deploy':
        case 'config':
        case 'test':
        case 'restart':
            break   // targets 为空 = servicesIP 全部
        default:
            error("hostDeploy: MODE=${mode} 暂未实现（当前支持 deploy / config / Grayscale / custom / test / restart）")
    }

    remoteHostDeploy(
        config   : paramMap,
        remoteDir: paramMap.remoteDir,
        targets  : targets,
        mode     : mode,
        dryRun   : (mode == 'test')
    )
}
