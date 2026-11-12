// vars/pipelineInlet.groovy
// ===== 流水线入口：Jenkinsfile 只有一行 pipelineInlet env.JOB_NAME =====
//
// 流程：job 名拆五段 → 读配置（全局 + 项目，项目优先）→ paramBuild 聚合 → 构建参数 → pipeline stages
// job 名格式：<项目>_<服务>_<语言>_<部署>_<环境>（如 demo_admin_nodejs_host_prod）
def call(String jobName) {
    // ===== 1. job 名拆五段 =====
    def parts = jobName.split('_')
    if (parts.size() != 5) {
        error("Job 名格式错误，应为 <项目>_<服务名>_<语言>_<部署>_<环境>，实际: ${jobName}")
    }
    def (project, service, language, deploy, envName) = parts

    // ===== 2. 读配置：全局 config/config.yaml + 项目 yaml（项目优先，同名 key 覆盖）=====
    // 项目 yaml 按路径 <env>/<项目>/<服务>/<job>.yaml 查找（和服务清单同目录），找不到直接报错
    def cfg = readYaml(text: libraryResource("config/config.yaml"))
    cfg.putAll(readYaml(text: libraryResource("${envName}/${project}/${service}/${jobName}.yaml")))

    // ===== 3. 参数聚合 + env 暴露（编译 / 分发 / 部署步骤依赖这些环境变量）=====
    def paramMap = paramBuild(jobName, cfg)
    env.PROJECT   = project
    env.SERVICE   = service
    env.LANGUAGE  = paramMap.LANGUAGE
    env.DEPLOY    = deploy
    env.ENV_NAME  = envName
    env.IMAGE_TAG = paramMap.IMAGE_TAG
    if (deploy != 'host') {
        env.IMAGE_FULL = paramMap.IMAGE_FULL
        env.K8S_NS     = paramMap.K8S_NS
    }

    // reactive 脚本跑在沙箱里不能调 Jenkins 步骤，主机选项提前拼成字面量烤进去
    // k8s 类型不配 servicesIP → 恒为空列表，不报错
    def ipOptions = paramMap.servicesIPList.collect { "'${it}'" }.join(', ')

    pipeline {
        agent any
        options {
            timestamps()
            disableConcurrentBuilds()
        }
        parameters {
            // 自动获取分支 / 标签（Git Parameter 插件，列表来自服务仓库）
            // PT_BRANCH_TAG：分支和标签都能选（要回滚到某个 tag 发版时直接选 tag）
            // quickFilterEnabled：列表长时可搜索（tag/branches 多的仓库必备）
            // sortMode DESCENDING_SMART + selectedValue TOP：最新排在前面且默认选中
            gitParameter(
                name              : 'BRANCH',
                type              : 'PT_BRANCH_TAG',
                branchFilter      : 'origin/(.*)',
                tagFilter         : '*',
                defaultValue      : (paramMap.default_branch ?: 'main'),
                description       : '选择将要构建的分支或标签（源码默认分支由服务 yaml 的 default_branch 指定，未配则 main）',
                quickFilterEnabled: true,
                selectedValue     : 'TOP',
                sortMode          : 'DESCENDING_SMART',
                useRepository     : "${paramMap.gitlab_url}"
            )
            // 发布模式（deploy 必须打头：choice 的默认值取第一项，避免误选）
            //   deploy  正常发布：拉代码→编译→构建镜像→推 ECR→apply/up
            //   config  只改配置：跳过拉代码/编译/构建/推送，沿用当前镜像，只 apply 清单 + 重启
            //           （改 configmap / compose / unit 用这个，不重新打包镜像）
            //   restart 直接重启：跳过拉代码/编译/分发，不碰配置
            //           k8s 滚动重启 Pod / host systemctl restart / docker restart 容器
            //   test    演练：拉代码/编译/构建镜像/分发产物全流程跑一遍，唯独不执行发布（kubectl apply / compose up / systemctl restart 都不跑）
            //   rollback 占位未实现（需要回滚时 revert 配置提交后重新 deploy）
            //   Grayscale / custom 仅 host 有意义
            choice(
                name: 'MODE',
                choices: deploy == 'k8s'
                    ? ['deploy', 'config', 'rollback', 'restart', 'test']
                    : ['deploy', 'config', 'rollback', 'Grayscale', 'custom', 'test', 'restart'],
                description: deploy == 'k8s'
                    ? 'k8s：deploy 发版；config 只改配置(不重构建)；restart 滚动重启；test 演练不发布'
                    : 'host/docker：deploy / config(只改配置) / Grayscale / custom / restart；test 演练不发布'
            )
            // MODE 变化时联动刷新：custom 才渲染 servicesIP 主机选项
            reactiveChoice(
                choiceType         : 'PT_MULTI_SELECT',
                name               : 'TARGET_HOSTS',
                description        : '手动选择目标主机（MODE = custom 时可选，不选 = 全部）',
                referencedParameters: 'MODE',
                filterLength       : 1,
                filterable         : false,
                script             : groovyScript(
                    fallbackScript: [classpath: [], oldScript: '', sandbox: true, script: 'return []'],
                    script        : [classpath: [], oldScript: '', sandbox: true, script: "if (MODE == 'custom') { return [${ipOptions}] } else { return [] }"]
                )
            )
        }
        stages {

            stage('拉代码') {
                // 所有 MODE 都拉源码（shallow depth1 + 单 refspec，成本很低）：
                // gitParameter 插件列分支/标签依赖 workspace 里源码仓的检出记录，
                // config/restart 跳过的话，下次构建参数页检索分支失败 → 回退 default_branch
                steps {
                    // 源码检出到 ${WORKSPACE}/src/，避免跟 deploy-configs 子目录冲突
                    // 地址自动选凭据：http:// 走 http_credentials_id，git@ 走 ssh_credentials_id
                    gitCheckout(
                        url             : paramMap.gitlab_url,
                        branch          : (params.BRANCH?.trim() ?: paramMap.default_branch ?: 'main'),
                        dir             : 'src',
                        httpCredentialsId: paramMap.http_credentials_id,
                        sshCredentialsId : paramMap.ssh_credentials_id
                    )
                }
            }

            // host 类型不需要 Dockerfile / k8s.yaml，直接跳过；restart 直接重启，也不需要配置
            // host 类型不需要 Dockerfile / k8s.yaml，直接跳过；restart 直接重启，也不需要部署清单
            // config 需要清单（只改配置正是要 apply 新清单）→ 照常拉
            // 拉配置仓库（deploy-configs）：部署清单 + k8s Secret 都在里面，所有部署类型无条件检出取用
            stage('拉部署清单') {
                when { expression { deploy != 'host' && params.MODE != 'restart' } }
                steps {
                    gitCheckout(
                        url              : paramMap.deploy_secrets_url,
                        branch           : 'main',
                        dir              : paramMap.deploy_secrets_dir,
                        httpCredentialsId: paramMap.http_credentials_id,
                        sshCredentialsId : paramMap.ssh_credentials_id
                    )
                }
            }

            stage('编译') {
                // rollback 不需要编译；restart / config 沿用当前生产镜像，也不编译
                when { expression { params.MODE != 'rollback' && params.MODE != 'restart' && params.MODE != 'config' } }
                steps {
                    // 统一编译：按 LANGUAGE 准备工具链 + 执行 build 命令（可覆盖）+ 产物打包
                    buildStage(paramMap)
                }
            }

            // rollback / restart 不需要分发（restart 直接重启，不碰配置）；直连模式（directDeploy）→ 整个 stage 跳过
            // config 需要分发（要把新清单同步到跳板机才能 apply）；test 会走进来：演练验证分发环节（ssh/rsync/--delete）
            stage('分发到云') {
                when {
                    expression {
                        params.MODE != 'rollback' && params.MODE != 'restart' && !paramMap.directDeploy
                    }
                }
                steps {
                    rsyncArtifacts(config: paramMap, remoteDir: paramMap.remoteDir)
                }
            }

            stage('部署') {
                // rollback 无构建无发布，整段跳过；test 跑构建+分发但不发布（apply 在各自 deploy 函数内按 MODE 跳过）
                // restart 直接重启；config 重放配置（沿用当前镜像，不重新构建）
                when { expression { params.MODE != 'rollback' } }
                steps {
                    script {
                        // host / k8s / docker 的部署编排各自隔离在 vars/ 下，互不影响
                        if (deploy == 'host') { hostDeploy(paramMap) }
                        else if (deploy == 'docker') { dockerDeploy(paramMap) }
                        else { k8sDeploy(paramMap) }
                    }
                }
            }
        }
    }
}
