"""Add reviewed-draft ITTO sections to legacy cards that predate the template."""

from pathlib import Path

ITTO = {
    "创建 WBS.md": ("项目范围说明书、需求文件、范围管理计划、项目管理计划和组织过程资产。", "专家判断、分解、滚动式规划和 WBS 结构化工具。", "范围基准、WBS、WBS 字典、变更请求和项目文件更新。"),
    "范围基准与 WBS 字典.md": ("项目范围说明书、WBS、项目管理计划、批准的变更和历史信息。", "分解、专家判断、产品分析和结构化模板。", "范围基准、WBS 字典、范围基准更新和项目文件更新。"),
    "项目范围管理.md": ("项目章程、项目管理计划、需求文件、组织过程资产和干系人信息。", "专家判断、数据分析、产品分析、分解和决策技术。", "范围管理计划、需求文件、范围说明书、WBS、范围基准、核实的可交付成果和变更请求。"),
    "项目进度管理.md": ("项目管理计划、活动清单、活动属性、里程碑清单、项目日历和资源需求。", "网络分析、关键路径法、资源优化、提前量与滞后量、进度压缩和数据分析。", "进度模型、项目进度计划、进度基准、工作绩效信息、变更请求和计划更新。"),
    "关键路径与进度压缩.md": ("活动清单、活动属性、逻辑关系、持续时间估算、资源和进度制约因素。", "网络图、关键路径法、总浮动时间分析、资源优化、赶工和快速跟进。", "进度模型、关键路径、总浮动时间、进度预测和进度计划更新。"),
    "项目风险管理.md": ("风险管理计划、项目管理计划、项目文件、干系人信息和组织过程资产。", "风险登记册、风险报告、专家判断、风险分析、概率影响矩阵和数据分析。", "风险登记册、风险报告、风险应对计划、变更请求、工作绩效信息和项目文件更新。"),
    "风险应对策略.md": ("风险登记册、风险报告、风险管理计划、风险责任人和已批准的应对计划。", "威胁与机会应对策略、专家判断、成本效益分析、决策技术和应急分析。", "风险应对计划更新、变更请求、风险登记册更新、残余风险和次生风险记录。"),
}

root = Path("knowledge-base/learning")
for name, (inputs, tools, outputs) in ITTO.items():
    path = root / name
    text = path.read_text(encoding="utf-8")
    if "## 输入、工具与技术、输出" in text:
        continue
    marker = "## 关联知识点"
    if marker not in text:
        raise SystemExit(f"Missing insertion marker: {path}")
    section = ("## 输入、工具与技术、输出\n\n"
               f"- **输入**：{inputs}\n"
               f"- **工具与技术**：{tools}\n"
               f"- **输出**：{outputs}\n\n")
    path.write_text(text.replace(marker, section + marker, 1), encoding="utf-8")
    print(f"updated {path}")
