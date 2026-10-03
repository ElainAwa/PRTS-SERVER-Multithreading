#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# PRTS devkit · verify/check_package_graph.py
#
# 用途   : 把"Java 包依赖必须无环、依赖只能指向更稳定的一侧"从靠人看变成机器可查。
#          扫 Java 源码的 package / import（含 import static 与通配 .*），建包级依赖图，输出三段：
#            a) 环        —— Tarjan SCC + 有界 DFS，逐条打印 a.b -> c.d -> a.b；并按边的类型分三桶：
#                            真环（只由横向依赖构成，失败）/ 父子互引（a <-> a.b，单列不计失败）/
#                            混合环（借父子边闭合，单列不计失败）；
#            b) 越级依赖  —— 按 --layer-rules（默认内置）报"低层直接依赖高层"，每条附一句修复方向建议；
#            c) 扇入/扇出 —— 每个包被多少包依赖（fan-in）/依赖多少包（fan-out），按 fan-in 降序 Top N。
#          纯静态文本扫描：不编译、不读 class、不联网。
#
# 依赖   : Python 3 标准库（无第三方包；不需要 JDK、不需要 PRTS 实例）。
#
# 参数   : check_package_graph.py [--roots DIR[,DIR...]] [--layer-rules FILE] [--top N]
#                                 [--json PATH] [--quiet] [--strict] [--no-fail-on-cycle]
#                                 [--ignore-parent-child] [--count-parent-child-as-cycle]
#                                 [--count-mixed-as-cycle]
#                                 [--max-cycles N] [--exclude NAME[,NAME...]] [-h|--help]
#                                 [--baseline FILE] [--baseline-write FILE]
#           默认：--ignore-parent-child（父子互引不算环、不失败）；
#                 --count-parent-child-as-cycle 回到旧语义（所有环都算、都失败）；
#                 --count-mixed-as-cycle 更严一档：混合环也算失败（抓模块级互引）。
#          环境: PRTS_REPO —— 不给 --roots 时扫这两个默认根：
#                  $PRTS_REPO/arclight-common/src/main/java
#                  $PRTS_REPO/arclight-neoforge/src/main/java
#                PRTS_REPO 没设又没给 --roots ⇒ 退出码 2，并提示怎么给。
#          位置参数也可直接写源码根目录（等价于 --roots）。
#
# 输入   : 一个或多个 Java 源码根目录。默认跳过 .git/.gradle/.idea/.vscode/node_modules，以及
#          build/out/target/bin —— 后四个只在"目录里没有直接 .java"时跳（它们也可能是正常包名，
#          本仓就有 ...entity.ai.goal.target）；--exclude 给名单时整体覆盖默认名单，且无条件跳过。
#
# 输出   : stdout 三段（真环 / 父子互引 / 混合环、越级依赖、扇入扇出）+ 末行 SUMMARY（键值对，便于
#          grep）；给了 --json PATH 时另写一份自描述 JSON：roots、java_files、packages/package_count、
#          internal_edges/edge_count、horizontal_edges、vertical_edges、scan_sec、elapsed_sec、
#          cycle_mode、cycles、true_cycles、parent_child_cycles、mixed_cycles（都带 *_count 与
#          *_truncated）、violations（每条带 sample 与 suggestion）、fan、layer_rules、exit_code；
#          给了 --baseline 时另加 baseline_file、known_cycles / new_true_cycles（*_count 同名）、
#          known_violation_count / new_violations（*_count 同名）、baseline_repaid（已还清条目）。
#
# 退出码 : 0 = 没有真环（--strict 时还要无越级依赖；--baseline 时还要没有基线外的新增）
#          1 = 有真环；--strict 时越级依赖也算；--count-mixed-as-cycle 时混合环也算；
#              --baseline 时基线内的算 known、只有新增才失败，越级的新增同样失败；
#              --no-fail-on-cycle 只报告环、不改退出码。父子互引永远不失败。
#          2 = 空输入或用法错（没有 --roots 也没设 PRTS_REPO、给的根都不存在、根下没有 .java、
#              没有一条 package 声明、规则文件写坏、基线文件不存在或写坏、参数非法、
#              --json 写不出去）
#          --baseline-write FILE 写出基线后直接以 0 退出（写基线本身是动作，不参与判定）。
#
# 常见误读: 1) 只统计"被扫到的包"之间的边：net.minecraft/java.* 这类外部 import 不建边，
#              所以 fan-out 不是源码 import 总数，而是指向内部包的依赖数。扫的根少一个
#              （例如只扫 common 不扫 neoforge），跨模块依赖就整块看不见。
#           2) 环按"包"判定，且分三桶：真环 = 只由横向依赖（互不为祖先/后代的两个包）构成的基本环，
#              默认只有它失败；父子互引 = 只由父子边构成（a 引 a.b、a.b 又引 a），单列、不计失败——
#              Java 里这是常态，不是缺陷；混合环 = 中间夹了父子边才闭合的环，单列、不计失败，但它可能
#              对应"模块级"互引，想抓就用 --count-mixed-as-cycle。同包内两个类互引不算环（同包边忽略）。
#           3) 越级只看**直接依赖**：低层 -> 中层 -> 高层 这条链本身不报，报的是低层直接
#              import 高层；链式中转要靠 fan-out 表或 --json 自己走图。
#           4) 未命中任何层规则的包（unranked）不参与越级判定。内置默认只覆盖 PRTS 的
#              prts.* 与命令/事件/入口包，别的仓库要写 --layer-rules。
#           5) 文本扫描不剥全部语法：顶格写在块注释行首的 "import x.Y;" 可能被算进去
#              （正常 javadoc 的 " * import" 不会）；字符串里的 import 同理。import static
#              与通配 .* 已正确解析。
#           6) 嵌套类 import（a.b.Outer.Inner）按"最长的已知包前缀"归属，遇到包名与类名
#              撞车时可能归错——已知启发式，宁可归到最近的内部包。
#           7) 每个桶里的条数 = 枚举到的基本环（elementary cycle）条数，不是"互相依赖的包组数"；
#              同一张图三桶相加不等于旧口径的总数吗？等于：真环 + 纯父子互引 + 混合 = 全图基本环总数
#              （本仓 17 + 8 + 68 = 93）。一个稠密 SCC 能出很多条，所以有 --max-cycles 上限（默认 100），
#              打满会标注"可能还有更多"；真环单独枚举，不会被父子边的海量环挤掉。
#
# 内置默认层规则（rank 越小越底层；规则按书写顺序匹配，包的任一祖先（含自身）命中即算命中，
# 即一条规则天然覆盖其子包）:
#   rank 10  *.config  *.kernel.auth  *.kernel.intent  *.kernel.meter
#            *.kernel.shares  *.kernel.waitpoints  *.kernel.observe
#   rank 20  *.kernel
#   rank 30  *.command  *.commands  *.event  *.events  *.listener  *.listeners
#            *.entry  *.entrypoint  *.bootstrap  *.plugin  *.plugins
#   越级 = 一条直接依赖的源包 rank < 目标包 rank。
#   --layer-rules FILE 覆盖内置规则：每行 "glob=rank"（# 注释与空行忽略；也接受空格分隔）。
#
# 基线   : 存量结构债白名单，只挡新增。--baseline FILE 读入基线：
#            · 基线内的真环 / 越级照常打印，后缀标 [known]，不失败；
#            · 不在基线里的真环 / 越级后缀标 [new]，失败（越级的新增无需 --strict —— 这就是基线的语义）；
#            · 基线里有、这次图里没有的条目 = 已还清，单独列出并提示重写基线；
#            · 文件不存在、某行格式不对 ⇒ 退出码 2（拒绝"当没有基线"往下跑）。
#          文件格式：纯文本，一行一条，'#' 开头为注释：
#            cycle: a.b <-> c.d <-> e.f      （包名环；写成时以最小包名起头，不重复首包）
#            layer: a.b -> c.d               （越级依赖，低层 -> 高层）
#          --baseline-write FILE 把当前真环与越级按稳定顺序写成基线（注释头含生成时间与仓库 HEAD）。
#          更新时机：只在真的还清一条债之后重写——重新生成、逐条核对 diff，列表应当变短；
#          新增的结构债不许写进基线（那不是还债，是把门禁关掉）。
#          注意：基线只覆盖真环与越级；--count-mixed-as-cycle 的混合环不在基线口径内，仍会失败。
#
# --strict 语义 : 默认就是严格——有真环即失败（exit 1）。--strict 再加一档：越级依赖也算失败。
#                 --no-fail-on-cycle 只报告环、不改退出码，供探索用法。
#                 --count-parent-child-as-cycle 回到旧语义（父子互引、混合环都算失败）。
#
# 性能   : 一次性正则扫描 + 集合查找；本仓 ~1100 个 Java 文件约 1 秒。
# ---------------------------------------------------------------------------
"""Java package-dependency graph checker: cycles, layer violations, fan-in/out.

Usage: check_package_graph.py [--roots DIR[,DIR...]] [--layer-rules FILE] [--top N]
                              [--json PATH] [--quiet] [--strict] [--no-fail-on-cycle]
                              [--max-cycles N] [--exclude NAME[,NAME...]] [-h|--help]
"""

