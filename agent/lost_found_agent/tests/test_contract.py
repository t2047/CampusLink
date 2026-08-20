"""失物招领 Agent 对外契约（schemas/lost-found-agent.json）的 schema 一致性测试。

覆盖功能点：
- Agent schema 本身合法，且关键字段符合契约（写操作需确认、6 个动作、topK=5、
  权重和为 1、单次最多 2 个工具、LLM 故障兜底为规则模式）；
- searchInput / invokeInput 接受"暂存图片"载荷（object_key + 视觉指纹 + URL）；
- invokeOutput 的样例响应（needs_more_info / needs_confirmation）都通过校验。

测试策略：
- 纯契约校验：用 jsonschema 的 Draft202012Validator 校验样例 JSON；
- schema 文件路径基于 __file__ 相对定位，不依赖运行时 cwd。
"""

import json
from pathlib import Path

from jsonschema import Draft202012Validator


def test_agent_schema_is_valid_and_requires_confirmation() -> None:
    """schema 本身必须合法（check_schema），且写确认/动作/权重等关键字段符合契约。"""
    # 定位到 schemas/lost-found-agent.json（本测试目录再上两级）
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    # 先用官方校验器检查 schema 自身结构是否合法（不校验数据）
    Draft202012Validator.check_schema(schema)
    # 契约要求的 6 个动作列表，多处引用对照
    expected_actions = [
        "report_lost",
        "report_found",
        "search_found_items",
        "search_lost_items",
        "get_item_detail",
        "claim_item",
    ]

    assert schema["version"] == "1.8.0"
    # 写操作（创建报告/认领）必须强制二次确认
    assert schema["security"]["writeConfirmationRequired"] == [
        "report_lost",
        "report_found",
        "claim_item",
    ]
    assert schema["capabilities"]["actions"] == expected_actions
    # 内部工具、模型可调用工具列表都与动作列表严格一致
    assert [tool["name"] for tool in schema["internalTools"]] == expected_actions
    assert schema["model"]["allowedTools"] == expected_actions
    # 隐私：不对外暴露拾获者联系方式
    assert schema["capabilities"]["privacy"]["exposesPublisherContact"] is False
    assert schema["matching"]["topK"] == 5
    # 匹配权重归一化（和为 1），保证加权平均结果有界
    assert sum(schema["matching"]["weights"].values()) == 1
    # 单次调用最多执行 2 个工具
    assert schema["model"]["maximumToolsPerInvocation"] == 2
    # LLM 故障时的兜底模式是规则引擎
    assert schema["model"]["fallbackMode"] == "rules"


def test_search_input_accepts_image_payload() -> None:
    """searchInput 必须接受带暂存图片的搜索请求（以图搜物场景）。"""
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    root = json.loads(schema_path.read_text(encoding="utf-8"))
    # 构造只引用 searchInput 子 schema 的校验器（root 提供 $defs 定义表）
    validator = Draft202012Validator({"$ref": "#/$defs/searchInput", "$defs": root["$defs"]})
    # 契约明确：search.input / search.output 必须引用对应的子定义
    assert root["search"]["input"] == {"$ref": "#/$defs/searchInput"}
    assert root["search"]["output"] == {"$ref": "#/$defs/searchOutput"}
    # 样例：带一张暂存图（MinIO object_key + 视觉指纹 + 访问 URL）
    sample: dict[str, object] = {
        "report_type": "FOUND",
        "keyword": "耳机",
        "date_from": "2026-08-01",
        "date_to": "2026-08-11",
        "images": [
            {
                "object_key": "lost-found-staging/abc.png",
                "visual_fingerprint": "VF1:xxx",
                "url": "/api/lost-found/images/staging/abc.png",
            }
        ],
    }

    validator.validate(sample)  # 校验通过即证明 schema 与样例一致


def test_invoke_input_accepts_staged_images() -> None:
    """invokeInput 同样接受带暂存图片的会话启动请求（携带图片找失物）。"""
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    root = json.loads(schema_path.read_text(encoding="utf-8"))
    # 构造只引用 invokeInput 子 schema 的校验器
    validator = Draft202012Validator({"$ref": "#/$defs/invokeInput", "$defs": root["$defs"]})
    # 样例：带一张暂存图，图片结构与 searchInput 保持一致
    sample: dict[str, object] = {
        "message": "帮我找这把蓝色雨伞",
        "conversation_context": {"session_id": "s1", "shared_data": {}},
        "images": [
            {
                "object_key": "lost-found-staging/abc.png",
                "visual_fingerprint": "VF1:xxx",
                "url": "/api/lost-found/images/staging/abc.png",
            }
        ],
    }

    validator.validate(sample)


def test_sample_response_matches_contract() -> None:
    """needs_more_info 样例响应必须通过 invokeOutput 校验。"""
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    root = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft202012Validator({"$ref": "#/$defs/invokeOutput", "$defs": root["$defs"]})
    # 样例：信息不足需要追问的状态，match_results / actions_taken 为空数组
    sample: dict[str, object] = {
        "response": "需要补充物品描述。",
        "status": "needs_more_info",
        "match_results": [],
        "confirmation_required": None,
        "shared_context": {},
        "actions_taken": [],
        "request_id": "request-1",
    }

    validator.validate(sample)


def test_report_found_confirmation_matches_contract() -> None:
    """needs_confirmation（拾获登记）样例响应必须通过 invokeOutput 校验。"""
    schema_path = Path(__file__).parents[2] / "schemas" / "lost-found-agent.json"
    root = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft202012Validator({"$ref": "#/$defs/invokeOutput", "$defs": root["$defs"]})
    # 样例：包含 confirmation_required 完整字段（id/action/summary/过期时间）
    sample: dict[str, object] = {
        "response": "请确认登记这件拾获物品。",
        "status": "needs_confirmation",
        "match_results": [],
        "confirmation_required": {
            "confirmation_id": "confirmation-1",
            "action": "report_found",
            "summary": "黑色耳机，中央图书馆，2026-08-08",
            "expires_at": "2026-08-11T10:10:00Z",
        },
        "shared_context": {},
        "actions_taken": [],
        "request_id": "request-report-found",
    }

    validator.validate(sample)
