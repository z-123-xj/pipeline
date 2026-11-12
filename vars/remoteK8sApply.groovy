// vars/remoteK8sApply.groovy
// ===== k8s 部署：创建 ns → 渲染 <image> → 打印 yaml → apply → （restart 时重建 Pod）→ 等滚动完成 =====
//
// 两种模式（由 k8sDeploy 传 restart 参数决定）：
//   restart=false（deploy） 用 IMAGE_FULL（本次构建的新镜像 tag）渲染后 apply
//   restart=true （restart）不换镜像：先读集群当前生产镜像，用它渲染后 apply，
//                           再 rollout restart —— configmap 是 subPath 挂载不会热更新，
//                           改了配置必须重建 Pod 才生效（这是 restart 模式存在的全部意义）
//
// 命名空间来源：项目 yaml 的 namespace: 字段（paramBuild 默认 ${PROJECT}-${ENV_NAME} 兜底）
def call(Map args = [:]) {
    remoteSh(
        config: args.config,
        envs  : [
            REMOTE_DIR : args.remoteDir,
            IMAGE_FULL : env.IMAGE_FULL,
            K8S_NS     : env.K8S_NS,
            SERVICE    : env.SERVICE,
            RESTART    : (args.restart ? 'true' : 'false')
        ],
        script: '''set -eu
# ===== 创建命名空间（幂等，已存在则 no-op） =====
kubectl create namespace "${K8S_NS}" --dry-run=client -o yaml | kubectl apply -f -

# 清单目录：优先 k8s/ 子目录（新结构），否则服务目录根（兼容旧布局）
MANI_DIR="${REMOTE_DIR}"
if [ -d "${REMOTE_DIR}/k8s" ]; then MANI_DIR="${REMOTE_DIR}/k8s"; fi

# ===== 敏感配置（Secret）：随清单一起 apply（k8s/secrets 优先，兼容根 secrets） =====
if [ -d "${REMOTE_DIR}/k8s/secrets" ]; then
    echo "===== apply 敏感配置（Secret）到 ${K8S_NS} ====="
    kubectl -n "${K8S_NS}" apply -f "${REMOTE_DIR}/k8s/secrets"
elif [ -d "${REMOTE_DIR}/secrets" ]; then
    echo "===== apply 敏感配置（Secret）到 ${K8S_NS} ====="
    kubectl -n "${K8S_NS}" apply -f "${REMOTE_DIR}/secrets"
fi

# ===== 决定渲染用的镜像 =====
# restart：沿用集群当前镜像（deployment 优先，StatefulSet 兜底），不引入新 tag
if [ "${RESTART}" = "true" ]; then
    RENDER_IMAGE="$(kubectl -n "${K8S_NS}" get deployment "${SERVICE}" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
    if [ -z "${RENDER_IMAGE}" ]; then
        RENDER_IMAGE="$(kubectl -n "${K8S_NS}" get statefulset "${SERVICE}" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
    fi
    [ -n "${RENDER_IMAGE}" ] || { echo "FAIL: restart 模式取不到 ${K8S_NS}/${SERVICE} 的当前镜像（deployment/statefulset 都不存在？）" >&2; exit 1; }
    echo "===== restart 模式：沿用当前镜像 ${RENDER_IMAGE} ====="
else
    RENDER_IMAGE="${IMAGE_FULL}"
fi

# ===== 渲染 <image> → 真实镜像，输出到临时目录，同时打印到 Jenkins 日志 =====
RENDER_DIR="$(mktemp -d)"
trap "rm -rf '${RENDER_DIR}'" EXIT
cd "${MANI_DIR}"
for f in *.yaml *.yml; do
    [ -f "$f" ] || continue
    # 跳过非 k8s 清单：job 配置(<项目>_<服务>_<语言>_<部署>_<环境>.yaml)与清单同目录，但无 kind: 字段
    grep -q '^kind:' "$f" || { echo "----- 跳过非 k8s 文件: ${f} -----"; continue; }
    sed "s|<image>|${RENDER_IMAGE}|g" "$f" > "${RENDER_DIR}/${f}"
    echo ""
    echo "===== 渲染后的 ${f} ====="
    cat "${RENDER_DIR}/${f}"
done
test -z "$(ls -A "${RENDER_DIR}" 2>/dev/null)" && { echo "FAIL: ${REMOTE_DIR} 下没有任何 *.yaml/*.yml" >&2; exit 1; }

# ===== apply =====
kubectl -n "${K8S_NS}" apply -f "${RENDER_DIR}"

# ===== restart：apply 本身不会重建 Pod（spec 没变），必须显式 rollout restart =====
if [ "${RESTART}" = "true" ]; then
    echo "===== rollout restart ${SERVICE}（重放配置，Pod 重建后生效）====="
    kubectl -n "${K8S_NS}" rollout restart "deployment/${SERVICE}" 2>/dev/null \
        || kubectl -n "${K8S_NS}" rollout restart "statefulset/${SERVICE}"
fi

# ===== 等滚动完成（失败挡板：pod 起不来 → 退出码非 0 → 构建 FAILURE，不会假绿） =====
# Deployment / StatefulSet 都支持：先探测哪种 kind 存在，再 watch 对应的 rollout
if kubectl -n "${K8S_NS}" get deployment "${SERVICE}" >/dev/null 2>&1; then
    kubectl -n "${K8S_NS}" rollout status "deployment/${SERVICE}" --timeout=300s
elif kubectl -n "${K8S_NS}" get statefulset "${SERVICE}" >/dev/null 2>&1; then
    kubectl -n "${K8S_NS}" rollout status "statefulset/${SERVICE}" --timeout=300s
else
    echo "FAIL: ${K8S_NS} 下既没有 deployment 也没有 statefulset ${SERVICE}" >&2
    exit 1
fi
'''
    )
}