import json
import os
import re
import sys
import time
from fnmatch import fnmatch

PROG = "check_package_graph.py"

# rank 越小越底层；顺序即匹配优先级（具体规则写在宽规则前面）
DEFAULT_RULES = [
    ("*.config", 10),
    ("*.kernel.auth", 10),
    ("*.kernel.intent", 10),
    ("*.kernel.meter", 10),
    ("*.kernel.shares", 10),
    ("*.kernel.waitpoints", 10),
    ("*.kernel.observe", 10),
    ("*.kernel", 20),
    ("*.command", 30),
    ("*.commands", 30),
    ("*.event", 30),
    ("*.events", 30),
    ("*.listener", 30),
    ("*.listeners", 30),
    ("*.entry", 30),
    ("*.entrypoint", 30),
    ("*.bootstrap", 30),
    ("*.plugin", 30),
    ("*.plugins", 30),
]

ALWAYS_EXCLUDES = (".git", ".gradle", ".idea", ".vscode", "node_modules")
# build/out/target/bin 只按默认名单跳；且"直接含 .java"时当包目录留下——
# 这些名字也可能是正常包名（本仓就有 ...entity.ai.goal.target，靠人看会漏 6 个文件）。
# --exclude 给名单时按名单无条件跳过，等于关掉这套保护。
BUILD_LIKE_EXCLUDES = ("build", "out", "target", "bin")
DEFAULT_EXCLUDES = ALWAYS_EXCLUDES + BUILD_LIKE_EXCLUDES

DEFAULT_ROOTS = (
    os.path.join("arclight-common", "src", "main", "java"),
    os.path.join("arclight-neoforge", "src", "main", "java"),
)

# 一次正则扫出 package / import，同时吃掉注释（注释分支先匹配，避免注释里的 import 被算进来）
SCAN_RE = re.compile(
    r"""
      (?P<comment> /\* .*? \*/ | // [^\n]* )
    | (?P<package> ^ [ \t]* package [ \t]+
                    (?P<pkg>  [A-Za-z_$][\w$]* (?: \. [A-Za-z_$][\w$]* )* ) [ \t]* ; )
    | (?P<import>  ^ [ \t]* import [ \t]+
                    (?: (?P<static> static [ \t]+ ) )?
                    (?P<imp>  [A-Za-z_$][\w$]* (?: \. [A-Za-z_$][\w$]* )* )
                    (?: [ \t]* \. [ \t]* (?P<star> \* ) )? [ \t]* ; )
    """,
    re.MULTILINE | re.DOTALL | re.VERBOSE,
)


