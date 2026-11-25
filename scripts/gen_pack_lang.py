"""リソースパックの言語ファイル(assets/ec/lang/{ja_jp,en_us}.json)を content/*.yml と lang/content_en_us.yml から生成します。

クライアント描画される共有オブジェクト(NPC名・敵名・採取ノード・アイテム名/説明・進捗)の翻訳キーを出力します。
使い方: python scripts/gen_pack_lang.py   (要 PyYAML)
"""
import glob, json, os

LORE_WRAP_JA = 28   # DialogueLayout.TEXT_WRAP_CHARACTERS と一致させる
LORE_WRAP_EN = 46   # LoreWrap.EN_WIDTH と一致させる
STATIC = {
    "ja_jp": {
        "ec.advancement.root_title": "エセリアルクロニクル", "ec.advancement.root_description": "冒険の記録",
        "ec.item.required_level": "必要レベル: %1$s / %2$s", "ec.gathering.node_label": "[採取] %1$s (Lv.%2$s)",
        "ec.rarity.common": "コモン", "ec.rarity.uncommon": "アンコモン", "ec.rarity.rare": "レア",
        "ec.rarity.epic": "エピック", "ec.rarity.legendary": "レジェンダリー",
    },
    "en_us": {
        "ec.advancement.root_title": "Ethereal Chronicle", "ec.advancement.root_description": "Adventure records",
        "ec.item.required_level": "Required level: %1$s / %2$s", "ec.gathering.node_label": "[Gather] %1$s (Lv. %2$s)",
        "ec.rarity.common": "Common", "ec.rarity.uncommon": "Uncommon", "ec.rarity.rare": "Rare",
        "ec.rarity.epic": "Epic", "ec.rarity.legendary": "Legendary",
    },
}

def wrap_ja(text): return [text[i:i + LORE_WRAP_JA] for i in range(0, len(text), LORE_WRAP_JA)]

def wrap_en(text):
    lines, current = [], ""
    for word in text.split(" "):
        candidate = word if not current else current + " " + word
        if len(candidate) > LORE_WRAP_EN and current:
            lines.append(current); current = word
        else: current = candidate
    if current: lines.append(current)
    return lines

def sid(x): return str(x).split(":", 1)[-1]

def main():
    import yaml
    english = {}
    for line in open("src/main/resources/lang/content_en_us.yml", encoding="utf-8"):
        if line.startswith("ec.content."):
            k, v = line.rstrip("\n").split(": ", 1); english[k] = json.loads(v)
    out = {"ja_jp": dict(STATIC["ja_jp"]), "en_us": dict(STATIC["en_us"])}
    def put(key, ja):
        out["ja_jp"][key] = ja; out["en_us"][key] = english.get(key, ja)
    for f in sorted(glob.glob("src/main/resources/content/**/*.yml", recursive=True)):
        d = yaml.safe_load(open(f, encoding="utf-8"))
        if not isinstance(d, dict): continue
        cat = f.replace("\\", "/").split("/")[4]; i = sid(d.get("id", ""))
        kind = {"npcs": "npc", "enemies": "enemy", "bosses": "boss", "gathering_nodes": "gathering", "items": "item", "titles": "title"}.get(cat)
        if kind is None: continue
        b = f"ec.content.{kind}.{i}"
        if kind in ("npc", "enemy", "boss", "gathering", "item"): put(b + ".name", d["name"])
        if kind == "item" and d.get("lore"):
            ja_lines, en_lines = wrap_ja(d["lore"]), wrap_en(english.get(b + ".lore", d["lore"]))
            for n in range(max(len(ja_lines), len(en_lines))):
                out["ja_jp"][f"{b}.lore.{n}"] = ja_lines[n] if n < len(ja_lines) else ""
                out["en_us"][f"{b}.lore.{n}"] = en_lines[n] if n < len(en_lines) else ""
        if kind == "title" and d.get("advancement"):
            a = d["advancement"]
            put(f"ec.content.advancement.{i}.title", a["title"]); put(f"ec.content.advancement.{i}.desc", a["description"])
    for lang, data in out.items():
        path = f"resourcepack/assets/ec/lang/{lang}.json"
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(dict(sorted(data.items())), fh, ensure_ascii=False, indent=2); fh.write("\n")
        print(path, len(data))

main()
