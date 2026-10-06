# 开发与验证

## 环境

Java 17、Maven 3.9、Node.js 22、Python 3.12，以及 MySQL 8、Redis 7.4、PostgreSQL 16 / pgvector。使用 Compose 可以隔离整套服务。

## 测试

```bash
cd backend
mvn test
cd ../user-frontend
npm ci
npm run build
node --test tests/*.cjs
cd ../deployment/pdf-parser
python -m unittest test_text_cleanup
```

后端单元测试与前端纯函数测试不需要模型密钥。Python 清洗测试也不需要下载解析模型。接口级冒烟测试见 `scripts/smoke-deployment.py`，会读取私密环境文件完成本机登录，但不会打印凭据。

开启 parser profile 并等待模型准备完成后，可使用 `python scripts/smoke-parser.py` 验证合成 PDF；只检验单页基本解析，不代表 OCR 或复杂版面质量。

本机开发配置与交接记录不属于公开源码。需要自己的开发配置时，参考 `application.yml` 和 `application-docker.yml` 创建未跟踪的 `application-dev.yml`，不要将任何凭据写到源码中。也可以先用完整 Docker 栈开发，修改后重建对应服务。

## 贡献原则

1. 提供可复现的失败场景，并标注输入资料是否可以公开。
2. 修改路由、引用、统计、记忆或恢复时增加相关回归用例。
3. 性能结论写清样本、运行环境、模型、token、耗时及限制，不用旧评测代替新版本验证。
4. 保留用户数据隔离、原文定位与深浅主题行为。
5. 提交前检查实际 Git 索引；不要扩大允许列表来上传运行数据。
