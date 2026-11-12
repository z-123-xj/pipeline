// vars/k8sDeploy.groovy
// ===== k8s 类型部署编排：构建镜像 → 推 ECR → kubectl apply =====
//
// 与 hostDeploy 完全隔离：k8s 流程的改动不影响 host，反之亦然
//
// 用法：k8sDeploy(paramMap)
// 依赖 env：IMAGE_FULL / K8S_NS / SERVICE（pipelineInlet 设置）
// 依赖 cfg：aws_region / image_registry
//
// MODE：
//   deploy  正常发版：构建 → 推 ECR → apply → 等滚动完成
//   config  只改配置：跳过构建/推送，沿用集群当前镜像渲染 → apply 清单 → rollout restart
//   restart 直接滚动重启当前 Pod（跳过拉代码/编译/分发，不 apply 配置、不换镜像）
//   test    演练：构建 + 推 ECR，但跳过 kubectl apply（不改动集群）
def call(Map paramMap) {
    def mode = params.MODE ?: 'deploy'

    // Grayscale / custom 是 host 类型参数，k8s 不适用；rollback 未实现（revert 配置后重新 deploy）
    // test 演练：构建 + 推 ECR，但跳过 kubectl apply（不改动集群）
    if (!(mode in ['deploy', 'restart', 'test', 'config'])) {
        error("k8sDeploy: MODE=${mode} 暂未实现（k8s 类型支持 deploy / config / restart / test）")
    }

    if (mode == 'restart') {
        echo "k8sDeploy >>> restart 模式：跳过构建/配置/分发，直接滚动重启"
        remoteK8sRestart(config: paramMap)
    } else if (mode == 'config') {
        // config：不重新构建/推送；restart:true 让 apply 用集群当前镜像渲染，
        // 并 rollout restart（configmap 是 subPath 挂载不会热更新，必须重建 Pod 才生效）
        echo "k8sDeploy >>> config 模式：跳过构建/推送，沿用集群当前镜像，apply 清单 + 滚动重启"
        remoteK8sApply(config: paramMap, remoteDir: paramMap.remoteDir, restart: true)
    } else {
        remoteDockerBuild(config: paramMap, remoteDir: paramMap.remoteDir)
        remoteDockerPush (config: paramMap, remoteDir: paramMap.remoteDir)
        if (mode == 'deploy') {
            remoteK8sApply(config: paramMap, remoteDir: paramMap.remoteDir)
        }
    }
}
