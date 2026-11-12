// vars/remoteK8sRestart.groovy
// ===== k8s 纯重启：跳过构建/配置/分发，直接滚动重启当前 Pod =====
//
// MODE=restart 专用：不 apply 配置、不换镜像，只对当前 deployment/statefulset
// 触发 rollout restart 并等滚动完成。跑在跳板机（remote_host）上，复用 remoteSh。
def call(Map args = [:]) {
    def cfg = args.config ?: error('remoteK8sRestart: 缺少参数 config')
    remoteSh(
        config: cfg,
        envs  : [K8S_NS: env.K8S_NS, SERVICE: env.SERVICE],
        script: '''set -eu
if kubectl -n "${K8S_NS}" get deployment "${SERVICE}" >/dev/null 2>&1; then
    echo "===== rollout restart deployment/${SERVICE} ====="
    kubectl -n "${K8S_NS}" rollout restart "deployment/${SERVICE}"
    kubectl -n "${K8S_NS}" rollout status "deployment/${SERVICE}" --timeout=300s
elif kubectl -n "${K8S_NS}" get statefulset "${SERVICE}" >/dev/null 2>&1; then
    echo "===== rollout restart statefulset/${SERVICE} ====="
    kubectl -n "${K8S_NS}" rollout restart "statefulset/${SERVICE}"
    kubectl -n "${K8S_NS}" rollout status "statefulset/${SERVICE}" --timeout=300s
else
    echo "FAIL: ${K8S_NS} 下既没有 deployment 也没有 statefulset ${SERVICE}" >&2
    exit 1
fi
'''
    )
}
