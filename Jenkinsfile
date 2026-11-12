// 入口只有 3 行，全部流程封装在共享库 vars/ 下（改流程不需要动 Jenkinsfile）：
//   paramBuild      参数聚合（job 名解析 + 配置校验 + 派生字段）
//   pipelineInlet   流水线定义（参数 / stages，host 与 k8s 的差异在 stage 里分流）
//   hostDeploy      host 类型部署编排（MODE 分发：全部 / Grayscale / custom）
//   k8sDeploy       k8s 类型部署编排（docker build → push ECR → kubectl apply）
@Library('Shared-Library@main') _
def paramMap = pipelineInlet env.JOB_NAME
