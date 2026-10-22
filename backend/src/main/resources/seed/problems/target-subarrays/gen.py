import sys, random
seed, n = int(sys.argv[1]), int(sys.argv[2])
r = random.Random(seed)
n = max(1, min(n, 100000))
mode = seed % 3
if mode == 0:
    a = [0] * n; k = 0
else:
    top = r.choice([2, 10**4])
    a = [r.randint(-top, top) for _ in range(n)]
    l = r.randint(0, n - 1); k = sum(a[l:r.randint(l, min(n - 1, l + 50)) + 1])
print(n, k); print(*a)
