import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
rnd = random.Random(seed)
r = c = max(2, min(n, 500))
mode = seed % 3
if mode == 0:  # serpentine corridor: very long shortest path
    g = [['.'] * c for _ in range(r)]
    for i in range(1, r, 2):
        for j in range(c): g[i][j] = '#'
        g[i][c - 1 if (i // 2) % 2 == 0 else 0] = '.'
    s, e = (0, 0), (r - 1 if (r - 1) % 2 == 0 else r - 2, c - 1)
else:
    dens = 0.25 if mode == 1 else 0.45
    g = [['#' if rnd.random() < dens else '.' for _ in range(c)] for _ in range(r)]
    s = (rnd.randrange(r), rnd.randrange(c))
    e = s
    while e == s: e = (rnd.randrange(r), rnd.randrange(c))
g[s[0]][s[1]] = 'S'; g[e[0]][e[1]] = 'E'
print(r, c)
print("\n".join("".join(row) for row in g))
