"""Lists every one-token bug that can be planted in a working Python program read from stdin. Prints JSON:
{"normal": the program as ast.unparse prints it, "mutants": [the program with one bug planted, ...]}.
Each mutant differs from "normal" on one line, so fixing its bug turns it back into "normal": the same program
without comments. Runs in the sandbox."""
import ast
import copy
import json
import sys

SWAP = {ast.Lt: ast.LtE, ast.LtE: ast.Lt, ast.Gt: ast.GtE, ast.GtE: ast.Gt, ast.Eq: ast.NotEq, ast.NotEq: ast.Eq,
        ast.Add: ast.Sub, ast.Sub: ast.Add, ast.FloorDiv: ast.Mod, ast.Mod: ast.FloorDiv, ast.And: ast.Or, ast.Or: ast.And}


def sites(tree):
    """(node, change) pairs in ast.walk order, which is the same for every copy of the tree, so a position names one bug."""
    found = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Compare):
            found += [(node, ("compare", i)) for i, op in enumerate(node.ops) if type(op) in SWAP]
        elif isinstance(node, (ast.BinOp, ast.AugAssign, ast.BoolOp)) and type(node.op) in SWAP:
            found.append((node, ("operator",)))
        elif isinstance(node, ast.Constant) and type(node.value) is int and 0 <= node.value <= 2:
            found += [(node, ("constant", 1)), (node, ("constant", -1))]
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in ("min", "max"):
            found.append((node, ("minmax",)))
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "range" and node.args:
            found += [(node, ("range", 1)), (node, ("range", -1))]
        elif isinstance(node, ast.Subscript) and isinstance(node.slice, ast.Name):
            found.append((node, ("index",)))
    return found


def plant(tree, position):
    tree = copy.deepcopy(tree)
    node, (kind, *arg) = sites(tree)[position]
    if kind == "compare":
        node.ops[arg[0]] = SWAP[type(node.ops[arg[0]])]()
    elif kind == "operator":
        node.op = SWAP[type(node.op)]()
    elif kind == "constant":
        node.value += arg[0]
    elif kind == "minmax":
        node.func.id = "max" if node.func.id == "min" else "min"
    elif kind == "range":  # the stop bound, off by one
        stop = 1 if len(node.args) > 1 else 0
        node.args[stop] = ast.BinOp(node.args[stop], ast.Add() if arg[0] > 0 else ast.Sub(), ast.Constant(1))
    else:  # an index, off by one
        node.slice = ast.BinOp(node.slice, ast.Sub(), ast.Constant(1))
    return ast.unparse(tree)


tree = ast.parse(sys.stdin.read())
print(json.dumps({"normal": ast.unparse(tree), "mutants": [plant(tree, i) for i in range(len(sites(tree)))]}))
