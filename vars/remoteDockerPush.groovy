// vars/remoteDockerPush.groovy
// ===== 推送镜像：ECR 登录（防 token 过期）→ 幂等建仓 → docker push =====
//
// 默认 SSH 到跳板机执行（remote 模式）；local: true 时改在 Jenkins agent 本地推送
// （直连模式：没有跳板机，构建与推送都在 agent 完成，目标机再 pull）。
//
// 用法：remoteDockerPush(config: cfg, remoteDir: remoteDir [, local: true])
// 依赖 env.IMAGE_FULL；aws_region / image_registry 取自服务配置（有默认值）
def call(Map args = [:]) {
    def cfg       = args.config    ?: error('remoteDockerPush: 缺少参数 config')
    def remoteDir = args.remoteDir ?: error('remoteDockerPush: 缺少参数 remoteDir')
    def local     = args.local     ?: false

    def envs = [
        IMAGE_FULL     : env.IMAGE_FULL,
        AWS_REGION     : (cfg.aws_region    ?: 'ap-northeast-1'),
        IMAGE_REGISTRY : cfg.image_registry
    ]
    def script = '''set -eu
# 从完整镜像地址提取仓库名：去掉 registry 前缀和 :tag 后缀
# 例：120685...amazonaws.com/demo/prod/api-04:20260924125140 → demo/prod/api-04
REPO="${IMAGE_FULL#*/}"
REPO="${REPO%:*}"

echo "===== ECR 登录（push 之前重新登录，避免 token 过期） ====="
aws ecr get-login-password --region "${AWS_REGION}" | docker login --username AWS --password-stdin "${IMAGE_REGISTRY}"

echo "===== 确保仓库存在（幂等）：${REPO} ====="
if aws ecr describe-repositories --region "${AWS_REGION}" --repository-names "${REPO}" > /dev/null 2>&1; then
    echo "仓库已存在：${REPO}"
else
    echo "仓库不存在，自动创建：${REPO}"
    aws ecr create-repository --region "${AWS_REGION}" --repository-name "${REPO}" --image-tag-mutability IMMUTABLE
fi

echo "===== docker push ${IMAGE_FULL} ====="
docker push "${IMAGE_FULL}"
'''

    if (local) {
        withEnv(envs.collect { k, v -> "${k}=${v}" }) {
            sh script
        }
    } else {
        remoteSh(config: cfg, envs: envs, script: script)
    }
}
