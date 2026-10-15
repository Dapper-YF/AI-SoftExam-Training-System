# AI高项培训系统后端

## 模块

- `common-infrastructure`: 统一响应、异常、安全、traceId、通用基础设施。
- `core-foundation`: 用户/角色/资格/科目/知识点等基础内核样例。
- `question-practice`: 刷题备考系统边界样例。
- `essay-training`: AI论文系统边界样例。
- `ai-capability`: AI能力层任务状态样例。
- `platform-application`: Spring Boot 启动模块与 Flyway 迁移脚本。

## 依赖安装

```powershell
cd .
mvn test
```

本机离线开发时已将可复用 Maven 缓存复制到 `backend\.m2\repository`，该目录只作本地缓存，不进入版本库。

## 数据库配置

MySQL 8.0 建库：

```sql
CREATE DATABASE ai_exam_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

环境变量：

```powershell
$env:MYSQL_URL="jdbc:mysql://127.0.0.1:33307/ai_exam_platform?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="<在当前 PowerShell 会话临时输入，不要写入文件>"
$env:REDIS_HOST="127.0.0.1"
$env:REDIS_PORT="6379"
```

也可以用交互方式设置密码，避免命令历史里出现明文：

```powershell
$secure = Read-Host "MYSQL_PASSWORD" -AsSecureString
$env:MYSQL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
  [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
)
```

## 迁移执行

```powershell
cd .
mvn test

[xml]$xml = Get-Content -LiteralPath "platform-application\target\surefire-reports\TEST-com.aiexam.app.ApiEnvelopeIntegrationTest.xml" -Encoding UTF8
$cp = ($xml.testsuite.properties.property | Where-Object { $_.name -eq "java.class.path" }).value
$mainCp = (($cp -split ';' | Where-Object {
  $_ -and ($_ -notlike '*platform-application\target\test-classes*')
}) -join ';')
java "-Dspring.profiles.active=mysql" -cp $mainCp com.aiexam.app.AiExamPlatformApplication
```

Flyway 脚本位于 `platform-application/src/main/resources/db/migration`：

- `V1__foundation.sql`
- `V2__question_practice.sql`
- `V3__essay_training.sql`
- `V4__ai_capability.sql`
- `V5__seed_data.sql`

## MySQL/Flyway Gate 验收

本 Gate 不接受 H2。必须使用 `mysql` profile 和主应用 classpath，并确认日志中出现 MySQL 数据库连接与 Flyway 迁移/校验信息。

> 注意：`src/test/resources/application-mysql.yml` 会关闭 Flyway，而且集成测试会手工建表和清理数据。测试配置只读取 `MYSQL_TEST_*`，默认连接 `ai_exam_platform_test`；启动后还会校验 `DATABASE()` 必须以 `_test` 结尾，否则在任何兼容 DDL 执行前立即失败。mysql profile 测试结果不能替代 Flyway Gate。

mysql profile 集成测试必须使用独立测试库：

```sql
CREATE DATABASE ai_exam_platform_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

```powershell
$env:MYSQL_TEST_URL="jdbc:mysql://127.0.0.1:33307/<随机测试库名>_test?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
$env:MYSQL_TEST_USERNAME="root"
$env:MYSQL_TEST_PASSWORD="<在当前 PowerShell 会话临时输入，不要写入文件>"

mvn "-Dspring.profiles.active=mysql" test
```

```powershell
cd .

$env:MYSQL_URL="jdbc:mysql://127.0.0.1:33307/ai_exam_platform?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="<在当前 PowerShell 会话临时输入，不要写入文件>"
```

先运行默认全量测试，再按“迁移执行”章节使用排除 `target/test-classes` 的主 classpath 启动应用：

```powershell
mvn test
```

验收时至少记录：

- `127.0.0.1:33307` 连接结果。
- 当前数据库名必须是 `ai_exam_platform`。
- `flyway_schema_history` 是否存在。
- V1 到 V5 是否均 `success=true`。
- 启动日志必须显示 active profile 为 `mysql`，并显示 MySQL/Flyway 连接信息。
- `GET /api/v1/health` 响应。
- 无 token 已登录接口返回 `401`。
- `Authorization: Bearer dev-student-token` 下至少一个学生端业务接口返回正常业务响应。

## 固定开发测试账号

仅在 `dev` 或 `test` profile 下显式启用：

```text
--aiexam.auth.dev-accounts.enabled=true
```

统一账号：

- 学员：`student` / `123456`，角色 `STUDENT`
- 管理员：`admin` / `123456`，角色 `ADMIN`、`STUDENT`、`QUESTION_AUTHOR`，权限 `question:read/create/update/submit-review/import`
- 审核发布人：`question_reviewer` / `123456`，角色 `QUESTION_REVIEWER`、`QUESTION_PUBLISHER`，权限 `question:read/review/publish`

初始化使用 Argon2id 哈希落库，重复启动保持幂等。默认、`mysql` only、`prod`
或 `production` profile 开启该配置时应用会拒绝启动。题库管理接口只按细粒度
权限码放行，不使用 `ROLE_ADMIN` 绕过审核/发布边界。账号密码不得嵌入前端
production bundle，登录页也不展示密码。

MySQL 集成测试必须创建随机名称且以 `_test` 结尾的数据库，并在测试结束后
自动删除。禁止把固定 `ai_exam_platform_test` 长期保留在 Navicat。

## 启动命令

无本地 MySQL 时可先启动骨架接口，默认不执行 Flyway：

```powershell
cd .
mvn "-Dmaven.repo.local=.\.m2\repository" test

[xml]$xml = Get-Content -LiteralPath "platform-application\target\surefire-reports\TEST-com.aiexam.app.ApiEnvelopeIntegrationTest.xml" -Encoding UTF8
$cp = ($xml.testsuite.properties.property | Where-Object { $_.name -eq "java.class.path" }).value
java -cp $cp com.aiexam.app.AiExamPlatformApplication
```

## 健康检查

```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8000/api/v1/health
```

开发用 Bearer Token：

- 管理员：`Authorization: Bearer dev-admin-token`
- 考生：`Authorization: Bearer dev-student-token`

## 样例接口

- `GET /api/v1/health`
- `GET /api/v1/foundation/qualifications`
- `GET /api/v1/foundation/subjects/{id}/syllabus-nodes`
- `GET /api/v1/questions/10001`
- `GET /api/v1/ai/tasks/8001`