def usage_from_header(path):
    """脚本头部注释块就是 --help 文本（devkit 约定）。"""
    out = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("#!"):
                continue
            if line.startswith("#"):
                out.append(line[1:].rstrip())
                continue
            break
    return "\n".join(out).strip("\n")


def die(msg, code=2):
    sys.stderr.write("ERROR: %s\n" % msg)
    sys.exit(code)


def take(a, argv, i):
    """取选项值：支持 --opt=V 与 --opt V 两种写法。"""
    if "=" in a:
        return a.split("=", 1)[1], i + 1
    if i + 1 >= len(argv):
        die("option %s needs a value" % a)
    return argv[i + 1], i + 2


def to_int(name, raw):
    try:
        value = int(raw)
    except (TypeError, ValueError):
        die("%s expects an integer, got: %s" % (name, raw))
    if value < 0:
        die("%s must be >= 0" % name)
    return value


def parse_args(argv):
    opts = {
        "roots": [],
        "layer_rules": None,
        "top": 20,
        "json": None,
        "quiet": False,
        "strict": False,
        "fail_on_cycle": True,
        "ignore_parent_child": True,
        "count_mixed": False,
        "max_cycles": 100,
        "excludes": None,
        "baseline": None,
        "baseline_write": None,
    }
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("-h", "--help"):
            print(usage_from_header(__file__))
            sys.exit(0)
        elif a == "--roots" or a.startswith("--roots="):
            raw, i = take(a, argv, i)
            opts["roots"] += [p for p in raw.split(",") if p]
        elif a == "--layer-rules" or a.startswith("--layer-rules="):
            opts["layer_rules"], i = take(a, argv, i)
        elif a == "--top" or a.startswith("--top="):
            raw, i = take(a, argv, i)
            opts["top"] = to_int("--top", raw)
        elif a == "--json" or a.startswith("--json="):
            opts["json"], i = take(a, argv, i)
        elif a == "--max-cycles" or a.startswith("--max-cycles="):
            raw, i = take(a, argv, i)
            opts["max_cycles"] = to_int("--max-cycles", raw)
        elif a == "--exclude" or a.startswith("--exclude="):
            raw, i = take(a, argv, i)
            opts["excludes"] = set(p for p in raw.split(",") if p)
        elif a == "--baseline-write" or a.startswith("--baseline-write="):
            opts["baseline_write"], i = take(a, argv, i)
        elif a == "--baseline" or a.startswith("--baseline="):
            opts["baseline"], i = take(a, argv, i)
        elif a == "--quiet":
            opts["quiet"] = True
            i += 1
        elif a == "--strict":
            opts["strict"] = True
            i += 1
        elif a == "--no-fail-on-cycle":
            opts["fail_on_cycle"] = False
            i += 1
        elif a == "--ignore-parent-child":
            opts["ignore_parent_child"] = True
            i += 1
        elif a == "--count-parent-child-as-cycle":
            opts["ignore_parent_child"] = False
            i += 1
        elif a == "--count-mixed-as-cycle":
            opts["count_mixed"] = True
            i += 1
        elif a.startswith("-"):
            sys.stderr.write("ERROR: unknown option: %s\n\n" % a)
            print(usage_from_header(__file__))
            sys.exit(2)
        else:
            opts["roots"].append(a)
            i += 1
    return opts


