# 治理诊断证据与显式复跑

这些文件保存 2026-10-26 在提交 `e6eeb3bca61e2c35609e9cc5fe76e68dfe0d89cb` 上执行的离线诊断，不是默认 CI 测试。结论、完整命令及未执行范围见[验收报告](../../2026-10-26-云端治理验收诊断.md)。后续文档提交不代表重新运行或修复了业务代码。

| 文件 | 用途 | 原轮次结果 |
| --- | --- | --- |
| [results.json](results.json) | 当次 Surefire/JUnit XML 的用例结果摘要，不含环境属性或原始日志 | 按检查组分别记录，不能合并成全量通过 |
| [GovernanceKindsProbeTest.java](GovernanceKindsProbeTest.java) | 复用现有 H2/Mock，验证四类业务知识生命周期 | 显式选择新增参数方法：4 通过 |
| [GovernanceAdaptationProbeTest.java](GovernanceAdaptationProbeTest.java) | 表级 FQN、准入与完成 CAS 反例，以及字段派生正向对照 | 2 通过、5 断言失败，退出码 1 |
| [adaptation_probes.py](adaptation_probes.py) | 文档/切片/通用 RAG/planner 输入与表级准入、关系补键反例 | 10 通过、4 断言失败，退出码 1 |

反例保留对正确行为的断言，因此在上述基线上**故意以失败暴露已知缺陷**。不要将它们改成无条件通过、将失败误报为修复完成，或直接接入默认 CI。业务修复后再单独复跑、核对结果并决定是否转成正式回归测试。

## 不影响默认测试收集

- Python 文件采用 `adaptation_probes.py`，不匹配默认 `test_*.py` / `*_test.py`；只有显式传文件路径才运行。原诊断执行时名为 `test_adaptation_probes.py`，提交准备仅改名，字节内容相同。`results.json` 的原始身份与报告历史命令保持不变。
- Java 文件位于 `docs/acceptance/evidence/`，不属于 `backend/DataOcean/src/test/java`；未改 pom、测试配置或 CI。复跑时只复制到仓库外的临时源码副本。
- Python 假令牌是隔离测试值，与仓库现有 conftest 的测试值一致，不是可用生产凭据；网络连接在新增测试中禁止，模型、向量库和缓存均模拟。H2/Flyway-disabled 不证明真实 MySQL 迁移。

提交前已做收集检查：从仓库根对本证据目录执行 `pytest --collect-only -q -p no:cacheprovider` 返回 `no tests collected`（退出码 5，符合预期）；按下方新文件路径显式 `--collect-only` 则收集 14 项、退出码 0。这只验证收集边界，未重新执行用例或改写历史成绩。

## 显式复跑

使用项目要求的 Python 3.13 依赖环境、JDK 17 与 Maven。`REPO` 指仓库根目录，`DIAG` 指工具创建的临时目录，`PYTHON` 指准备好的 Python 3.13 解释器，`MVN` 指 Maven 可执行文件。工具、依赖缓存和临时源码副本留在仓库外；不要使用真实数据库或外部模型配置。

Python 新名称的等价命令：

```bash
cd "$REPO/python-service"
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$REPO/python-service:$REPO/python-service/tests" \
  "$PYTHON" -m pytest -q -p no:cacheprovider \
  "$REPO/docs/acceptance/evidence/2026-10-26-governance/adaptation_probes.py"
```

Java 临时副本准备与 17 套件命令见验收报告。将两份 Java 文件复制到报告指明的临时 package 后，分别选择：

```bash
"$MVN" -o -B -ntp -f "$DIAG/java-copy/backend/DataOcean/pom.xml" \
  -Dmaven.repo.local="$DIAG/m2" -Dtest=GovernanceAdaptationProbeTest test
"$MVN" -o -B -ntp -f "$DIAG/java-copy/backend/DataOcean/pom.xml" \
  -Dmaven.repo.local="$DIAG/m2" \
  '-Dtest=GovernanceKindsProbeTest#reviewedKindsRetainExactDependenciesAndInvalidateOnSourceChangeAndRevocation' test
```

`-o` 要求先按报告在临时缓存准备依赖。第二条使用方法选择，只运行新增 4 个参数用例，避免把父类 21 项重复累计为新覆盖。原问数基线的 1 失败、6 错误仍是独立已知问题，本材料提交没有修复它们。
