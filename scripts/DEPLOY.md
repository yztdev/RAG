# PaiSmart 发布

入口：`bash scripts/deploy-release.sh <action>`。默认 `DRY_RUN=1`，仅输出计划；不连接服务器，不写文件。

- 本地构建：先完成测试、`mvn package -DskipTests` 和前端 `pnpm build`。发布器不编译、不提交、不推送。
- 显式设置唯一 `RELEASE_ID`；同一次发布的所有步骤使用相同 ID。
- 服务器连接：从环境变量或仓库根目录 `.env`（已被 git 忽略）读取 `DEPLOY_SERVER_HOST`、`DEPLOY_SERVER_USER`（默认 `root`）、`DEPLOY_SERVER_KEY`，与 `deploy-front.sh` 共用。服务器地址和私钥路径禁止写进仓库。
- `DRY_RUN=0 ... preflight`：只读检查线上 PID、现有登录/API、首页资源、空间、工具。
- `DRY_RUN=0 ... prepare`：冻结并上传构建产物、校验 SHA256、备份 MySQL/JAR/dist、提前解压新前端。当前服务保持运行。
- `DRY_RUN=0 ... publish`：发布已 prepare 的版本。短停旧 Java 进程、替换产物、用原 JVM 命令和线上 `.env` 启动；验证本机/公网登录和只读 API、前端入口资源。
- `DRY_RUN=0 ... all`：为新的发布 ID 连续执行 prepare 和 publish。
- `DRY_RUN=0 ... status` / `verify`：查看状态 / 重验当前发布。
- `DRY_RUN=0 ... rollback`：恢复该发布切换前的 JAR 和前端，启动并验收。数据库不会自动恢复。

示例（必须先检查目标构建）：

```bash
export RELEASE_ID=1e36fa56-20260929-release1
DRY_RUN=1 bash scripts/deploy-release.sh all
DRY_RUN=0 bash scripts/deploy-release.sh prepare
DRY_RUN=0 bash scripts/deploy-release.sh publish
```

远程每次发布记录保存在 `/home/www/PaiSmart/.releases/<RELEASE_ID>/`（仅当前服务器用户可读）：

- `manifest.json`：提交与构建产物哈希。产物必须由该提交构建，发布器不把 Git HEAD 当作构建来源的独立证明。
- `backup/database.sql.gz`：一致性导出；检查 mysqldump 退出码、完整 gzip 和 SQL 完成标记。不等同于做过恢复演练。
- `backup/SmartPAI-0.0.1-SNAPSHOT.jar` / `backup/dist.tar.gz`：旧产物备份。
- `before.json`：旧产物哈希、原 JVM 命令和线上 .env 哈希（不保存配置明文）。
- `state.json` / `deployment.log` / `startup.log`：发布状态和受限日志。

脚本不删除历史备份、不覆盖线上 .env、不重建 ES 索引、不停止数据库等基础服务。前端旧目录保留为 `dist.prev.<RELEASE_ID>`，新 `dist` 指向本次静态资源目录。第一次从普通目录切换为符号链接涉及两次文件系统操作，存在极短窗口，不宣称零停机。

切换和验收由独立远程进程负责，本地终端关闭后仍继续执行；出现切换/验收异常时自动回滚。断电、系统 OOM 或 worker 被强杀等无法被捕获的故障，需用同一 RELEASE_ID 检查状态并手动回滚。数据库升级应只含兼容旧代码的增量修改；本次为新增表/字段，仍需保留数据库备份。

验证脚本：`python3 -m unittest discover -s scripts/tests -v`。
