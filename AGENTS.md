# TuAI 工作约定

此目录包含后端 `tuai` 和前端 `tuai-ui`。

用户已授权：完成本项目的代码修改和验证后，直接使用统一脚本部署前后端，不重复询问是否部署，也不只停在本地构建。若用户当次明确要求不部署，以当次要求为准。

从后端目录运行：

```powershell
python scripts/deploy_all.py
```

从前端目录运行：

```powershell
python ../tuai/scripts/deploy_all.py
```

脚本统一执行前后端构建、测试、镜像发布、Kubernetes 更新及上线验证。发布失败时检查对应步骤日志并修复后继续，未通过上线验证不能宣称部署完成。复用现有私有发布配置，不输出密码、token 或完整 Secret。
