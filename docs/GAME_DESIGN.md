# Game Design — 「エーテルの年代記」(Ethereal Chronicle)
World: 浮遊する大地「アエリア」。千年前、空を支える〈エーテル核〉が砕け、その欠片〈星片〉が魔物を生んだ。
Chapter 1「砕けた星片」: 見習い冒険者が港町ルミナで目覚め、近郊で星片の異常を調べる。
Flow: 導入(ルミナ到着/職業選択) → 事件(星片の暴走で森の魔物が凶暴化) → 探索(アッシュウッド/廃坑) → 対立(星片を狙う黒き教団〈虚ろの手〉) → クライマックス(廃坑ダンジョン最奥のボス「虚核の守護者ヴォイド・ウォーデン」協力戦) → エンディング(教団の首領が〈第二の星片〉を示唆、第2章への伏線)。
Areas: ルミナ港(街・安全地帯 Lv1-), アッシュウッドの森(Lv1-8), 嘆きの廃坑(Lv8-14), 星見の丘(Lv12-16) + ダンジョン「虚核の聖堂」(協力, Lv15-).
Classes: 戦士(Warrior: 近接、怒りゲージ、盾/範囲), 弓使い(Archer: 遠距離、集中、弱点/機動), 魔法使い(Mage: マナ、属性、範囲/支援).
Skill use: hotbar slot 9 reserved "スキルスロット"/ sneak+右クリックで切替、右クリック(武器)で選択スキル発動、F(持ち替え)キーでスキル1..4 サイクル。 ActionBar にHP/リソース/CD表示。
Level cap 20 (v1.0). Stats: HP, Resource, ATK, DEF, CRIT%, CRIT_DMG, ELEMENT_RES, SKILL_POWER.
Combat damage: dmg = (ATK * skillMult + weapon) * (100/(100+DEF)) * crit * elementMod, clamped by balance caps.
Rarity: コモン / アンコモン / レア / エピック / レジェンダリー (stat budget multipliers & affix count 0-4; special effects from rare+).
v1.0 content target: 3 classes, ≥15 quests, ≥20 equipment, ≥10 enemies, ≥2 bosses, 1 coop dungeon, NPC dialogue+shops, party, tutorial, admin commands.
