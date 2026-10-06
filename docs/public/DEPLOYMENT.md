# Docker 部署与运行说明

## 服务与持久化

默认启动 `web`、`backend`、`mysql`、`postgres`、`redis` 五个服务，仅 Web 绑定本机 `127.0.0.1:8080`。数据库和后端没有公开主机端口。`parser` profile 额外启动增强 PDF 解析服务。

项目名默认为 `aiassistant-stack`，避免与已有本机开发数据库容器混用。部署多个实例时使用不同的 `-p` 项目名和 Web 端口，并为每个实例单独生成环境文件。

| 数据卷 | 内容 |
| --- | --- |
| `mysql-data` | 用户、会话、记忆、知识库元数据、全文索引及配置 |
| `postgres-data` | 1024 维向量索引 |
| `redis-data` | 令牌撤销与刷新状态 |
| `runtime-data` | 原始上传资料、文档作业、聊天与工具运行记录、统计与导出文件 |
| `uploads-data` | 对话上传图片与历史上传路径 |
| `parser-models` / `parser-runtime` | 可选解析模型及临时缓存 |

运行数据只保存在部署环境，不会提交 Git。所有数据卷与 `.env.docker` 都需要保留；丢失 `ENCRYPTION_KEY` 可能导致数据库中保存的模型密钥无法解密。

## 初始化

```bash
python deployment/init-env.py
docker compose --env-file .env.docker up --build -d
docker compose --env-file .env.docker ps
```

生成器为数据库、Redis、JWT、加密、管理员及解析服务分别生成随机值，不覆盖已有文件。管理员只在新数据库中初始化一次；修改环境变量不会自动重置已有管理员密码。不要把旧数据库直接接到首次初始化的 Docker 部署上。

MySQL 使用 Flyway 执行源码迁移；PostgreSQL 只在新数据卷首次启动时执行 `V1__init_pgvector.sql`。历史 PostgreSQL V3 脚本包含删除重建向量表的操作，**不要对现有数据手动执行**。当前模板与 MySQL 后续迁移都使用 BGE-M3 的 1024 维设置。

## 模型与联网

管理员可在页面中配置模型。默认对话模板是 OpenAI 兼容接口的 DeepSeek，嵌入模板是 SiliconFlow BGE-M3；这些是配置模板，不附带可用密钥。不同供应商的图像输入、思考档位与参数支持可能不同。

对话配置更新会清理模型缓存。嵌入客户端在当前进程内保持模型身份，以便安全复用检查点；修改嵌入配置后执行：

```bash
docker compose --env-file .env.docker restart backend
```

更换嵌入模型、向量维度或分块配置时，应备份后重新建立索引，不能直接复用旧向量。联网搜索默认禁用；需要时设置 `.env.docker` 的 `EXA_ENABLED=true` 后重新创建后端。Docker 默认不自动发现宿主机 MCP 工具。

## 增强解析

```bash
docker compose --env-file .env.docker --profile parser up --build -d
```

首次启动会下载模型，`.ready` 标志在下载成功后写入。解析接口仅在容器网络中开放，并校验与后端共享的随机令牌。`/health` 表示服务可响应，不代替真实文档解析验收。默认使用 CPU；大扫描件可能需要较长时间与更多内存。

## 资料目录

默认只能使用上传资料。需要绑定目录时，在 `.env.docker` 中设置 `LOCAL_DOCUMENTS_DIR`，再显式加载 `deployment/compose.local-folders.yaml`。Windows 可选择任意盘符中的资料目录，Linux / macOS 使用对应的绝对路径：

```dotenv
# Windows 示例（不要绑定整个磁盘）
LOCAL_DOCUMENTS_DIR=D:/documents
# Linux 示例：LOCAL_DOCUMENTS_DIR=/srv/documents
# macOS 示例：LOCAL_DOCUMENTS_DIR=/Users/yourname/Documents/library
```

未设置或设为空时沿用 `E:/资源`。容器路径统一为 `/documents`，后端允许访问的根目录也限定为 `/documents`；更换盘符不需要修改 Java 代码。挂载为只读，路径不存在会报错，不会自动创建。Windows 推荐使用正斜杠，路径含空格时可按 dotenv 语法加引号。

文件夹导入会读取所选目录内的资料，向量化可能调用所配置的外部模型。更换目录前应停止旧目录的导入任务；已导入的资料副本与索引不会随挂载路径变化自动删除。默认示例源于开发环境，部署者应显式选择自己愿意交给应用处理的资料目录。

## 停止、更新与备份

```bash
# 停止容器，保留数据卷
docker compose --env-file .env.docker down
# 更新源码后重新构建
docker compose --env-file .env.docker up --build -d
```

不要使用 `down -v`，它会删除数据卷。升级前备份 MySQL、PostgreSQL、运行数据卷及私密环境文件；数据库推荐使用逻辑备份，并安排暂停写入以保持资料与索引一致。备份文件不要放进仓库。检查点中可能含原始消息与工具结果，应按用户资料保护。

## 公网部署

如需外部访问，自行配置域名、HTTPS、反向代理与访问控制，再修改 `WEB_BIND`。CORS 应限制为实际使用的域名。当前模板不包含高可用、多副本作业锁、自动备份或生产级监控，不应直接当作完成这些能力的证明。

## 常见问题

- **前端未启动：** Web 等待后端健康，后端等待数据库健康；检查 Compose 状态及脱敏日志。
- **无密钥无法回答：** 先配置模型；启动和进入后台不要求模型 API 密钥。
- **新管理员登录失败：** 使用本次生成文件中的管理员密码。已有数据卷不会再次重置密码。
- **导入向量失败：** 核对嵌入接口、密钥、模型名称与返回向量维度；当前数据库为 1024 维。
- **扫描件无法识别：** 基础部署没有增强解析服务；开启 parser profile 并等待模型下载完成。
- **大文件被拒绝：** 单文件上限为 100 MiB，Nginx 为 multipart 额外预留请求头空间；仍需考虑实际内存容量。
- **Windows Docker 连接失败：** 先启动 Docker Desktop 并启用 Linux 容器。Docker 镜像、数据卷及缓存所在磁盘由 Docker Desktop 设置决定，可选择 D 盘。