def load_layer_rules(path):
    """规则文件：每行 glob=rank（或空格分隔）；# 注释、空行忽略。"""
    rules = []
    try:
        with open(path, encoding="utf-8") as handle:
            lines = handle.readlines()
    except OSError as exc:
        die("cannot read --layer-rules %s: %s" % (path, exc))
    for no, raw in enumerate(lines, 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if "=" in line:
            glob, rank_raw = line.rsplit("=", 1)
        else:
            parts = line.split()
            if len(parts) != 2:
                die("%s:%d: expected 'glob=rank', got: %s" % (path, no, raw.strip()))
            glob, rank_raw = parts
        glob = glob.strip()
        rank_raw = rank_raw.strip()
        if not glob:
            die("%s:%d: empty glob in: %s" % (path, no, raw.strip()))
        try:
            rank = int(rank_raw)
        except ValueError:
            die("%s:%d: rank is not an integer: %s" % (path, no, rank_raw))
        rules.append((glob, rank))
    if not rules:
        die("--layer-rules %s has no usable 'glob=rank' line (refusing to guess)" % path)
    return rules


BASELINE_CYCLE = "cycle:"
BASELINE_LAYER = "layer:"
PACKAGE_RE = re.compile(r"^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+$")


def canonical_cycle(nodes):
    """把环写成规范形：从最小的包名起头、保持边的顺序（写盘与比对都用它）。"""
    if not nodes:
        return ()
    idx = min(range(len(nodes)), key=lambda i: nodes[i])
    return tuple(nodes[idx:]) + tuple(nodes[:idx])


def cycle_key(cyc):
    return canonical_cycle(list(cyc))


def cycle_line(cyc):
    return "%s %s" % (BASELINE_CYCLE, " <-> ".join(canonical_cycle(list(cyc))))


def layer_line(v):
    return "%s %s -> %s" % (BASELINE_LAYER, v["from"], v["to"])


def is_package_name(text):
    return bool(PACKAGE_RE.match(text))


def find_git_dir(root):
    """从扫描根往上找 .git（目录，或 worktree 的 gitdir 文件）；找不到返回 None。"""
    cur = os.path.abspath(root)
    while True:
        candidate = os.path.join(cur, ".git")
        if os.path.isdir(candidate):
            return candidate
        if os.path.isfile(candidate):
            try:
                with open(candidate, encoding="utf-8") as handle:
                    line = handle.readline().strip()
            except OSError:
                return None
            if line.startswith("gitdir:"):
                return os.path.abspath(os.path.join(cur, line.split(":", 1)[1].strip()))
            return None
        parent = os.path.dirname(cur)
        if parent == cur:
            return None
        cur = parent


def packed_ref(gitdir, ref):
    """refs 打包在 packed-refs 里时从那里取（同样只读文本）。"""
    try:
        with open(os.path.join(gitdir, "packed-refs"), encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith(("#", "^")):
                    continue
                parts = line.split()
                if len(parts) == 2 and parts[1] == ref:
                    return parts[0]
    except OSError:
        pass
    return ""


def checkout_root(root):
    """扫描根所属的检出根（有 .git 就用它的父目录，否则退回扫描根本身）。"""
    gitdir = find_git_dir(root)
    return os.path.dirname(gitdir) if gitdir else os.path.abspath(root)


def git_head(root):
    """尽力而为读 HEAD 短 sha（只读 .git 文本，不调 git、不联网）；读不到返回 None。"""
    gitdir = find_git_dir(root)
    if not gitdir:
        return None
    try:
        with open(os.path.join(gitdir, "HEAD"), encoding="utf-8") as handle:
            head = handle.readline().strip()
    except OSError:
        return None
    if head.startswith("ref:"):
        ref = head.split(":", 1)[1].strip()
        try:
            with open(os.path.join(gitdir, ref), encoding="utf-8") as handle:
                sha = handle.readline().strip()
        except OSError:
            sha = packed_ref(gitdir, ref)
    else:
        sha = head
    if re.match(r"^[0-9a-fA-F]{7,40}$", sha or ""):
        return sha[:12]
    return None


def write_baseline(path, roots, cycles, violations, rules_path):
    """把当前真环与越级写成基线（稳定排序 + 注释头含生成时间与仓库 HEAD）。"""
    head = git_head(roots[0]) or "unknown"
    stamp = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    cycle_lines = [cycle_line(c) for c in sorted(cycles, key=lambda c: (len(c), cycle_key(c)))]
    layer_lines = [layer_line(v) for v in sorted(violations, key=lambda v: (v["from"], v["to"]))]
    header = [
        "# PRTS package-graph baseline — known structural debt, blocks NEW entries only.",
        "# generated: %s" % stamp,
        "# repo: %s (head %s)" % (checkout_root(roots[0]), head),
        "# layer rules: %s" % (rules_path or "(built-in)"),
        "# entries: %d cycle(s) · %d layer(s)" % (len(cycle_lines), len(layer_lines)),
        "# format: one entry per line — 'cycle: a <-> b <-> c' or 'layer: from -> to'; '#' starts a comment",
        "# update: only when a debt is really repaid — regenerate, review the diff; the list should",
        "#         shrink, and a new cycle or violation must never be written into the baseline.",
        "",
    ]
    try:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(header + cycle_lines + layer_lines) + "\n")
    except OSError as exc:
        die("cannot write --baseline-write %s: %s" % (path, exc))
    return len(cycle_lines), len(layer_lines)


def load_baseline(path):
    """读基线：返回 (真环规范形集合, (from,to) 集合)；文件缺失或行写坏一律 exit 2。"""
    if not os.path.isfile(path):
        die("--baseline %s: no such file — write it first with --baseline-write %s "
            "(a baseline lists the known structural debt; the check then fails only on "
            "entries missing from it)" % (path, path))
    cycles, layers = set(), set()
    try:
        handle = open(path, encoding="utf-8")
    except OSError as exc:
        die("cannot read --baseline %s: %s" % (path, exc))
    with handle:
        for no, raw in enumerate(handle, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith(BASELINE_CYCLE):
                body = line[len(BASELINE_CYCLE):].strip()
                nodes = [p.strip() for p in body.split("<->" if "<->" in body else "->")]
                nodes = [p for p in nodes if p]
                if len(nodes) > 1 and nodes[0] == nodes[-1]:
                    nodes = nodes[:-1]  # 容忍把首包在末尾又写了一遍的写法
                if len(nodes) < 2 or not all(is_package_name(n) for n in nodes):
                    die("%s:%d: bad cycle entry (want 'cycle: a <-> b <-> c'): %s"
                        % (path, no, line))
                cycles.add(canonical_cycle(nodes))
            elif line.startswith(BASELINE_LAYER):
                body = line[len(BASELINE_LAYER):].strip()
                if "->" not in body:
                    die("%s:%d: bad layer entry (want 'layer: from -> to'): %s" % (path, no, line))
                src, dst = (p.strip() for p in body.split("->", 1))
                if not is_package_name(src) or not is_package_name(dst):
                    die("%s:%d: bad package name in layer entry: %s" % (path, no, line))
                layers.add((src, dst))
            else:
                die("%s:%d: unknown baseline line (want 'cycle:' or 'layer:'): %s"
                    % (path, no, line))
    return cycles, layers


def match_layer(pkg, rules):
    """包的任一祖先（含自身）命中规则即算命中；返回 (rank, glob) 或 (None, None)。"""
    parts = pkg.split(".")
    ancestors = [".".join(parts[:i]) for i in range(len(parts), 0, -1)]
    for glob, rank in rules:
        for anc in ancestors:
            if fnmatch(anc, glob):
                return rank, glob
    return None, None


def package_candidates(spec, is_static, is_wildcard):
    """把一个 import 归约成候选包，从最长（最具体）到最短；取第一个已知内部包。"""
    if is_wildcard and not is_static:
        return [spec]  # import a.b.* —— 就是 a.b 这个包
    segs = spec.split(".")
    if is_static and not is_wildcard:
        segs = segs[:-1]  # import static a.b.C.member —— 去掉成员，剩下的 a.b.C 是类型
    return [".".join(segs[:i]) for i in range(len(segs) - 1, 0, -1)]


def scan_java(text):
    """返回 (package 或 None, [(import 路径, static, wildcard, 行号), ...])。"""
    pkg = None
    imports = []
    line = 1
    pos = 0
    for m in SCAN_RE.finditer(text):
        line += text.count("\n", pos, m.start())
        pos = m.start()
        if m.group("comment") is not None:
            continue
        if m.group("package") is not None:
            if pkg is None:
                pkg = m.group("pkg")
            continue
        if m.group("import") is not None:
            imports.append((m.group("imp"), bool(m.group("static")),
                            bool(m.group("star")), line))
    return pkg, imports


def iter_java_files(roots, excludes, guard_build_dirs):
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            keep = []
            for name in sorted(dirnames):
                if name not in excludes:
                    keep.append(name)
                    continue
                if guard_build_dirs and name in BUILD_LIKE_EXCLUDES:
                    # 只有"自己直接含 .java"才当它是包目录留下（真正的 build/ 产物没有）
                    try:
                        if any(n.endswith(".java") for n in os.listdir(os.path.join(dirpath, name))):
                            keep.append(name)
                    except OSError:
                        pass
            dirnames[:] = keep
            for name in sorted(filenames):
                if name.endswith(".java"):
                    yield os.path.join(dirpath, name)


def tarjan_scc(nodes, adj):
    """迭代版 Tarjan：返回 SCC 列表（每个是节点名列表）。"""
    index_of = {}
    low = {}
    on_stack = set()
    stack = []
    sccs = []
    counter = 0
    for start in nodes:
        if start in index_of:
            continue
        index_of[start] = low[start] = counter
        counter += 1
        stack.append(start)
        on_stack.add(start)
        work = [(start, iter(sorted(adj.get(start, ()))))]
        while work:
            v, it = work[-1]
            advanced = False
            for w in it:
                if w not in index_of:
                    index_of[w] = low[w] = counter
                    counter += 1
                    stack.append(w)
                    on_stack.add(w)
                    work.append((w, iter(sorted(adj.get(w, ())))))
                    advanced = True
                    break
                if w in on_stack:
                    low[v] = min(low[v], index_of[w])
            if advanced:
                continue
            work.pop()
            if work:
                parent = work[-1][0]
                low[parent] = min(low[parent], low[v])
            if low[v] == index_of[v]:
                comp = []
                while True:
                    w = stack.pop()
                    on_stack.discard(w)
                    comp.append(w)
                    if w == v:
                        break
                sccs.append(comp)
    return sccs


def cycle_dfs(start, adj, members, cap, out, budget):
    """从 start 出发枚举基本环（只走 >= start 的成员，保证每个环只在最小节点处报一次）。

    返回 True 表示撞到 cap/budget、枚举被截断。
    """
    def nbrs(v):
        return iter(sorted(w for w in adj.get(v, ()) if w in members and w >= start))

    path = [start]
    onpath = {start}
    frames = [[start, nbrs(start)]]
    steps = 0
    while frames:
        v, it = frames[-1]
        advanced = False
        for w in it:
            steps += 1
            budget[0] += 1
            if budget[0] > budget[1]:
                return True
            if w == start:
                out.append(list(path))
                if len(out) >= cap:
                    return True
            elif w not in onpath:
                path.append(w)
                onpath.add(w)
                frames.append([w, nbrs(w)])
                advanced = True
                break
        if advanced:
            continue
        frames.pop()
        if frames:
            path.pop()
            onpath.discard(v)
    return False


def find_cycles(adj, sccs, cap):
    out = []
    budget = [0, max(200000, cap * 2000)]
    for comp in sccs:
        if len(comp) < 2:
            continue
        members = set(comp)
        for start in sorted(comp):
            if cycle_dfs(start, adj, members, cap, out, budget):
                return out, True
    return out, False


def cycles_of(adj, nodes, cap):
    """在给定子图上枚举基本环（先 Tarjan SCC，再有界 DFS）。"""
    return find_cycles(adj, tarjan_scc(nodes, adj), cap)


def is_ancestor_related(a, b):
    """a、b 之间是不是"父子包"关系（一个是另一个的祖先）。"""
    return b.startswith(a + ".") or a.startswith(b + ".")


def split_edges(edges):
    """把包级边分成横向边与父子边两组。"""
    horizontal, vertical = {}, {}
    for src, dsts in edges.items():
        for dst in dsts:
            bucket = vertical if is_ancestor_related(src, dst) else horizontal
            bucket.setdefault(src, set()).add(dst)
    return horizontal, vertical


def cycle_edges(cyc):
    return [(cyc[i], cyc[(i + 1) % len(cyc)]) for i in range(len(cyc))]


def all_vertical(cyc):
    return all(is_ancestor_related(s, d) for s, d in cycle_edges(cyc))


def all_horizontal(cyc):
    return all(not is_ancestor_related(s, d) for s, d in cycle_edges(cyc))


def violation_suggestion(src, dst, src_rank, dst_rank, src_glob, dst_glob):
    """给一条越级依赖一句可执行的方向建议（只看规则与 rank，不做语义推断）。"""
    if src_glob and src_glob.endswith(".config"):
        return ("配置层应保持被动：把 %s（rank %d）里被 %s 引用的类型下沉到 rank <= %d 的包，"
                "或在 %s 里定义只读接口/记录、由 %s 实现——%s 不应反向 import 业务实现"
                % (dst, dst_rank, src, src_rank, src, dst, src))
    if dst_glob and dst_glob.endswith((".command", ".commands", ".event", ".events",
                                       ".listener", ".listeners", ".entry", ".entrypoint",
                                       ".bootstrap", ".plugin", ".plugins")):
        return ("方向反了：让 %s 定义回调/接口（低层），由 %s 注册实现；"
                "%s 不应直接 import 高层入口包" % (src, dst, src))
    return ("把 %s（rank %d）里被引用的类型下沉到 rank <= %d 的包，或让 %s 只依赖更低层的接口、"
            "把实现留在 %s" % (dst, dst_rank, src_rank, src, dst))


def main():
    t0 = time.time()
    opts = parse_args(sys.argv[1:])
    quiet = opts["quiet"]
    rules = load_layer_rules(opts["layer_rules"]) if opts["layer_rules"] else list(DEFAULT_RULES)
    if opts["excludes"] is not None:
        excludes, guard_build_dirs = set(opts["excludes"]), False
    else:
        excludes, guard_build_dirs = set(DEFAULT_EXCLUDES), True

    # ---- 根目录解析：空输入是铁律的 exit 2
    roots = list(opts["roots"])
    if not roots:
        repo = os.environ.get("PRTS_REPO", "").strip()
        if not repo:
            die("no roots to scan: pass --roots DIR[,DIR...] or set PRTS_REPO "
                "(default roots: $PRTS_REPO/arclight-common/src/main/java, "
                "$PRTS_REPO/arclight-neoforge/src/main/java)")
        roots = [os.path.join(repo, rel) for rel in DEFAULT_ROOTS]
    missing = [r for r in roots if not os.path.isdir(r)]
    roots = [r for r in roots if os.path.isdir(r)]
    if not roots:
        die("none of the given roots exist" + ":\n       " + "\n       ".join(missing))
    if missing and not quiet:
        print("WARN: skipping missing roots: %s" % ", ".join(missing))

    # ---- 扫描：一次正则，逐文件取 package / import
    files = 0
    no_pkg = 0
    unreadable = 0
    scanned = []
    for path in iter_java_files(roots, excludes, guard_build_dirs):
        files += 1
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except OSError as exc:
            unreadable += 1
            if not quiet:
                print("WARN: cannot read %s: %s" % (path, exc))
            continue
        pkg, imports = scan_java(text)
        if pkg is None:
            no_pkg += 1
            continue
        scanned.append((path, pkg, imports))

    scan_sec = time.time() - t0
    packages = set(pkg for _, pkg, _ in scanned)
    if not packages:
        die("no Java package declarations found under: %s\n"
            "       (%d .java file(s) seen%s) — nothing to build a graph from"
            % (", ".join(roots), files, ", all without a package statement" if files else ""))

    # ---- 建图：只保留内部包之间的边；同包忽略
    edges = {}
    samples = {}
    for path, owner, imports in scanned:
        for spec, is_static, is_wildcard, line in imports:
            for cand in package_candidates(spec, is_static, is_wildcard):
                if cand == owner:
                    break
                if cand in packages:
                    edges.setdefault(owner, set()).add(cand)
                    hit = samples.setdefault((owner, cand), [])
                    if len(hit) < 3:
                        hit.append("%s:%d" % (path, line))
                    break

    edge_count = sum(len(v) for v in edges.values())
    nodes = sorted(packages)
    layer = {pkg: match_layer(pkg, rules) for pkg in nodes}

    # ---- a) 环：按边的类型分三桶（父子边 = 一个包是另一个的祖先/后代）
    #      真环     = 只由横向边构成 —— 默认只有它算失败
    #      父子互引 = 只由父子边构成（a <-> a.b）—— 单列，不计失败
    #      混合环   = 横纵边一起才闭合 —— 单列，不计失败（--count-mixed-as-cycle 才失败）
    cap = max(1, opts["max_cycles"])
    edges_h, edges_v = split_edges(edges)
    h_edge_count = sum(len(v) for v in edges_h.values())
    v_edge_count = sum(len(v) for v in edges_v.values())
    cycles_true, trunc_true = cycles_of(edges_h, nodes, cap)
    cycles_pc, trunc_pc = cycles_of(edges_v, nodes, cap)
    cycles_all, trunc_all = cycles_of(edges, nodes, cap)
    mixed = [c for c in cycles_all if not all_vertical(c) and not all_horizontal(c)]
    for bucket in (cycles_true, cycles_pc, mixed):
        bucket.sort(key=lambda c: (len(c), c))  # 短的先报：最好修、也最能说明问题
    if opts["ignore_parent_child"]:
        cycles = list(cycles_true) + (list(mixed) if opts["count_mixed"] else [])
        truncated = trunc_true or (trunc_all if opts["count_mixed"] else False)
    else:
        cycles = list(cycles_all)
        truncated = trunc_all
    cycles.sort(key=lambda c: (len(c), c))

    # ---- b) 越级依赖（低层 -> 高层，直接依赖）
    violations = []
    for src in nodes:
        src_rank, src_glob = layer[src]
        if src_rank is None:
            continue
        for dst in sorted(edges.get(src, ())):
            dst_rank, dst_glob = layer[dst]
            if dst_rank is None or dst_rank <= src_rank:
                continue
            violations.append({
                "from": src, "to": dst,
                "from_rank": src_rank, "to_rank": dst_rank,
                "from_rule": src_glob, "to_rule": dst_glob,
                "sample": (samples.get((src, dst)) or [""])[0],
                "suggestion": violation_suggestion(src, dst, src_rank, dst_rank,
                                                   src_glob, dst_glob),
            })
    violations.sort(key=lambda v: (v["from_rank"], v["from"], v["to"]))

    # ---- 基线：只挡新增（--baseline-write 写完即退出；--baseline 把失败面收窄到"不在基线里"）
    baseline_path = opts["baseline"]
    known_cycles, known_violations = set(), set()
    repaid_cycles, repaid_layers = [], []
    if opts["baseline_write"]:
        written_cycles, written_layers = write_baseline(
            opts["baseline_write"], roots, cycles_true, violations, opts["layer_rules"])
        if not quiet:
            print("")
            print("-- 基线已写出: %s（真环 %d 条 · 越级 %d 条）--"
                  % (opts["baseline_write"], written_cycles, written_layers))
            print("   生成时间 %s · HEAD %s"
                  % (time.strftime("%Y-%m-%dT%H:%M:%S%z"), git_head(roots[0]) or "unknown"))
            print("   更新口径：只在真的还清一条债时重写；新增的结构债不许写进基线。")
        sys.exit(0)
    if baseline_path:
        known_cycles, known_violations = load_baseline(baseline_path)
        repaid_cycles = sorted(known_cycles - {cycle_key(c) for c in cycles_true})
        repaid_layers = sorted(known_violations - {(v["from"], v["to"]) for v in violations})
    known_true_cycles = [c for c in cycles_true if cycle_key(c) in known_cycles] if baseline_path else []
    new_true_cycles = [c for c in cycles_true if cycle_key(c) not in known_cycles] if baseline_path else []
    known_violation_list = ([v for v in violations if (v["from"], v["to"]) in known_violations]
                            if baseline_path else [])
    new_violation_list = ([v for v in violations if (v["from"], v["to"]) not in known_violations]
                          if baseline_path else [])

    # ---- c) 扇入/扇出
    fan_in = {pkg: 0 for pkg in nodes}
    fan_out = {pkg: len(edges.get(pkg, ())) for pkg in nodes}
    for src, dsts in edges.items():
        for dst in dsts:
            fan_in[dst] += 1
    fan = sorted(nodes, key=lambda p: (-fan_in[p], -fan_out[p], p))
    unranked = [p for p in nodes if layer[p][0] is None]

    # ---- stdout
    def say(line=""):
        if not quiet:
            print(line)

    print("== Java 包依赖图检查 ==")
    say("根(%d): %s" % (len(roots), ", ".join(roots)))
    say("文件: %d 个 .java（无 package 声明 %d%s）· 包: %d · 内部依赖边: %d · 耗时 %.2fs"
        % (files, no_pkg, "，读不了 %d" % unreadable if unreadable else "",
           len(packages), edge_count, time.time() - t0))

    def trunc_note(flag):
        return "（撞上 --max-cycles=%d，可能还有更多）" % opts["max_cycles"] if flag else ""

    def print_cycles(title, bucket, note, limit, known=None):
        if not bucket:
            print("-- %s: 0 条 --" % title)
            return
        extra = ""
        if known is not None:
            inside = sum(1 for c in bucket if cycle_key(c) in known)
            extra = "（基线内 %d · 新增 %d）" % (inside, len(bucket) - inside)
        print("-- %s: %d 条%s%s --" % (title, len(bucket), note, extra))
        for idx, cyc in enumerate(bucket[:limit], 1):
            tag = ""
            if known is not None:
                tag = "   [known]" if cycle_key(cyc) in known else "   [new]"
            print("  %d. %s%s" % (idx, " -> ".join(cyc + [cyc[0]]), tag))
            if idx == 1:
                for i, src in enumerate(cyc):
                    dst = cyc[(i + 1) % len(cyc)]
                    for spot in (samples.get((src, dst)) or [])[:2]:
                        print("       %s -> %s   at %s" % (src, dst, spot))
        if limit < len(bucket):
            print("  其余 %d 条见 --json（--max-cycles 可调上限）" % (len(bucket) - limit))

    print("")
    if baseline_path:
        print("-- 基线: %s（known %d 条真环 · %d 条越级；不在基线里的新增即失败）--"
              % (baseline_path, len(known_true_cycles), len(known_violation_list)))
        print("")
    if opts["ignore_parent_child"]:
        print_cycles("真环（只由横向依赖构成）", cycles_true, trunc_note(trunc_true), 200,
                     known_cycles if baseline_path else None)
        print("")
        print_cycles("父子互引（不计失败；父包与自己的子包互引）", cycles_pc, trunc_note(trunc_pc), 20)
        print("")
        print_cycles("混合环（借父子边闭合，不计失败）", mixed, trunc_note(trunc_all), 5)
    else:
        print_cycles("环（含父子互引；--ignore-parent-child 可单列）", cycles, trunc_note(trunc_all), 200,
                     known_cycles if baseline_path else None)
        print("")
        print("  分类: 真环 %d · 纯父子互引 %d · 混合 %d"
              % (len(cycles_true), len(cycles_pc), len(mixed)))

    print("")
    if violations:
        extra = ""
        if baseline_path:
            extra = "（基线内 %d · 新增 %d）" % (len(known_violation_list), len(new_violation_list))
        print("-- 越级依赖: %d 条（低层 -> 高层）%s--" % (len(violations), extra))
        for idx, v in enumerate(violations, 1):
            tag = ""
            if baseline_path:
                tag = ("   [known]" if (v["from"], v["to"]) in known_violations else "   [new]")
            print("  %d. %s -> %s   rank %d -> %d   规则 %s / %s%s"
                  % (idx, v["from"], v["to"], v["from_rank"], v["to_rank"],
                     v["from_rule"], v["to_rule"], tag))
            say("       at %s" % v["sample"])
            say("       建议: %s" % v["suggestion"])
    else:
        print("-- 越级依赖: 0 条（低层没有直接依赖高层）--")

    if baseline_path and (repaid_cycles or repaid_layers):
        print("")
        print("-- 基线内已还清: %d 条（可以重写基线：--baseline-write %s）--"
              % (len(repaid_cycles) + len(repaid_layers), baseline_path))
        for cyc in repaid_cycles:
            print("  %s" % cycle_line(cyc))
        for src, dst in repaid_layers:
            print("  layer: %s -> %s" % (src, dst))

    if not quiet:
        print("")
        limit = opts["top"] if opts["top"] > 0 else len(fan)
        print("-- 扇入/扇出 Top %d（按 fan-in 降序；fan-in=被多少包依赖，fan-out=依赖多少包）--"
              % min(limit, len(fan)))
        print("  %3s  %-56s %7s %8s  %s" % ("#", "包", "fan-in", "fan-out", "层"))
        for idx, pkg in enumerate(fan[:limit], 1):
            rank = layer[pkg][0]
            print("  %3d  %-56s %7d %8d  %s"
                  % (idx, pkg if len(pkg) <= 56 else pkg[:53] + "...",
                     fan_in[pkg], fan_out[pkg], rank if rank is not None else "-"))
        if limit < len(fan):
            print("  其余 %d 个包省略（--top N 调整，--top 0 打全部；完整数据见 --json）"
                  % (len(fan) - limit))
        print("")
        print("-- 未命中层规则: %d/%d 个包（不参与越级判定；默认规则只覆盖 PRTS prts.* 与命令/事件包，"
              "可用 --layer-rules 覆盖）--" % (len(unranked), len(nodes)))

    # ---- 退出码：默认严格（有真环即失败）；--strict 连越级也失败；父子互引永不失败
    #      --baseline 时失败面收窄到"不在基线里"：基线内的 known 只报告，新增的环与越级都失败
    if baseline_path:
        failing_cycles = [c for c in cycles if cycle_key(c) not in known_cycles]
        failing_violations = new_violation_list
    else:
        failing_cycles, failing_violations = list(cycles), list(violations)
    if failing_cycles and opts["fail_on_cycle"]:
        exit_code = 1
    elif failing_violations and (opts["strict"] or baseline_path):
        exit_code = 1
    else:
        exit_code = 0
    if baseline_path:
        print("")
        print("BASELINE: file=%s known_cycles=%d new_cycles=%d known_violations=%d "
              "new_violations=%d repaid=%d exit=%d"
              % (baseline_path, len(known_true_cycles), len(new_true_cycles),
                 len(known_violation_list), len(new_violation_list),
                 len(repaid_cycles) + len(repaid_layers), exit_code))
    print("")
    print("SUMMARY: packages=%d edges=%d mode=%s cycles=%d true_cycles=%d parent_child=%d mixed=%d "
          "violations=%d unranked=%d exit=%d"
          % (len(nodes), edge_count,
             "ignore-parent-child" if opts["ignore_parent_child"] else "count-parent-child",
             len(cycles), len(cycles_true), len(cycles_pc), len(mixed),
             len(violations), len(unranked), exit_code))

    # ---- JSON：自描述（计数有复数与 *_count 两套名字，环分三桶）
    if opts["json"]:
        involved = set()
        for bucket in (cycles, cycles_true, cycles_pc, mixed):
            for cyc in bucket:
                involved.update(cycle_edges(cyc))
        for v in violations:
            involved.add((v["from"], v["to"]))
        payload = {
            "tool": PROG,
            "roots": roots,
            "missing_roots": missing,
            "java_files": files,
            "files_without_package": no_pkg,
            "unreadable_files": unreadable,
            "packages": len(nodes),
            "package_count": len(nodes),
            "internal_edges": edge_count,
            "edge_count": edge_count,
            "horizontal_edges": h_edge_count,
            "vertical_edges": v_edge_count,
            "scan_sec": round(scan_sec, 3),
            "elapsed_sec": round(time.time() - t0, 3),
            "cycle_mode": ("ignore-parent-child" if opts["ignore_parent_child"]
                           else "count-parent-child-as-cycle"),
            "count_mixed_as_cycle": opts["count_mixed"],
            "cycles": cycles,
            "cycle_count": len(cycles),
            "cycles_truncated": truncated,
            "true_cycles": cycles_true,
            "true_cycle_count": len(cycles_true),
            "true_cycles_truncated": trunc_true,
            "parent_child_cycles": cycles_pc,
            "parent_child_cycle_count": len(cycles_pc),
            "parent_child_cycles_truncated": trunc_pc,
            "mixed_cycles": mixed,
            "mixed_cycle_count": len(mixed),
            "mixed_cycles_truncated": trunc_all,
            "violations": violations,
            "violation_count": len(violations),
            "baseline_file": baseline_path,
            "baseline_written": opts["baseline_write"],
            "known_cycles": known_true_cycles,
            "known_cycle_count": len(known_true_cycles),
            "known_true_cycles": known_true_cycles,
            "known_true_cycle_count": len(known_true_cycles),
            "new_cycles": new_true_cycles,
            "new_cycle_count": len(new_true_cycles),
            "new_true_cycles": new_true_cycles,
            "new_true_cycle_count": len(new_true_cycles),
            "known_violations": known_violation_list,
            "known_violation_count": len(known_violation_list),
            "new_violations": new_violation_list,
            "new_violation_count": len(new_violation_list),
            "baseline_repaid": {
                "cycles": [list(c) for c in repaid_cycles],
                "layers": [{"from": s, "to": d} for s, d in repaid_layers],
                "count": len(repaid_cycles) + len(repaid_layers),
            },
            "unranked_packages": unranked,
            "layer_rules": [{"glob": g, "rank": r} for g, r in rules],
            "fan": [{"package": p, "fan_in": fan_in[p], "fan_out": fan_out[p],
                     "rank": layer[p][0], "rule": layer[p][1]} for p in fan],
            "samples": {"%s -> %s" % e: samples.get(e, []) for e in sorted(involved)},
            "exit_code": exit_code,
        }
        try:
            with open(opts["json"], "w", encoding="utf-8") as handle:
                json.dump(payload, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
        except OSError as exc:
            die("cannot write --json %s: %s" % (opts["json"], exc), code=2)
        if not quiet:
            print("json: %s" % opts["json"])

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
