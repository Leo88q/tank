#!/usr/bin/env python3
"""Аудит де-брендинга (Фаза 2, чек-лист §8.9).

Режим отчёта: ищет упоминания старого бренда в текстовых файлах и сверяет
список известных бренд-ассетов, ожидающих замены. Намеренные исключения:
 - блок «Open Source Credits» в credits.tscn (обязательная MIT-атрибуция);
 - текстовые референсы оригинала в docs/ (DESIGN/MANUAL/CREDITS/CHANGELOG);
 - baseline.yml (сборка немодифицированного апстрима — ссылается на него сама).
"""
import os, re, sys

BRAND_PATTERNS = [
    r'tanks of freedom', r'cz[oł]wiekimadlo', r'p1x\.in', r'tof\.p1x', r'api\.tof',
    r'amber noon', r'ruby dusk', r'jade twilight', r'sapphire dawn', r'obsidian night',
    r'rubyport', r'jade oasis', r'ambar fortress|amber fortress', r'sapphire city',
    r'\bgrim\b', r'\btybalt\b', r'\bludwig\b', r'\bgideon\b', r'\bdawson\b',
    r'\barchibald\b', r'\bsidney\b', r'\birene\b', r'\btorsten\b', r'\bclaude\b',
    r'\bcyrus\b', r'\bblake\b', r'dike continent', r'\blibre\b', r'czolgi wolnosci',
]
ALLOWLIST = [
    'scenes/ui/menu/credits.tscn',           # обязательная атрибуция
    '.github/workflows/baseline.yml',        # референс-сборка апстрима
    '.github/workflows/fork-build.yml',      # grep-паттерны гейта старого лора
    'tools/rebrand_audit.py',
    'LICENSE_NOTES.md', 'PROJECT_STATUS.md', 'README.md',   # внутренняя документация проекта
]
SCAN_EXTS = {'.gd', '.tscn', '.tres', '.json', '.csv', '.cfg', '.md', '.txt', '.godot', '.yml'}
SKIP_DIRS = {'.git', '.godot', '_upstream', 'docs'}   # docs — референсные тексты оригинала

# Известные бинарные бренд-ассеты, ожидающие замены (арт/аудио-проход)
PENDING_BINARY_BRAND = {
    'ассеты, ожидaющие решения по заказу/заглушкам (не блокируют dev-сборки)': [
        # иконка/сплэш/превью карт заменены плейсхолдерами (_artgen/); юнит-воксели и
        # шрифты — MIT/легальные ассеты, их замена это арт-направление, а не де-брендинг
    ],
    'аудио оригинала (НЕ ДОЛЖНО попасть в публичный релиз — см. IDENTITY §7)': [
        *[f'assets/audio/soundtrack/{n}' for n in [
            'grand_beats_110.ogg', 'grand_beats_menu_soundtrack.ogg',
            'grand_beats_soundtrack_1_metal.ogg', 'reduz_all_star_champion_sheep.ogg',
            'reduz_capybara_love.ogg', 'reduz_like_a_whale.ogg',
            'reduz_the_sorrows_of_a_crab.ogg']],
    ],
}

def scan_texts():
    hits = []
    rx = re.compile('|'.join(BRAND_PATTERNS), re.I)
    for root, dirs, files in os.walk('.'):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            p = os.path.join(root, f)[2:]
            if p in ALLOWLIST:
                continue
            if os.path.splitext(f)[1].lower() not in SCAN_EXTS and f != 'project.godot':
                continue
            try:
                text = open(p, encoding='utf-8', errors='ignore').read()
            except OSError:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                if rx.search(line):
                    hits.append((p, i, line.strip()[:110]))
    return hits

def main():
    print('=== 1. Текстовые упоминания бренда ===')
    hits = scan_texts()
    for p, i, l in hits:
        print(f'{p}:{i}: {l}')
    print(f'итого: {len(hits)} (цель — 0; исключения см. в шапке скрипта)')
    print()
    print('=== 2. Бинарные бренд-ассеты, ожидающие замены ===')
    for group, items in PENDING_BINARY_BRAND.items():
        present = [x for x in items if os.path.exists(x)]
        print(f'[{group}] осталось {len(present)} из {len(items)}')
        for x in present:
            print('   -', x)
    return 0

if __name__ == '__main__':
    sys.exit(main())
