#!/usr/bin/env python3
"""校验插件语言表。

规则（与 Evernight 的插件约定一致）：
  1. 语言表位于 src/main/resources/assets/<fabric.mod.json 的 id>/lang/。
  2. 每个 <code>.json 是 UTF-8、不带 BOM 的扁平 JSON 对象，值是字符串。
  3. en_us.json 与 zh_cn.json 的键集合完全一致。
  4. 所有键都以 addons.<id>. 开头；evernight. 是本体保留命名空间，插件不得使用。

用法（仓库根目录）：
    python scripts/check-lang.py

退出码：0 = 通过（可能有 WARN），1 = 有 ERROR。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MOD_JSON = ROOT / "src" / "main" / "resources" / "fabric.mod.json"
BASE_LANGUAGE = "en_us"
RESERVED_PREFIX = "evernight."
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+0,(<]*\d*(?:\.\d+)?[sSdfxXoberaAcCeEgGtT]")

ERRORS = []
WARNINGS = []


def report() -> int:
    for warning in WARNINGS:
        print("WARN  " + warning)
    for error in ERRORS:
        print("ERROR " + error)
    if ERRORS:
        print("\n失败：%d 个错误 / %d 个警告" % (len(ERRORS), len(WARNINGS)))
        return 1
    print("\n通过：0 个错误 / %d 个警告" % len(WARNINGS))
    return 0


def fail(message: str) -> None:
    ERRORS.append(message)
    report()
    raise SystemExit(1)


def load_mod_id() -> str:
    if not MOD_JSON.is_file():
        fail("缺少 src/main/resources/fabric.mod.json")
    try:
        data = json.loads(MOD_JSON.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail("fabric.mod.json 不是合法 JSON：%s" % error)
    mod_id = data.get("id") if isinstance(data, dict) else None
    if not isinstance(mod_id, str) or not mod_id:
        fail("fabric.mod.json 缺少字符串字段 id")
    return mod_id


def read_language(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    text = path.read_text(encoding="utf-8")
    if text.startswith("\ufeff"):
        ERRORS.append("%s 带 UTF-8 BOM，Gson 解析会失败" % path.name)
    duplicates = []

    def collect(pairs):
        seen = set()
        for key, _value in pairs:
            if key in seen:
                duplicates.append(key)
            seen.add(key)
        return dict(pairs)

    try:
        data = json.loads(text, object_pairs_hook=collect)
    except json.JSONDecodeError as error:
        ERRORS.append("%s 不是合法 JSON：第 %d 行第 %d 列：%s"
                      % (path.name, error.lineno, error.colno, error.msg))
        return entries
    if not isinstance(data, dict):
        ERRORS.append("%s 顶层必须是 JSON 对象" % path.name)
        return entries
    for key, value in data.items():
        if not isinstance(value, str):
            ERRORS.append("%s 的值必须是字符串：%s" % (path.name, key))
            continue
        if not value.strip():
            ERRORS.append("%s 有空值：%s" % (path.name, key))
        entries[key] = value
    for key in duplicates:
        ERRORS.append("%s 有重复键（Gson 保留最后一个）：%s" % (path.name, key))
    return entries


def main() -> int:
    mod_id = load_mod_id()
    language_dir = ROOT / "src" / "main" / "resources" / "assets" / mod_id / "lang"
    if not language_dir.is_dir():
        fail("语言目录不存在：%s" % language_dir.relative_to(ROOT))

    languages = {}
    for path in sorted(language_dir.glob("*.json")):
        languages[path.stem] = read_language(path)
    if not languages:
        fail("没有找到语言表，至少需要 en_us.json")
    if BASE_LANGUAGE not in languages:
        fail("缺少 en_us.json")
    if "zh_cn" not in languages:
        WARNINGS.append("没有 zh_cn.json：中文客户端会回退到英文")

    prefix = "addons." + mod_id + "."
    base = languages[BASE_LANGUAGE]
    for code, entries in sorted(languages.items()):
        for key in sorted(entries):
            if key.startswith(RESERVED_PREFIX):
                ERRORS.append("%s：%s 使用了本体保留的 evernight. 命名空间" % (code + ".json", key))
            elif not key.startswith(prefix):
                ERRORS.append("%s：%s 必须以 %s 开头" % (code + ".json", key, prefix))
        if code == BASE_LANGUAGE:
            continue
        for key in sorted(set(base) - set(entries)):
            ERRORS.append("%s.json 缺少键：%s" % (code, key))
        for key in sorted(set(entries) - set(base)):
            ERRORS.append("en_us.json 缺少键：%s" % key)
        for key in sorted(set(base) & set(entries)):
            if len(PLACEHOLDER.findall(base[key])) != len(PLACEHOLDER.findall(entries[key])):
                WARNINGS.append("%s.json 的占位符个数与 en_us.json 不一致：%s" % (code, key))

    print("模组 id：%s，语言：%s，%d 个键"
          % (mod_id, ", ".join(sorted(languages)), len(base)))
    return report()


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
